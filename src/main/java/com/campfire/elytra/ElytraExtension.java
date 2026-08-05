package com.campfire.elytra;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
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

    // session uuid -> glide task
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
        UUID uuid = connection.uuid();

        // Poll every 500ms — check if player is gliding, if yes force the animation
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                tickGlide(connection);
            } catch (Exception ignored) {}
        }, 500, 500, TimeUnit.MILLISECONDS);

        tasks.put(uuid, task);
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        ScheduledFuture<?> task = tasks.remove(event.connection().uuid());
        if (task != null) task.cancel(false);
    }

    // ------------------------------------------------------------------
    // Core logic — reflect into GeyserSession to:
    // 1. Get the player entity
    // 2. Check if IS_GLIDING flag is set (Java server already told Geyser)
    // 3. Re-send SetEntityDataPacket with IS_GLIDING=true so Bedrock client
    //    opens the wings on custom elytra attachable
    // ------------------------------------------------------------------
    private void tickGlide(GeyserConnection connection) throws Exception {
        // GeyserConnection is GeyserSession at runtime
        Object session = connection;

        // getPlayerEntity()
        Object playerEntity = invokeMethod(session, "getPlayerEntity");
        if (playerEntity == null) return;

        // getFlags() -> EntityFlags
        Object flags = invokeMethod(playerEntity, "getFlags");
        if (flags == null) return;

        // Check IS_GLIDING flag current value
        Class<?> entityFlagClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag");
        Object isGlidingFlag = getEnumConstant(entityFlagClass, "IS_GLIDING");
        if (isGlidingFlag == null) return;

        // getFlag(EntityFlag) -> boolean
        boolean isGliding = (boolean) invokeMethod(flags, "getFlag", entityFlagClass, isGlidingFlag);
        if (!isGliding) return;

        // Player is gliding — build and send SetEntityDataPacket
        // getGeyserId() -> long  (Bedrock runtime entity ID)
        long runtimeId = (long) invokeMethod(playerEntity, "getGeyserId");

        // getDirtyMetadata() — entity metadata container to send
        Object dirtyMetadata = invokeMethod(playerEntity, "getDirtyMetadata");
        if (dirtyMetadata == null) return;

        // Ensure IS_GLIDING is set true in the dirty metadata
        invokeMethod(flags, "setFlag", entityFlagClass, boolean.class,
            isGlidingFlag, true);

        // Build SetEntityDataPacket
        Class<?> packetClass = Class.forName(
            "org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket");
        Object packet = packetClass.getDeclaredConstructor().newInstance();

        setField(packet, "runtimeEntityId", runtimeId);
        setField(packet, "metadata", dirtyMetadata);
        setField(packet, "tick", 0L);

        // sendUpstreamPacket(BedrockPacket)
        invokeMethod(session, "sendUpstreamPacket",
            Class.forName("org.cloudburstmc.protocol.bedrock.packet.BedrockPacket"),
            packet);
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    /** Invoke first method matching name, optionally with typed args */
    private Object invokeMethod(Object target, String name, Object... args) throws Exception {
        Class<?> paramType = null;
        if (args.length == 2 && args[0] instanceof Class) {
            // typed variant: (Class<?> paramType, Object value)
            paramType = (Class<?>) args[0];
            Object value = args[1];
            return invokeTyped(target, name, new Class[]{paramType}, value);
        }
        if (args.length == 3 && args[0] instanceof Class && args[1] instanceof Class) {
            // typed variant: (Class<?> p1, Class<?> p2, Object v1, Object v2) — not used here
        }
        // no-arg or find by name
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
