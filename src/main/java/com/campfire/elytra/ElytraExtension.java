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
 * Trick: Geyser chỉ set IS_GLIDING cho minecraft:elytra vanilla.
 * Với custom item, ta tự poll trạng thái elytra từ Java side (EntityMetadata / isFlyingWithElytra)
 * rồi inject IS_GLIDING=true qua SetEntityDataPacket xuống Bedrock client.
 *
 * Flow:
 *  1. Mỗi 100ms, lấy playerEntity từ session
 *  2. Đọc Java-side flag "isFlyingWithElytra" từ metadata hoặc entity flags
 *  3. Nếu đang bay AND mặc custom elytra → inject IS_GLIDING packet
 *  4. Nếu không → inject IS_GLIDING=false để tắt glide animation
 */
public class ElytraExtension implements Extension {

    // Các Java identifier được coi là custom elytra (thêm vào đây nếu cần)
    private static final Set<String> CUSTOM_ELYTRA_IDS = Set.of(
        "campfire:elytra",
        "campfire:custom_elytra"
        // thêm identifier custom item của bạn ở đây
    );

    private final Map<UUID, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    // track trạng thái glide trước đó để chỉ gửi packet khi thay đổi
    private final Map<UUID, Boolean> lastGlideState = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        scheduler = Executors.newScheduledThreadPool(4);
        this.logger().info("CampfireElytra enabled.");
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        GeyserConnection connection = event.connection();
        UUID uuid = connection.playerUuid();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                tickCustomElytra(connection);
            } catch (Exception e) {
                // silent fail — session có thể đang disconnect
            }
        }, 200, 100, TimeUnit.MILLISECONDS);
        tasks.put(uuid, task);
        lastGlideState.put(uuid, false);
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        UUID uuid = event.connection().playerUuid();
        ScheduledFuture<?> task = tasks.remove(uuid);
        if (task != null) task.cancel(false);
        lastGlideState.remove(uuid);
    }

    private void tickCustomElytra(GeyserConnection connection) throws Exception {
        Object session = connection;

        // 1. Kiểm tra player đang mặc custom elytra không
        if (!isWearingCustomElytra(session)) {
            // nếu không, đảm bảo glide state tắt
            forceGlideState(session, false);
            return;
        }

        // 2. Đọc trạng thái "đang bay elytra" từ Java entity metadata
        boolean isFlyingWithElytra = readJavaGlidingFlag(session);

        // 3. Chỉ gửi packet nếu state thay đổi
        UUID uuid = connection.playerUuid();
        boolean last = lastGlideState.getOrDefault(uuid, false);
        if (isFlyingWithElytra != last) {
            lastGlideState.put(uuid, isFlyingWithElytra);
            forceGlideState(session, isFlyingWithElytra);
        }
    }

    /**
     * Đọc cờ "đang lướt elytra" từ Java entity data.
     * Geyser lưu các flag Java trong playerEntity dưới dạng EntityMetadata.
     * Flag index 7 bit 7 = isFlyingWithElytra theo Java protocol.
     */
    private boolean readJavaGlidingFlag(Object session) {
        try {
            Object playerEntity = getField(session, "playerEntity");
            if (playerEntity == null) playerEntity = invokeNoArgs(session, "getPlayerEntity");
            if (playerEntity == null) return false;

            // Thử đọc trực tiếp field "flying" hoặc "elytraFlying" từ entity
            for (String fieldName : new String[]{"elytraFlying", "flyingWithElytra", "gliding"}) {
                try {
                    Object val = getField(playerEntity, fieldName);
                    if (val instanceof Boolean b) return b;
                } catch (Exception ignored) {}
            }

            // Thử qua Java metadata: EntityMetadata list, tìm index 7 (shared flags)
            Object metadata = invokeNoArgs(playerEntity, "getMetadata");
            if (metadata == null) metadata = getField(playerEntity, "metadata");
            if (metadata != null) {
                // metadata thường là Map<Integer, EntityMetadata>
                if (metadata instanceof Map<?, ?> map) {
                    Object entry = map.get(7); // index 7 = shared flags byte
                    if (entry != null) {
                        Object value = invokeNoArgs(entry, "getValue");
                        if (value instanceof Number n) {
                            int flags = n.intValue();
                            return (flags & (1 << 7)) != 0; // bit 7 = isFlyingWithElytra
                        }
                    }
                }
            }

            // Fallback: đọc Bedrock IS_GLIDING (nếu Geyser đã propagate)
            return readBedrockGlidingFlag(playerEntity);

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fallback: đọc IS_GLIDING từ Bedrock entity flags.
     */
    private boolean readBedrockGlidingFlag(Object playerEntity) {
        try {
            Object flags = invokeNoArgs(playerEntity, "getFlags");
            if (flags == null) return false;
            Class<?> flagClass = Class.forName("org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag");
            Object isGlidingConst = getEnumConstant(flagClass, "IS_GLIDING");
            if (isGlidingConst == null) return false;
            Object result = invokeTyped(flags, "getFlag", new Class[]{flagClass}, isGlidingConst);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Inject IS_GLIDING = state vào Bedrock client bằng SetEntityDataPacket.
     */
    private void forceGlideState(Object session, boolean state) {
        try {
            Object playerEntity = getField(session, "playerEntity");
            if (playerEntity == null) playerEntity = invokeNoArgs(session, "getPlayerEntity");
            if (playerEntity == null) return;

            Object flags = invokeNoArgs(playerEntity, "getFlags");
            if (flags == null) return;

            Class<?> flagClass = Class.forName("org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag");
            Object isGlidingConst = getEnumConstant(flagClass, "IS_GLIDING");
            if (isGlidingConst == null) return;

            // Set flag trên entity
            invokeTyped(flags, "setFlag", new Class[]{flagClass, boolean.class}, isGlidingConst, state);

            // Lấy runtimeId
            Object runtimeIdObj = invokeNoArgs(playerEntity, "getGeyserId");
            if (runtimeIdObj == null) return;
            long runtimeId = ((Number) runtimeIdObj).longValue();

            // Lấy dirty metadata — thử nhiều tên khác nhau
            Object dirtyMetadata = null;
            for (String m : new String[]{"getDirtyMetadata", "getMetadata", "dirtyMetadata"}) {
                try {
                    dirtyMetadata = invokeNoArgs(playerEntity, m);
                    if (dirtyMetadata == null) dirtyMetadata = getField(playerEntity, m.replace("get", "").toLowerCase());
                    if (dirtyMetadata != null) break;
                } catch (Exception ignored) {}
            }
            if (dirtyMetadata == null) return;

            // Build SetEntityDataPacket
            Class<?> packetClass = Class.forName("org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket");
            Object packet = packetClass.getDeclaredConstructor().newInstance();
            setField(packet, "runtimeEntityId", runtimeId);
            setField(packet, "metadata", dirtyMetadata);
            setField(packet, "tick", 0L);

            // Gửi upstream (Geyser → Bedrock client)
            Class<?> bedrockPacketClass = Class.forName("org.cloudburstmc.protocol.bedrock.packet.BedrockPacket");
            invokeTyped(session, "sendUpstreamPacket", new Class[]{bedrockPacketClass}, packet);

        } catch (Exception e) {
            // silent fail
        }
    }

    /**
     * Kiểm tra chestplate có phải custom elytra không.
     * Chỉ check các identifier trong CUSTOM_ELYTRA_IDS — vanilla minecraft:elytra bị bỏ qua.
     */
    private boolean isWearingCustomElytra(Object session) {
        try {
            Object inventory = invokeNoArgs(session, "getPlayerInventory");
            if (inventory == null) return false;

            Object chestplate = invokeNoArgs(inventory, "getChestplate");
            if (chestplate == null) return false;

            // Lấy Java identifier của item
            String javaId = getJavaItemId(chestplate, session);
            if (javaId == null) return false;

            return CUSTOM_ELYTRA_IDS.contains(javaId)
                || (javaId.contains("elytra") && !javaId.equals("minecraft:elytra"));

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lấy Java identifier của item từ GeyserItemStack.
     * Thử nhiều path khác nhau vì Geyser internal API hay thay đổi.
     */
    private String getJavaItemId(Object itemStack, Object session) {
        // Thử getJavaIdentifier trực tiếp
        for (String m : new String[]{"getJavaIdentifier", "getJavaId", "identifier"}) {
            try {
                Object r = invokeNoArgs(itemStack, m);
                if (r != null) return r.toString();
            } catch (Exception ignored) {}
        }

        // Thử qua mapping
        for (String m : new String[]{"getMapping", "getItemMapping", "mapping"}) {
            try {
                Object mapping = invokeNoArgs(itemStack, m);
                if (mapping == null) {
                    // Một số version cần truyền session vào
                    try { mapping = invokeTyped(itemStack, "getMapping",
                            new Class[]{session.getClass()}, session); } catch (Exception ignored) {}
                }
                if (mapping == null) continue;
                for (String mid : new String[]{"getJavaIdentifier", "javaIdentifier", "identifier"}) {
                    try {
                        Object r = invokeNoArgs(mapping, mid);
                        if (r != null) return r.toString();
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        // Thử lấy javaId (int) rồi resolve
        try {
            Object javaIdObj = invokeNoArgs(itemStack, "getJavaId");
            if (javaIdObj instanceof Number) {
                // Tìm ItemMapping registry từ session
                Object itemMappings = invokeNoArgs(session, "getItemMappings");
                if (itemMappings == null) itemMappings = invokeNoArgs(session, "getMappings");
                if (itemMappings != null) {
                    Object mappingEntry = invokeTyped(itemMappings, "getMapping",
                            new Class[]{int.class}, ((Number) javaIdObj).intValue());
                    if (mappingEntry != null) {
                        Object r = invokeNoArgs(mappingEntry, "getJavaIdentifier");
                        if (r != null) return r.toString();
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    // ── Reflection helpers ──────────────────────────────────────────────────

    private Object invokeNoArgs(Object target, String name) throws Exception {
        if (target == null) return null;
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {}
            // check interfaces too
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

    private Object getEnumConstant(Class<?> enumClass, String name) {
        for (Object c : enumClass.getEnumConstants()) {
            if (c.toString().equals(name)) return c;
        }
        return null;
    }
}
