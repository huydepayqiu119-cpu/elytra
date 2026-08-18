package com.campfire.elytra;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ElytraExtension — Dummy-entity approach
 *
 * Khi player mặc custom elytra và nhảy lên không:
 *  1. Geyser gửi AddEntityPacket → spawn dummy entity (invisible) tại vị trí player
 *  2. SetEntityLinkPacket → player ride dummy entity
 *  3. Mỗi tick: đọc look direction của player → tính velocity vector →
 *     gửi MoveEntityAbsolutePacket / SetEntityMotionPacket để di chuyển dummy
 *  4. Khi player chạm đất hoặc tháo elytra → SetEntityLinkPacket (unlink) +
 *     RemoveEntityPacket
 *
 * Dummy entity type: dùng "minecraft:area_effect_cloud" hoặc boat —
 * loại invisible, không có hitbox ảnh hưởng, không bị gravity kéo.
 */
public class ElytraExtension implements Extension {

    // Custom elytra Java identifiers
    private static final Set<String> CUSTOM_ELYTRA_IDS = Set.of(
        "campfire:custom_elytra",
        "campfire:elytra"
    );

    // Entity type dùng làm dummy (area_effect_cloud: invisible, no gravity khi set)
    private static final String DUMMY_ENTITY_TYPE = "minecraft:area_effect_cloud";

    // Elytra glide speed (blocks/tick ở 20tps)
    private static final float GLIDE_SPEED        = 0.6f;
    private static final float GLIDE_SPEED_MIN    = 0.1f;
    private static final float GRAVITY            = 0.05f; // kéo xuống mỗi tick nếu không glide

    // Runtime ID counter — phải không trùng với entity server thật
    private static final AtomicLong ENTITY_ID_COUNTER = new AtomicLong(100_000_000L);

    // ── Per-player state ──────────────────────────────────────────────────────

    private static class PlayerState {
        long    dummyEntityId   = -1;   // Bedrock runtime ID của dummy entity
        boolean riding          = false; // đang ride dummy entity
        float   velocityX       = 0f;
        float   velocityY       = 0f;
        float   velocityZ       = 0f;
        String  savedCustomId   = null;
    }

    private final Map<UUID, PlayerState>        states    = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> tasks     = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        scheduler = Executors.newScheduledThreadPool(4);
        logger().info("[CampfireElytra] Dummy-entity elytra extension enabled.");
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        GeyserConnection conn = event.connection();
        UUID uuid = conn.playerUuid();
        states.put(uuid, new PlayerState());
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
            () -> { try { tick(conn); } catch (Exception ignored) {} },
            500, 50, TimeUnit.MILLISECONDS // 50ms = 20 tps
        );
        tasks.put(uuid, task);
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        UUID uuid = event.connection().playerUuid();
        ScheduledFuture<?> t = tasks.remove(uuid);
        if (t != null) t.cancel(false);
        PlayerState state = states.remove(uuid);
        if (state != null && state.riding) {
            try { dismountAndRemoveDummy(event.connection(), state); } catch (Exception ignored) {}
        }
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    private void tick(GeyserConnection conn) throws Exception {
        UUID uuid = conn.playerUuid();
        PlayerState state = states.get(uuid);
        if (state == null) return;

        boolean wearingCustom = isWearingCustomElytra(conn, state);
        boolean airborne      = isAirborne(conn);

        if (!state.riding) {
            // Bắt đầu glide: đang mặc custom elytra + trên không
            if (wearingCustom && airborne) {
                spawnAndMount(conn, state);
            }
        } else {
            // Đang glide
            if (!wearingCustom || !airborne) {
                // Tháo elytra hoặc chạm đất → dừng
                dismountAndRemoveDummy(conn, state);
            } else {
                // Tiếp tục: cập nhật vị trí dummy theo look direction
                updateGlide(conn, state);
            }
        }
    }

    // ── Spawn dummy + cho player ride ─────────────────────────────────────────

    private void spawnAndMount(GeyserConnection conn, PlayerState state) throws Exception {
        long entityId = ENTITY_ID_COUNTER.getAndIncrement();
        state.dummyEntityId = entityId;

        // Vị trí hiện tại của player
        float[] pos = getPlayerPosition(conn);
        if (pos == null) return;

        sendAddEntityPacket(conn, entityId, pos[0], pos[1], pos[2]);
        sendSetEntityLinkPacket(conn, entityId, getPlayerRuntimeId(conn), true);

        state.riding      = true;
        state.velocityY   = 0f;
        logger().debug("[CampfireElytra] Player {} mounted dummy entity {}", conn.playerUuid(), entityId);
    }

    // ── Di chuyển dummy theo hướng nhìn ───────────────────────────────────────

    private void updateGlide(GeyserConnection conn, PlayerState state) throws Exception {
        float[] rot = getPlayerRotation(conn); // [yaw, pitch]
        float[] pos = getPlayerPosition(conn);
        if (rot == null || pos == null) return;

        float yaw   = rot[0];
        float pitch = rot[1];

        // Tính vector hướng nhìn
        double pitchRad = Math.toRadians(pitch);
        double yawRad   = Math.toRadians(yaw);

        float speed = GLIDE_SPEED;
        // Pitch dương = nhìn xuống → giảm tốc dọc, pitch âm = nhìn lên
        float hSpeed = (float)(speed * Math.cos(pitchRad));
        float vSpeed = (float)(-speed * Math.sin(pitchRad));

        state.velocityX = (float)(-hSpeed * Math.sin(yawRad));
        state.velocityY = vSpeed;
        state.velocityZ = (float)( hSpeed * Math.cos(yawRad));

        float newX = pos[0] + state.velocityX;
        float newY = pos[1] + state.velocityY;
        float newZ = pos[2] + state.velocityZ;

        sendMoveEntityPacket(conn, state.dummyEntityId, newX, newY, newZ, yaw, pitch);
        sendSetEntityMotionPacket(conn, state.dummyEntityId,
            state.velocityX, state.velocityY, state.velocityZ);
    }

    // ── Dismount + remove dummy ───────────────────────────────────────────────

    private void dismountAndRemoveDummy(GeyserConnection conn, PlayerState state) throws Exception {
        if (state.dummyEntityId < 0) return;
        sendSetEntityLinkPacket(conn, state.dummyEntityId, getPlayerRuntimeId(conn), false);
        sendRemoveEntityPacket(conn, state.dummyEntityId);
        state.riding      = false;
        state.dummyEntityId = -1;
        state.velocityX = state.velocityY = state.velocityZ = 0f;
    }

    // ── Packet senders ────────────────────────────────────────────────────────

    private void sendAddEntityPacket(GeyserConnection conn, long entityId,
                                     float x, float y, float z) throws Exception {
        Class<?> pkClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket");
        Object pk = pkClass.getDeclaredConstructor().newInstance();

        setField(pk, "runtimeEntityId",   entityId);
        setField(pk, "uniqueEntityId",    entityId);
        setField(pk, "identifier",        DUMMY_ENTITY_TYPE);

        // Position
        Object vecClass3f = buildVector3f(x, y, z);
        setField(pk, "position", vecClass3f);
        setField(pk, "motion",   buildVector3f(0, 0, 0));
        setField(pk, "rotation", buildVector2f(0, 0));

        // Attributes + metadata lists
        trySetEmptyList(pk, "attributes");
        trySetEmptyList(pk, "metadata");
        trySetEmptyList(pk, "links");
        trySetEmptyMap(pk, "properties");

        sendUpstream(conn, pk);
    }

    private void sendSetEntityLinkPacket(GeyserConnection conn, long riderOf,
                                          long riderId, boolean mount) throws Exception {
        Class<?> pkClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket");
        Object pk = pkClass.getDeclaredConstructor().newInstance();

        // EntityLinkData(from, to, type, immediate, passengerInitiated)
        Class<?> linkClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData");
        Class<?> linkTypeClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData$Type");
        Object linkType = mount
            ? getEnumConstant(linkTypeClass, "RIDER")
            : getEnumConstant(linkTypeClass, "REMOVE");

        Object link = linkClass.getDeclaredConstructors()[0].newInstance(
            riderOf, riderId, linkType, true, false
        );
        setField(pk, "entityLink", link);
        sendUpstream(conn, pk);
    }

    private void sendMoveEntityPacket(GeyserConnection conn, long entityId,
                                       float x, float y, float z,
                                       float yaw, float pitch) throws Exception {
        Class<?> pkClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket");
        Object pk = pkClass.getDeclaredConstructor().newInstance();

        setField(pk, "runtimeEntityId", entityId);
        setField(pk, "position",        buildVector3f(x, y, z));
        setField(pk, "rotation",        buildVector3f(pitch, yaw, 0)); // pitch, yaw, roll
        setField(pk, "onGround",        false);
        setField(pk, "teleported",      false);
        sendUpstream(conn, pk);
    }

    private void sendSetEntityMotionPacket(GeyserConnection conn, long entityId,
                                            float vx, float vy, float vz) throws Exception {
        Class<?> pkClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket");
        Object pk = pkClass.getDeclaredConstructor().newInstance();

        setField(pk, "runtimeEntityId", entityId);
        setField(pk, "motion",          buildVector3f(vx, vy, vz));
        sendUpstream(conn, pk);
    }

    private void sendRemoveEntityPacket(GeyserConnection conn, long entityId) throws Exception {
        Class<?> pkClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket");
        Object pk = pkClass.getDeclaredConstructor().newInstance();
        setField(pk, "uniqueEntityId", entityId);
        sendUpstream(conn, pk);
    }

    private void sendUpstream(GeyserConnection conn, Object packet) throws Exception {
        Class<?> bedrockPk = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.BedrockPacket");
        invokeTyped(conn, "sendUpstreamPacket", new Class[]{bedrockPk}, packet);
    }

    // ── Player state helpers ──────────────────────────────────────────────────

    private boolean isWearingCustomElytra(GeyserConnection conn, PlayerState state) {
        try {
            Object inventory = invokeNoArgs(conn, "getPlayerInventory");
            if (inventory == null) return false;
            Object chest = invokeNoArgs(inventory, "getChestplate");
            if (chest == null) return false;
            String id = getJavaId(chest);
            boolean is = isCustomElytra(id);
            if (is) state.savedCustomId = id;
            return is;
        } catch (Exception e) { return false; }
    }

    private boolean isAirborne(GeyserConnection conn) {
        try {
            Object playerEntity = invokeNoArgs(conn, "getPlayerEntity");
            if (playerEntity == null) return false;
            for (String f : new String[]{"onGround", "isOnGround"}) {
                try { Object v = getFieldVal(playerEntity, f); if (v instanceof Boolean b) return !b; } catch (Exception ignored) {}
                try { Object v = invokeNoArgs(playerEntity, f);  if (v instanceof Boolean b) return !b; } catch (Exception ignored) {}
            }
            Object v = invokeNoArgs(conn, "isOnGround");
            if (v instanceof Boolean b) return !b;
        } catch (Exception ignored) {}
        return false;
    }

    private float[] getPlayerPosition(GeyserConnection conn) {
        try {
            Object entity = invokeNoArgs(conn, "getPlayerEntity");
            if (entity == null) return null;
            Object pos = invokeNoArgs(entity, "getPosition");
            if (pos == null) return null;
            float x = ((Number) invokeNoArgs(pos, "getX")).floatValue();
            float y = ((Number) invokeNoArgs(pos, "getY")).floatValue();
            float z = ((Number) invokeNoArgs(pos, "getZ")).floatValue();
            return new float[]{x, y, z};
        } catch (Exception e) { return null; }
    }

    /** Returns [yaw, pitch] in degrees */
    private float[] getPlayerRotation(GeyserConnection conn) {
        try {
            Object entity = invokeNoArgs(conn, "getPlayerEntity");
            if (entity == null) return null;
            // Geyser entity thường có rotation() trả về Vector3f(pitch, headYaw, bodyYaw)
            // hoặc các field riêng
            for (String m : new String[]{"getRotation", "rotation"}) {
                try {
                    Object rot = invokeNoArgs(entity, m);
                    if (rot == null) continue;
                    float pitch = ((Number) invokeNoArgs(rot, "getX")).floatValue();
                    float yaw   = ((Number) invokeNoArgs(rot, "getY")).floatValue();
                    return new float[]{yaw, pitch};
                } catch (Exception ignored) {}
            }
            // Fallback: đọc field
            for (String f : new String[]{"yaw", "headYaw"}) {
                try {
                    Object yawObj   = getFieldVal(entity, f);
                    Object pitchObj = getFieldVal(entity, "pitch");
                    if (yawObj != null && pitchObj != null) {
                        return new float[]{
                            ((Number) yawObj).floatValue(),
                            ((Number) pitchObj).floatValue()
                        };
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return new float[]{0f, 0f};
    }

    private long getPlayerRuntimeId(GeyserConnection conn) {
        try {
            Object entity = invokeNoArgs(conn, "getPlayerEntity");
            if (entity == null) return 0L;
            for (String m : new String[]{"getGeyserId", "getRuntimeId", "runtimeId"}) {
                try {
                    Object v = invokeNoArgs(entity, m);
                    if (v instanceof Number n) return n.longValue();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    // ── Util builders ─────────────────────────────────────────────────────────

    private Object buildVector3f(float x, float y, float z) throws Exception {
        Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector3f");
        return cls.getMethod("from", float.class, float.class, float.class).invoke(null, x, y, z);
    }

    private Object buildVector2f(float x, float y) throws Exception {
        Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector2f");
        return cls.getMethod("from", float.class, float.class).invoke(null, x, y);
    }

    private Object getEnumConstant(Class<?> enumClass, String name) {
        for (Object c : enumClass.getEnumConstants()) {
            if (c.toString().equals(name)) return c;
        }
        return enumClass.getEnumConstants()[0];
    }

    @SuppressWarnings("unchecked")
    private void trySetEmptyList(Object target, String fieldName) {
        try { setField(target, fieldName, new ArrayList<>()); } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private void trySetEmptyMap(Object target, String fieldName) {
        try { setField(target, fieldName, new HashMap<>()); } catch (Exception ignored) {}
    }

    // ── Domain helpers ────────────────────────────────────────────────────────

    private boolean isCustomElytra(String id) {
        if (id == null) return false;
        if (CUSTOM_ELYTRA_IDS.contains(id)) return true;
        return id.contains("elytra") && !id.equals("minecraft:elytra");
    }

    private String getJavaId(Object itemStack) {
        for (String m : new String[]{"getJavaIdentifier", "getJavaId", "identifier"}) {
            try { Object r = invokeNoArgs(itemStack, m); if (r != null) return r.toString(); }
            catch (Exception ignored) {}
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
            try { Method m = clazz.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target); }
            catch (NoSuchMethodException ignored) {}
            for (Class<?> iface : clazz.getInterfaces()) {
                try { Method m = iface.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target); }
                catch (NoSuchMethodException ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private Object invokeTyped(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        if (target == null) return null;
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try { Method m = clazz.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
            catch (NoSuchMethodException ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private Object getFieldVal(Object target, String name) {
        if (target == null) return null;
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try { Field f = clazz.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
            catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
            catch (Exception e) { return null; }
        }
        return null;
    }

    private void setField(Object target, String name, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try { Field f = clazz.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; }
            catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
            catch (Exception e) { return; }
        }
    }
}
