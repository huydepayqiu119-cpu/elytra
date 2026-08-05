package com.campfire.elytra;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

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

    private boolean isWearingElytra(Object session) {
        try {
            Object inventory = invokeMethod(session, "getPlayerInventory");
            if (inventory == null) return false;
            Object chestplate = invokeMethod(inventory, "getChestplate");
            if (chestplate == null) return false;
            Object mapping = invokeMethod(chestplate, "getMapping", session.getClass(), session);
            if (mapping == null) {
                Object identifier = invokeMethod(chestplate, "getJavaIdentifier");
                return identifier != null && identifier.toString().contains("elytra");
            }
            Object identifier = invokeMethod(mapping, "getJavaIdentifier");
            return identifier != null && identifier.toString().contains("elytra");
        } catch (Exception e) {
            return false;
        }
    }

    private Object invokeMethod(Object target, String name, Object... args) throws Exception {
        if (args.length >= 2 && args[0] instanceof Class<?> paramType) {
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return invokeTyped(target, name, new Class[]{paramType}, rest);
        }
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            for (var m : clazz.getDeclaredMethods()) {
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
                var m = clazz.getDeclaredMethod(name, types);
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
