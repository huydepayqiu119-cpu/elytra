package com.campfire.elytra;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ElytraExtension implements Extension {

    private final Map<UUID, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        scheduler = Executors.newScheduledThreadPool(2);
        this.logger().info("CampfireElytra enabled.");
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        GeyserConnection connection = event.connection();
        UUID uuid = connection.playerUuid();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try { tickGlide(connection); } catch (Exception ignored) {}
        }, 500, 500, TimeUnit.MILLISECONDS);
        tasks.put(uuid, task);
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        ScheduledFuture<?> task = tasks.remove(event.connection().playerUuid());
        if (task != null) task.cancel(false);
    }

    private void tickGlide(GeyserConnection connection) throws Exception {
        Object session = connection;
        Object playerEntity = invokeMethod(session, "getPlayerEntity");
        if (playerEntity == null) return;

        Object flags = invokeMethod(playerEntity, "getFlags");
        if (flags == null) return;

        Class<?> entityFlagClass = Class.forName("org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag");
        Object isGlidingFlag = getEnumConstant(entityFlagClass, "IS_GLIDING");
        if (isGlidingFlag == null) return;

        boolean isGliding = (boolean) invokeMethod(flags, "getFlag", entityFlagClass, isGlidingFlag);
        if (!isGliding) return;

        if (!isWearingElytra(session)) return;

        long runtimeId = (long) invokeMethod(playerEntity, "getGeyserId");
        Object dirtyMetadata = invokeMethod(playerEntity, "getDirtyMetadata");
        if (dirtyMetadata == null) return;

        invokeMethod(flags, "setFlag", entityFlagClass, boolean.class, isGlidingFlag, true);

        Class<?> packetClass = Class.forName("org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket");
        Object packet = packetClass.getDeclaredConstructor().newInstance();
        setField(packet, "runtimeEntityId", runtimeId);
        setField(packet, "metadata", dirtyMetadata);
        setField(packet, "tick", 0L);

        invokeMethod(session, "sendUpstreamPacket",
            Class.forName("org.cloudburstmc.protocol.bedrock.packet.BedrockPacket"), packet);
    }

    /**
     * Check if the player is wearing any item that behaves as an elytra.
     * This covers vanilla minecraft:elytra AND any custom item mapped to elytra via Geyser mappings,
     * by checking all available identifiers on both the item and its mapping.
     */
    private boolean isWearingElytra(Object session) {
        try {
            Object inventory = invokeMethod(session, "getPlayerInventory");
            if (inventory == null) return false;
            Object chestplate = invokeMethod(inventory, "getChestplate");
            if (chestplate == null) return false;

            // Try every identifier-like method on the item itself
            if (containsElytra(chestplate)) return true;

            // Try via Geyser ItemMapping (custom items registered via mappings)
            Object mapping = getMapping(chestplate, session);
            if (mapping != null && containsElytra(mapping)) return true;

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Try all known identifier getter method names on an object and check if any contains "elytra".
     */
    private boolean containsElytra(Object obj) {
        String[] getters = {
            "getJavaIdentifier",
            "getBedrockIdentifier",
            "getJavaId",
            "getBedrockId",
            "identifier",
            "getId"
        };
        for (String getter : getters) {
            try {
                Object result = invokeMethod(obj, getter);
                if (result != null && result.toString().contains("elytra")) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Attempt to get the ItemMapping from a GeyserItemStack using the session as context.
     */
    private Object getMapping(Object chestplate, Object session) {
        try {
            // Try with session parameter (some Geyser versions)
            Object mapping = invokeMethod(chestplate, "getMapping", session.getClass(), session);
            if (mapping != null) return mapping;
        } catch (Exception ignored) {}
        try {
            // Try without parameter
            return invokeMethod(chestplate, "getMapping");
        } catch (Exception ignored) {}
        return null;
    }

    private Object invokeMethod(Object target, String name, Object... args) throws Exception {
        if (args.length >= 2 && args[0] instanceof Class<?> paramType) {
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return invokeTyped(target, name, new Class[]{paramType}, rest);
        }
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name) && (args.length == 0 || m.getParameterCount() == args.length)) {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private Object invokeTyped(Object target, String name, Class<?>[] types, Object... args) throws Exception {
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

    private void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                var f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                break;
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
