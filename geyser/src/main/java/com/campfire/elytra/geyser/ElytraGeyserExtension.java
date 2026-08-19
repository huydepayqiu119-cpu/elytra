package com.campfire.elytra.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

/**
 * Khi ArmorStand (elytra mount) spawn trong session của Bedrock player:
 * - Hook vào entity cache của session
 * - Set SEAT_OFFSET + ROTATE_RIDER_DEGREES qua reflection để player nằm ngang
 *
 * Dùng polling vì ServerSpawnEntityEvent không có trong public API 2.10.
 */
public class ElytraGeyserExtension implements Extension {

    private final Map<UUID, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    // Cache entity data type constants (lazy-loaded via reflection)
    private volatile Object scaleType          = null;
    private volatile Object seatOffsetType     = null;
    private volatile Object rotationLockedType = null;
    private volatile Object rotateRiderType    = null;
    private volatile Object hitboxesType       = null;
    private volatile boolean dataTypesLoaded   = false;

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "campfire-elytra-mount");
            t.setDaemon(true);
            return t;
        });
        logger().info("[CampfireElytra] Geyser extension loaded.");
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        GeyserConnection conn = event.connection();
        UUID uuid = conn.playerUuid();
        // Poll mỗi 200ms để detect ArmorStand mới spawn
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
            () -> checkNewEntities(conn), 500, 200, TimeUnit.MILLISECONDS
        );
        tasks.put(uuid, task);
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        UUID uuid = event.connection().playerUuid();
        ScheduledFuture<?> t = tasks.remove(uuid);
        if (t != null) t.cancel(false);
    }

    // ── Entity detection & patching ───────────────────────────────────────────

    // Track entity IDs đã được patch rồi
    private final Set<Long> patched = ConcurrentHashMap.newKeySet();

    private void checkNewEntities(GeyserConnection conn) {
        try {
            Object entityCache = invokeNoArgs(conn, "getEntityCache");
            if (entityCache == null) return;

            // Lấy tất cả entity
            Collection<?> entities = getAllEntities(entityCache);
            if (entities == null) return;

            for (Object entity : entities) {
                if (entity == null) continue;
                long id = getGeyserId(entity);
                if (id <= 0 || patched.contains(id)) continue;

                // Check có phải ArmorStand không
                if (!isArmorStand(entity)) continue;

                // Check có passenger là player không (marker mount)
                if (!hasPlayerPassenger(entity, conn)) continue;

                patched.add(id);
                applyMountData(entity);
                logger().info("[CampfireElytra] Patched mount entity " + id);
            }
        } catch (Exception ignored) {}
    }

    private void applyMountData(Object entity) {
        try {
            loadDataTypes(entity);

            // Scale nhỏ để ẩn entity
            if (scaleType != null) overrideData(entity, scaleType, 0.001f);

            // Seat offset
            if (seatOffsetType != null) {
                Object vec = buildVector3f(0f, -0.8f, 0f);
                if (vec != null) overrideData(entity, seatOffsetType, vec);
            }

            // Lock rotation theo vehicle
            if (rotationLockedType != null) overrideData(entity, rotationLockedType, true);

            // Rotate rider 90 độ → nằm ngang
            if (rotateRiderType != null) overrideData(entity, rotateRiderType, 90f);

            // Xóa hitbox
            if (hitboxesType != null) overrideData(entity, hitboxesType, new ArrayList<>());

        } catch (Exception e) {
            logger().warning("[CampfireElytra] applyMountData failed: " + e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isArmorStand(Object entity) {
        try {
            String type = getEntityDefinitionString(entity);
            return type != null && type.contains("armor_stand");
        } catch (Exception ignored) {}
        return false;
    }

    private boolean hasPlayerPassenger(Object entity, GeyserConnection conn) {
        try {
            // Entity có passenger UUID khớp với connection player
            Object passengers = invokeNoArgs(entity, "getPassengers");
            if (passengers instanceof Collection<?> col) {
                for (Object p : col) {
                    if (p instanceof UUID uid && uid.equals(conn.playerUuid())) return true;
                }
            }
            // Fallback: check entity có passengers không
            Object pass = invokeNoArgs(entity, "passengers");
            if (pass instanceof Collection<?> col && !col.isEmpty()) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private String getEntityDefinitionString(Object entity) {
        for (String m : new String[]{"getDefinition", "definition", "getType", "entityType"}) {
            try {
                Object v = invokeNoArgs(entity, m);
                if (v != null) return v.toString().toLowerCase();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private long getGeyserId(Object entity) {
        for (String m : new String[]{"getGeyserId", "geyserId", "getRuntimeId"}) {
            try {
                Object v = invokeNoArgs(entity, m);
                if (v instanceof Number n) return n.longValue();
            } catch (Exception ignored) {}
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Collection<?> getAllEntities(Object entityCache) {
        for (String m : new String[]{"getEntities", "entities", "getAllEntities"}) {
            try {
                Object v = invokeNoArgs(entityCache, m);
                if (v instanceof Collection<?> c) return c;
                if (v instanceof Map<?,?> map) return map.values();
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Data type loading ─────────────────────────────────────────────────────

    private void loadDataTypes(Object entity) {
        if (dataTypesLoaded) return;
        try {
            Class<?> cls = Class.forName("org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes");
            scaleType          = cls.getField("SCALE").get(null);
            seatOffsetType     = cls.getField("SEAT_OFFSET").get(null);
            rotationLockedType = cls.getField("ROTATION_LOCKED_TO_VEHICLE").get(null);
            rotateRiderType    = cls.getField("ROTATE_RIDER_DEGREES").get(null);
            hitboxesType       = cls.getField("HITBOXES").get(null);
            dataTypesLoaded = true;
            logger().info("[CampfireElytra] GeyserEntityDataTypes loaded.");
        } catch (Exception e) {
            logger().warning("[CampfireElytra] Could not load GeyserEntityDataTypes: " + e.getMessage());
            dataTypesLoaded = true; // Không thử lại
        }
    }

    private void overrideData(Object entity, Object dataType, Object value) throws Exception {
        for (Method m : entity.getClass().getMethods()) {
            if (m.getName().equals("override") && m.getParameterCount() == 2) {
                m.invoke(entity, dataType, value);
                return;
            }
        }
    }

    private Object buildVector3f(float x, float y, float z) {
        try {
            Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector3f");
            return cls.getMethod("from", float.class, float.class, float.class).invoke(null, x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Reflection util ───────────────────────────────────────────────────────

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
}
