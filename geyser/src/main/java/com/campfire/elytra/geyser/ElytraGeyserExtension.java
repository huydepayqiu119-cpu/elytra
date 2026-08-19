package com.campfire.elytra.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.entity.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.entity.EntityDefinition;
import org.geysermc.geyser.api.entity.EntityData;
import org.geysermc.geyser.api.entity.GeyserEntityDataTypes;
import org.geysermc.geyser.api.util.Identifier;
import org.cloudburstmc.math.vector.Vector3f;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ElytraGeyserExtension
 *
 * Khi Spigot spawn ArmorStand (mount) cho player gliding:
 *  - Geyser intercept ServerSpawnEntityEvent
 *  - Check entity có marker + invisible + passenger là Bedrock player
 *  - Set SEAT_OFFSET để player nằm xuống (y offset âm + rotation)
 *  - Set ROTATION_LOCKED_TO_VEHICLE = true
 */
public class ElytraGeyserExtension implements Extension {

    // Set các UUID của entity đang là elytra mount (Bedrock entity id)
    private final Set<Long> elytraMounts = ConcurrentHashMap.newKeySet();

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        logger().info("[CampfireElytra] Geyser extension loaded.");
    }

    @Subscribe
    public void onSpawnEntity(ServerSpawnEntityEvent event) {
        try {
            Object entity = event.entity();
            if (entity == null) return;

            // Check có phải ArmorStand không
            String entityType = getEntityType(entity);
            if (entityType == null || !entityType.contains("armor_stand")) return;

            // Check có phải marker (invisible, no gravity) không
            if (!isMarkerArmorStand(entity)) return;

            // Set seat offset để player nằm ngang khi cưỡi
            // Y offset âm → player thấp hơn
            // Rotation locked → player quay theo entity
            setEntityData(entity, "SEAT_OFFSET", buildVector3f(0f, -1.2f, 0f));
            setEntityData(entity, "ROTATION_LOCKED_TO_VEHICLE", true);
            setEntityData(entity, "SEAT_HAS_ROTATION", true);
            // Pitch player nằm xuống ~90 độ
            setEntityData(entity, "ROTATE_RIDER_DEGREES", 90f);

            // Ẩn entity hoàn toàn phía Bedrock (scale 0)
            setEntityData(entity, "SCALE", 0.001f);

            long geyserId = getGeyserId(entity);
            if (geyserId > 0) elytraMounts.add(geyserId);

            logger().info("[CampfireElytra] Intercepted elytra mount entity");
        } catch (Exception e) {
            logger().warning("[CampfireElytra] onSpawnEntity error: " + e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getEntityType(Object entity) {
        try {
            for (String m : new String[]{"getType", "getDefinition"}) {
                try {
                    Object type = invokeNoArgs(entity, m);
                    if (type != null) return type.toString().toLowerCase();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isMarkerArmorStand(Object entity) {
        try {
            // ArmorStand marker = isMarker() / small + no hitbox
            for (String m : new String[]{"isMarker", "isSmall"}) {
                try {
                    Object v = invokeNoArgs(entity, m);
                    if (v instanceof Boolean b && b) return true;
                } catch (Exception ignored) {}
            }
            // Check metadata flags
            Object flags = invokeNoArgs(entity, "getArmorStandFlags");
            if (flags instanceof Byte b && (b & 0x10) != 0) return true; // marker bit
        } catch (Exception ignored) {}
        return false;
    }

    private void setEntityData(Object entity, String typeName, Object value) {
        try {
            // Tìm GeyserEntityDataTypes field
            Class<?> dataTypesClass = Class.forName(
                "org.geysermc.geyser.api.entity.GeyserEntityDataTypes");
            Object dataType = dataTypesClass.getField(typeName).get(null);
            if (dataType == null) return;

            // entity.override(type, value)
            for (Method m : entity.getClass().getMethods()) {
                if (m.getName().equals("override") && m.getParameterCount() == 2) {
                    m.invoke(entity, dataType, value);
                    return;
                }
            }
        } catch (Exception e) {
            logger().warning("[CampfireElytra] setEntityData " + typeName + " failed: " + e.getMessage());
        }
    }

    private long getGeyserId(Object entity) {
        try {
            for (String m : new String[]{"getGeyserId", "geyserId", "getRuntimeId"}) {
                try {
                    Object v = invokeNoArgs(entity, m);
                    if (v instanceof Number n) return n.longValue();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private Object buildVector3f(float x, float y, float z) throws Exception {
        Class<?> cls = Class.forName("org.cloudburstmc.math.vector.Vector3f");
        return cls.getMethod("from", float.class, float.class, float.class).invoke(null, x, y, z);
    }

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
