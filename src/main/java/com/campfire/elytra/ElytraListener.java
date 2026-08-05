package com.campfire.elytra;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.scheduler.BukkitTask;

import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ElytraListener implements Listener {

    private static final String ELYTRA_TAG_NAMESPACE = "campfire_custom";
    private static final String ELYTRA_TAG_KEY = "custom_elytra";

    private final ElytraPlugin plugin;

    // tracks players currently gliding with a custom elytra
    private final Map<UUID, BukkitTask> glideTaskMap = new ConcurrentHashMap<>();

    public ElytraListener(ElytraPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Check if the chestplate is a custom elytra.
    //
    // Two ways supported:
    // 1. PDC tag "campfire_custom:custom_elytra" (set by Java plugin/datapack)
    // 2. CustomModelData — any elytra with CMD > 0 counts as custom
    //    (matches anything registered in Geyser mappings with custom_model_data)
    //    You can narrow this by whitelisting specific CMD values in config.
    // -----------------------------------------------------------------------
    private boolean isCustomElytra(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        // Option 1: explicit PDC tag
        NamespacedKey key = new NamespacedKey(ELYTRA_TAG_NAMESPACE, ELYTRA_TAG_KEY);
        if (meta.getPersistentDataContainer().has(key, PersistentDataType.STRING)
                || meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            return true;
        }

        // Option 2: has CustomModelData (1.21.4+ component API)
        if (meta.hasCustomModelData()) {
            return true;
        }

        return false;
    }

    // -----------------------------------------------------------------------
    // When player starts/stops gliding
    // -----------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack chestplate = player.getInventory().getChestplate();
        if (!isCustomElytra(chestplate)) return;

        UUID uuid = player.getUniqueId();

        if (event.isGliding()) {
            startGlideAnimation(player, uuid);
        } else {
            stopGlideAnimation(uuid);
        }
    }

    // -----------------------------------------------------------------------
    // When player uses firework rocket while gliding — re-send animation
    // -----------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireworkBoost(PlayerItemConsumeEvent event) {
        // Paper fires PlayerItemConsumeEvent for firework boost usage while gliding
        Player player = event.getPlayer();
        if (!player.isGliding()) return;

        ItemStack chestplate = player.getInventory().getChestplate();
        if (!isCustomElytra(chestplate)) return;

        // Re-trigger animation after a short delay to sync with firework boost
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isGliding() && player.isOnline()) {
                sendGlidingAnimation(player);
            }
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopGlideAnimation(event.getPlayer().getUniqueId());
    }

    // -----------------------------------------------------------------------
    // Start periodic re-send of gliding flag to keep animation alive
    // Bedrock needs the IS_GLIDING entity flag set on the player entity.
    // Geyser normally sets this from Java metadata, but custom elytra
    // attachables don't get the wings-open pose automatically — we force it.
    // -----------------------------------------------------------------------
    private void startGlideAnimation(Player player, UUID uuid) {
        // Cancel any existing task first
        stopGlideAnimation(uuid);

        // Send immediately, then every 10 ticks to keep it alive
        sendGlidingAnimation(player);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!player.isOnline() || !player.isGliding()) {
                stopGlideAnimation(uuid);
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> sendGlidingAnimation(player));
        }, 10L, 10L);

        glideTaskMap.put(uuid, task);
    }

    private void stopGlideAnimation(UUID uuid) {
        BukkitTask task = glideTaskMap.remove(uuid);
        if (task != null) task.cancel();
    }

    // -----------------------------------------------------------------------
    // Core: get the GeyserConnection and send SetEntityDataPacket
    // forcing IS_GLIDING = true so the Bedrock client opens the wings
    //
    // Geyser public API only exposes GeyserConnection, but we need
    // GeyserSession (internal) to call sendUpstreamPacket.
    // We use reflection as a thin bridge — this is the only viable path
    // without modifying Geyser source.
    // -----------------------------------------------------------------------
    private void sendGlidingAnimation(Player player) {
        GeyserApi api = GeyserApi.api();
        if (api == null) return;

        GeyserConnection connection = api.connectionByUuid(player.getUniqueId());
        if (connection == null) return; // not a Bedrock player

        try {
            // GeyserConnection is implemented by GeyserSession internally
            // We reflect into it to call sendUpstreamPacket with SetEntityDataPacket
            Class<?> sessionClass = connection.getClass();

            // Get the player entity object from session
            java.lang.reflect.Method getPlayerEntityMethod = findMethod(sessionClass, "getPlayerEntity");
            if (getPlayerEntityMethod == null) return;
            Object playerEntity = getPlayerEntityMethod.invoke(connection);
            if (playerEntity == null) return;

            // Get entity ID (Bedrock runtime ID)
            java.lang.reflect.Method getRuntimeIdMethod = findMethod(playerEntity.getClass(), "getGeyserId");
            if (getRuntimeIdMethod == null) return;
            long runtimeId = (long) getRuntimeIdMethod.invoke(playerEntity);

            // Get entity flags / metadata from entity
            java.lang.reflect.Method getDirtyMetadataMethod = findMethod(playerEntity.getClass(), "getDirtyMetadata");
            if (getDirtyMetadataMethod == null) return;
            Object dirtyMeta = getDirtyMetadataMethod.invoke(playerEntity);

            // EntityFlags — set IS_GLIDING flag true
            java.lang.reflect.Method getFlagsMethod = findMethod(playerEntity.getClass(), "getFlags");
            if (getFlagsMethod == null) return;
            Object flags = getFlagsMethod.invoke(playerEntity);

            // EntityFlag.IS_GLIDING
            Class<?> entityFlagClass = Class.forName("org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag");
            Object isGlidingFlag = java.lang.reflect.Array.get(
                entityFlagClass.getMethod("values").invoke(null), 0
            );
            // Find IS_GLIDING by name
            for (Object f : (Object[]) entityFlagClass.getMethod("values").invoke(null)) {
                if (f.toString().equals("IS_GLIDING")) {
                    isGlidingFlag = f;
                    break;
                }
            }

            // Set the flag on the flags object
            java.lang.reflect.Method setFlagMethod = findMethod(flags.getClass(), "setFlag",
                entityFlagClass, boolean.class);
            if (setFlagMethod != null) {
                setFlagMethod.invoke(flags, isGlidingFlag, true);
            }

            // Build SetEntityDataPacket
            Class<?> packetClass = Class.forName("org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket");
            Object packet = packetClass.getDeclaredConstructor().newInstance();

            packetClass.getMethod("setRuntimeEntityId", long.class).invoke(packet, runtimeId);

            // Set metadata from dirty meta
            java.lang.reflect.Method setMetadataMethod = findMethod(packetClass, "setMetadata");
            if (setMetadataMethod != null) setMetadataMethod.invoke(packet, dirtyMeta);

            packetClass.getMethod("setTick", long.class).invoke(packet, 0L);

            // sendUpstreamPacket via session
            java.lang.reflect.Method sendMethod = findMethod(sessionClass, "sendUpstreamPacket");
            if (sendMethod != null) sendMethod.invoke(connection, packet);

        } catch (Exception ignored) {
            // silently fail — animation just won't play, no crash
        }
    }

    // -----------------------------------------------------------------------
    // Helper: find method by name ignoring param types (first match)
    // -----------------------------------------------------------------------
    private java.lang.reflect.Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> current = clazz;
        while (current != null) {
            for (java.lang.reflect.Method m : current.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (params.length == 0 || parametersMatch(m, params)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private boolean parametersMatch(java.lang.reflect.Method m, Class<?>[] params) {
        Class<?>[] types = m.getParameterTypes();
        if (types.length != params.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (!types[i].isAssignableFrom(params[i])) return false;
        }
        return true;
    }
}
