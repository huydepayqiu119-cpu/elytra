package com.campfire.elytra;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

/**
 * CampfireElytra — Entity Property approach (theo Scaffolding)
 *
 * Thay vì dummy entity + reflection packet thủ công, plugin này dùng
 * GeyserDefineEntityPropertiesEvent để register property "campfire:elytra"
 * lên player entity. Resource pack Bedrock đọc giá trị này để chọn model cánh.
 *
 * Variant map: model item_id → int index (0 = vanilla)
 * Mỗi 500ms sweep tất cả session → detect chestplate item_model → update property.
 */
public class ElytraExtension implements Extension {

    // Property name gửi xuống Bedrock client (resource pack đọc)
    private static final String PROPERTY_NAMESPACE = "campfire";
    private static final String PROPERTY_NAME      = "elytra";
    private static final int    MAX_VARIANTS       = 255;

    // Map từ item_model id → variant index
    // index 0 = vanilla elytra (không custom)
    private static final Map<String, Integer> VARIANTS = new LinkedHashMap<>();
    static {
        VARIANTS.put("campfire:elytra_red",    1);
        VARIANTS.put("campfire:elytra_blue",   2);
        VARIANTS.put("campfire:elytra_gold",   3);
        VARIANTS.put("campfire:custom_elytra", 4);
        VARIANTS.put("campfire:elytra",        4);
        // Thêm variants tại đây
    }

    // GeyserEntityProperty handle (set khi register)
    private volatile Object elytraProperty = null;

    // Session → { playerUUID → lastVariant } để chỉ update khi thay đổi
    private final ConcurrentHashMap<Object, ConcurrentHashMap<UUID, Integer>> sessions = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?>        sweepTask;

    // Lazy reflection: GeyserItemStack.getComponent(DataComponentType)
    private volatile Method getComponentMethod = null;
    private volatile Object itemModelType      = null;
    private volatile boolean componentApiAvailable = true;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "campfire-elytra");
            t.setDaemon(true);
            return t;
        });
        logger().info("[CampfireElytra] Entity-property elytra extension enabled. Variants: " + VARIANTS.size());
    }

    @Subscribe
    public void onDefineEntityProperties(GeyserDefineEntityPropertiesEvent event) {
        try {
            Identifier player = Identifier.of("player");
            Identifier prop   = Identifier.of(PROPERTY_NAMESPACE + ":" + PROPERTY_NAME);
            elytraProperty = event.registerIntegerProperty(player, prop, 0, MAX_VARIANTS, 0);
            logger().info("[CampfireElytra] Registered entity property campfire:elytra");
        } catch (Exception e) {
            logger().warning("[CampfireElytra] Failed to register entity property: " + e);
        }
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        Object session = event.connection();
        sessions.put(session, new ConcurrentHashMap<>());

        // Sweep ngay khi join để set property sớm
        scheduler.schedule(() -> sweep(session), 500, TimeUnit.MILLISECONDS);

        // Bắt đầu sweep task toàn bộ nếu chưa chạy
        if (sweepTask == null) {
            sweepTask = scheduler.scheduleWithFixedDelay(this::sweepAll, 500, 500, TimeUnit.MILLISECONDS);
        }
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        sessions.remove(event.connection());
    }

    // ── Sweep ─────────────────────────────────────────────────────────────────

    private void sweepAll() {
        for (Object session : new ArrayList<>(sessions.keySet())) {
            try { sweep(session); } catch (Exception ignored) {}
        }
    }

    private void sweep(Object session) {
        if (elytraProperty == null) return;

        ConcurrentHashMap<UUID, Integer> cache = sessions.get(session);
        if (cache == null) return;

        List<Object> players = getPlayerEntities(session);
        logger().info("[CampfireElytra] sweep: " + players.size() + " player entities found");

        Set<UUID> seen = new HashSet<>();
        for (Object playerEntity : players) {
            UUID uuid = getUuid(playerEntity);
            if (uuid == null || !seen.add(uuid)) continue;

            String model  = wornElytraItemModel(session, playerEntity);
            int   variant = model != null ? VARIANTS.getOrDefault(model, 0) : 0;
            logger().info("[CampfireElytra] uuid=" + uuid + " model=" + model + " variant=" + variant);

            Integer prev = cache.put(uuid, variant);
            if (prev == null || prev != variant) {
                updateProperty(playerEntity, variant);
                logger().info("[CampfireElytra] updateProperty -> " + variant + " for " + uuid);
            }
        }

        cache.keySet().retainAll(seen);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Lấy item_model string của chestplate đang mặc.
     * Nếu là bản thân session → dùng PlayerInventory.
     * Nếu là player khác     → dùng getItemInSlot.
     */
    private String wornElytraItemModel(Object session, Object playerEntity) {
        try {
            // Kiểm tra có phải bản thân không (so UUID)
            Object selfEntity = invokeNoArgs(session, "getPlayerEntity");
            UUID selfUuid = selfEntity != null ? getUuid(selfEntity) : null;
            UUID entityUuid = getUuid(playerEntity);

            Object itemStack;
            if (selfUuid != null && selfUuid.equals(entityUuid)) {
                // Bản thân → lấy từ inventory (chính xác hơn)
                Object inv = invokeNoArgs(session, "getPlayerInventory");
                if (inv == null) return null;
                Map<?, ?> equipment = (Map<?, ?>) invokeNoArgs(inv, "getEquipment");
                if (equipment == null) return null;
                Object chestSlot = findChestplateSlot();
                itemStack = chestSlot != null ? equipment.get(chestSlot) : null;
            } else {
                // Player khác → getItemInSlot
                Object chestSlot = findChestplateSlot();
                if (chestSlot == null) return null;
                itemStack = invokeTyped(playerEntity, "getItemInSlot",
                    new Class[]{chestSlot.getClass()}, chestSlot);
            }

            if (itemStack == null) return null;

            // Log để debug
            logger().info("[CampfireElytra] itemStack=" + itemStack.getClass().getName());

            return getItemModel(itemStack);
        } catch (Exception e) {
            return null;
        }
    }

    /** Đọc item_model component từ GeyserItemStack qua reflection lazy */
    private String getItemModel(Object itemStack) {
        // Thử item_model component trước (Java 1.21+)
        if (componentApiAvailable) {
            try {
                if (getComponentMethod == null || itemModelType == null) {
                    Class<?> dcTypes = Class.forName(
                        "org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes");
                    itemModelType = dcTypes.getField("ITEM_MODEL").get(null);
                    Class<?> dcType = Class.forName(
                        "org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType");
                    getComponentMethod = itemStack.getClass().getMethod("getComponent", dcType);
                }
                Object result = getComponentMethod.invoke(itemStack, itemModelType);
                logger().info("[CampfireElytra] item_model=" + result);
                if (result != null && !result.toString().equals("minecraft:elytra")) return result.toString();
            } catch (Exception e) {
                componentApiAvailable = false;
                logger().warning("[CampfireElytra] item_model component unavailable, falling back to CustomModelData: " + e.getMessage());
            }
        }

        // Fallback: dump tất cả components để tìm CustomModelData
        try {
            Object allComponents = itemStack.getClass().getMethod("getAllComponents").invoke(itemStack);
            logger().info("[CampfireElytra] getAllComponents=" + allComponents);
            if (allComponents instanceof Map<?,?> map) {
                for (Map.Entry<?,?> e : map.entrySet()) {
                    logger().info("[CampfireElytra]   component: " + e.getKey() + " = " + e.getValue());
                }
            }
        } catch (Exception e) {
            logger().warning("[CampfireElytra] getAllComponents failed: " + e);
        }
        return null;
    }

    /** Map CustomModelData int → item_model string (khớp với Geyser mapping JSON) */
    private String customModelDataToModel(int cmd) {
        // Tra ngược VARIANTS map hoặc dùng hardcode theo mapping file
        for (Map.Entry<String, Integer> e : VARIANTS.entrySet()) {
            // Nếu variant index khớp với cmd (legacy mapping dùng cmd làm variant)
        }
        // Fallback: dùng map riêng cho CustomModelData
        return CMD_MAP.get(cmd);
    }

    // Map CustomModelData → item_model id (theo file mapping JSON của bạn)
    private static final Map<Integer, String> CMD_MAP = new HashMap<>();
    static {
        CMD_MAP.put(1000, "campfire:custom_elytra");
        // Thêm các CMD khác tại đây nếu có
    }

    /** Gọi playerEntity.updateProperty(elytraProperty, variant) */
    private void updateProperty(Object playerEntity, int variant) {
        try {
            for (Method m : playerEntity.getClass().getMethods()) {
                if (m.getName().equals("updateProperty") && m.getParameterCount() == 2) {
                    m.invoke(playerEntity, elytraProperty, variant);
                    logger().info("[CampfireElytra] updateProperty called successfully, method=" + m);
                    return;
                }
            }
            logger().warning("[CampfireElytra] updateProperty method NOT FOUND on " + playerEntity.getClass().getName());
        } catch (Exception e) {
            logger().warning("[CampfireElytra] updateProperty failed: " + e);
        }
    }

    /** Lấy tất cả PlayerEntity trong session: bản thân + entity cache */
    private List<Object> getPlayerEntities(Object session) {
        List<Object> result = new ArrayList<>();
        try {
            Object self = invokeNoArgs(session, "getPlayerEntity");
            if (self != null) result.add(self);

            Object cache = invokeNoArgs(session, "getEntityCache");
            if (cache != null) {
                // forEachPlayerEntity(Consumer<PlayerEntity>)
                try {
                    Method m = cache.getClass().getMethod("forEachPlayerEntity", java.util.function.Consumer.class);
                    m.invoke(cache, (java.util.function.Consumer<Object>) result::add);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    private UUID getUuid(Object playerEntity) {
        try {
            Object u = invokeNoArgs(playerEntity, "uuid");
            if (u instanceof UUID uuid) return uuid;
            u = invokeNoArgs(playerEntity, "getUuid");
            if (u instanceof UUID uuid) return uuid;
        } catch (Exception ignored) {}
        return null;
    }

    // Cache chestplate slot
    private volatile Object chestplateSlot = null;
    private Object findChestplateSlot() {
        if (chestplateSlot != null) return chestplateSlot;
        try {
            Class<?> cls = Class.forName(
                "org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot");
            for (Object c : cls.getEnumConstants()) {
                if (c.toString().equals("CHESTPLATE")) { chestplateSlot = c; return c; }
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
}
