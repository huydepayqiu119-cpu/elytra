package com.campfire.elytra;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Trick: Giống hệt Bedrock Script approach — swap chestplate thật.
 *
 * Khi player rời mặt đất và đang mặc custom elytra:
 *   → Geyser gửi packet thay chestplate thành minecraft:elytra thật về Bedrock client
 *   → Client thấy elytra thật → glide bình thường, animation đúng
 *
 * Khi player chạm đất:
 *   → Swap ngược lại custom item
 *
 * Không cần động vào Java server inventory — chỉ thao tác phía Bedrock upstream.
 */
public class ElytraExtension implements Extension {

    // Các custom elytra identifier (Java item id)
    // Item chứa "elytra" trong tên (trừ minecraft:elytra) sẽ tự động được nhận
    private static final Set<String> CUSTOM_ELYTRA_IDS = Set.of(
        "campfire:custom_elytra",
        "campfire:elytra"
        // thêm identifier của bạn ở đây nếu cần
    );

    // State per player
    private static class PlayerState {
        boolean elytraActive = false;      // đang hiển thị elytra thật về phía Bedrock
        String  savedCustomId = null;      // identifier custom item đã lưu
        boolean wasAirborne   = false;     // frame trước có trên không không
    }

    private final Map<UUID, PlayerState>    states    = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        scheduler = Executors.newScheduledThreadPool(4);
        logger().info("CampfireElytra enabled.");
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        GeyserConnection conn = event.connection();
        UUID uuid = conn.playerUuid();
        states.put(uuid, new PlayerState());
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try { tick(conn); } catch (Exception ignored) {}
        }, 300, 100, TimeUnit.MILLISECONDS);
        tasks.put(uuid, task);
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        UUID uuid = event.connection().playerUuid();
        ScheduledFuture<?> t = tasks.remove(uuid);
        if (t != null) t.cancel(false);
        states.remove(uuid);
    }

    // ── Core tick ────────────────────────────────────────────────────────────

    private void tick(GeyserConnection connection) throws Exception {
        Object session = connection;
        UUID uuid = connection.playerUuid();
        PlayerState state = states.get(uuid);
        if (state == null) return;

        // Lấy inventory Bedrock-side (GeyserItemStack)
        Object inventory = invokeNoArgs(session, "getPlayerInventory");
        if (inventory == null) return;

        Object chestItem = invokeNoArgs(inventory, "getChestplate");

        // Kiểm tra player có đang trên không không
        boolean airborne = isAirborne(session);

        if (!state.elytraActive) {
            // --- Chưa swap: kiểm tra xem có nên swap sang elytra không ---
            if (chestItem == null) return;
            String customId = getJavaId(chestItem);
            if (!isCustomElytra(customId)) return;

            if (airborne) {
                // Swap: ghi nhớ custom item rồi gửi elytra về phía Bedrock
                state.savedCustomId  = customId;
                state.elytraActive   = true;
                sendChestplatePacket(session, "minecraft:elytra");
            }
        } else {
            // --- Đang swap: kiểm tra có nên swap ngược lại không ---
            if (!airborne && state.wasAirborne) {
                // Vừa chạm đất → swap lại
                state.elytraActive = false;
                String restoreId = state.savedCustomId != null ? state.savedCustomId : "minecraft:elytra";
                sendChestplatePacket(session, restoreId);
                state.savedCustomId = null;
            }
        }

        state.wasAirborne = airborne;
    }

    // ── Gửi packet đổi chestplate về Bedrock client ──────────────────────────

    /**
     * Gửi MobEquipmentPacket / InventorySlotPacket về phía Bedrock để client
     * thấy chestplate thay đổi mà không thật sự thay đổi Java inventory.
     */
    private void sendChestplatePacket(Object session, String itemId) {
        try {
            // Lấy ItemMapping cho itemId từ ItemMappings của session
            Object itemMappings = getItemMappings(session);
            if (itemMappings == null) return;

            Object bedrockData = resolveBedrockItem(itemMappings, itemId);
            if (bedrockData == null) return;

            // Build InventorySlotPacket (slot 6 = chestplate trong Bedrock inventory)
            Class<?> packetClass = Class.forName(
                "org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket");
            Object packet = packetClass.getDeclaredConstructor().newInstance();

            // containerId = 0 (player inventory), slot 6 = chestplate
            setField(packet, "containerId", 0);
            setField(packet, "slot", 6);
            setField(packet, "item", bedrockData);

            Class<?> bedrockPacketClass = Class.forName(
                "org.cloudburstmc.protocol.bedrock.packet.BedrockPacket");
            invokeTyped(session, "sendUpstreamPacket",
                new Class[]{bedrockPacketClass}, packet);

        } catch (Exception e) {
            // Fallback: thử MobEquipmentPacket
            try { sendMobEquipmentPacket(session, itemId); } catch (Exception ignored) {}
        }
    }

    private void sendMobEquipmentPacket(Object session, String itemId) throws Exception {
        Object itemMappings = getItemMappings(session);
        if (itemMappings == null) return;

        Object bedrockData = resolveBedrockItem(itemMappings, itemId);
        if (bedrockData == null) return;

        Object playerEntity = invokeNoArgs(session, "getPlayerEntity");
        if (playerEntity == null) return;
        long runtimeId = ((Number) invokeNoArgs(playerEntity, "getGeyserId")).longValue();

        Class<?> packetClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket");
        Object packet = packetClass.getDeclaredConstructor().newInstance();
        setField(packet, "runtimeEntityId", runtimeId);
        setField(packet, "item", bedrockData);
        setField(packet, "inventorySlot", 6);
        setField(packet, "hotbarSlot", 0);
        setField(packet, "containerId", (byte) 6); // ARMOR container

        Class<?> bedrockPacketClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.BedrockPacket");
        invokeTyped(session, "sendUpstreamPacket",
            new Class[]{bedrockPacketClass}, packet);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAirborne(Object session) {
        try {
            // Thử đọc onGround flag từ playerEntity
            Object playerEntity = invokeNoArgs(session, "getPlayerEntity");
            if (playerEntity == null) return false;

            for (String f : new String[]{"onGround", "isOnGround"}) {
                try {
                    Object val = getField(playerEntity, f);
                    if (val instanceof Boolean b) return !b;
                } catch (Exception ignored) {}
                try {
                    Object val = invokeNoArgs(playerEntity, f);
                    if (val instanceof Boolean b) return !b;
                } catch (Exception ignored) {}
            }

            // Fallback: đọc qua Geyser connection API
            Object onGround = invokeNoArgs(session, "isOnGround");
            if (onGround instanceof Boolean b) return !b;

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Object getItemMappings(Object session) {
        for (String m : new String[]{"getItemMappings", "getMappings", "getCodecHelper"}) {
            try {
                Object r = invokeNoArgs(session, m);
                if (r != null) return r;
            } catch (Exception ignored) {}
        }
        try {
            // Thử qua upstream session
            Object upstream = getField(session, "upstream");
            if (upstream != null) return invokeNoArgs(upstream, "getItemDefinitions");
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Resolve Bedrock ItemData từ Java identifier.
     * Trả về ItemData object để nhét vào packet.
     */
    private Object resolveBedrockItem(Object itemMappings, String javaId) {
        try {
            // Thử getMapping(String) trực tiếp
            for (String m : new String[]{"getMapping", "getItemMapping", "getMappingByJavaIdentifier"}) {
                try {
                    Object mapping = invokeTyped(itemMappings, m,
                        new Class[]{String.class}, javaId);
                    if (mapping != null) {
                        // Convert mapping → ItemData
                        return mappingToItemData(mapping);
                    }
                } catch (Exception ignored) {}
            }

            // Thử duyệt mappings list
            Object mappingsList = invokeNoArgs(itemMappings, "getItems");
            if (mappingsList instanceof Iterable<?> it) {
                for (Object entry : it) {
                    try {
                        Object id = invokeNoArgs(entry, "getJavaIdentifier");
                        if (id != null && id.toString().equals(javaId)) {
                            return mappingToItemData(entry);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object mappingToItemData(Object mapping) throws Exception {
        // Thử getItemData() trực tiếp
        try {
            Object data = invokeNoArgs(mapping, "getItemData");
            if (data != null) return data;
        } catch (Exception ignored) {}

        // Build ItemData từ bedrockId + bedrockData
        Object bedrockIdObj = invokeNoArgs(mapping, "getBedrockId");
        if (bedrockIdObj == null) return null;
        int bedrockId = ((Number) bedrockIdObj).intValue();

        Class<?> itemDataClass = Class.forName("org.cloudburstmc.protocol.bedrock.data.inventory.ItemData");
        // ItemData.builder().id(x).count(1).build()
        Object builder = itemDataClass.getMethod("builder").invoke(null);
        invokeTyped(builder, "id", new Class[]{int.class}, bedrockId);
        invokeTyped(builder, "count", new Class[]{int.class}, 1);
        return invokeNoArgs(builder, "build");
    }

    private boolean isCustomElytra(String id) {
        if (id == null) return false;
        if (CUSTOM_ELYTRA_IDS.contains(id)) return true;
        return id.contains("elytra") && !id.equals("minecraft:elytra");
    }

    private String getJavaId(Object itemStack) {
        for (String m : new String[]{"getJavaIdentifier", "getJavaId", "identifier"}) {
            try {
                Object r = invokeNoArgs(itemStack, m);
                if (r != null) return r.toString();
            } catch (Exception ignored) {}
        }
        try {
            Object mapping = invokeNoArgs(itemStack, "getMapping");
            if (mapping != null) {
                Object r = invokeNoArgs(mapping, "getJavaIdentifier");
                if (r != null) return r.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Reflection utils ──────────────────────────────────────────────────────

    private Object invokeNoArgs(Object target, String name) throws Exception {
        if (target == null) return null;
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {}
            for (Class<?> iface : clazz.getInterfaces()) {
                try {
                    Method m = iface.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (NoSuchMethodException ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private Object invokeTyped(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        if (target == null) return null;
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private Object getField(Object target, String name) {
        if (target == null) return null;
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void setField(Object target, String name, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }
}
