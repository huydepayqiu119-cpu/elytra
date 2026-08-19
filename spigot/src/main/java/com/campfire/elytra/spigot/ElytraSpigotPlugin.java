package com.campfire.elytra.spigot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ElytraSpigotPlugin extends JavaPlugin implements Listener {

    // player UUID → mount entity UUID
    private final Map<UUID, UUID> mounts = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CampfireElytraSpigot enabled.");
    }

    @Override
    public void onDisable() {
        // Cleanup all mounts
        for (UUID mountId : mounts.values()) {
            Entity e = Bukkit.getEntity(mountId);
            if (e != null) e.remove();
        }
        mounts.clear();
    }

    @EventHandler
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!isWearingCustomElytra(player)) return;

        if (event.isGliding()) {
            spawnMount(player);
        } else {
            removeMount(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeMount(event.getPlayer());
    }

    private boolean isWearingCustomElytra(Player player) {
        var chest = player.getInventory().getChestplate();
        if (chest == null) return false;
        // Kiểm tra item_model component hoặc custom model data
        var meta = chest.getItemMeta();
        if (meta == null) return false;
        // Nexo item có custom model data hoặc item model
        try {
            var itemModel = meta.getClass().getMethod("getItemModel").invoke(meta);
            if (itemModel != null) {
                String id = itemModel.toString();
                if (!id.equals("minecraft:elytra") && id.contains("elytra")) return true;
            }
        } catch (Exception ignored) {}
        return meta.hasCustomModelData() && chest.getType().name().equals("ELYTRA");
    }

    private void spawnMount(Player player) {
        if (mounts.containsKey(player.getUniqueId())) return;

        Location loc = player.getLocation();
        ArmorStand stand = player.getWorld().spawn(loc, ArmorStand.class, e -> {
            e.setVisible(false);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSmall(true);
            e.setMarker(true);
            e.setPersistent(false);
        });

        mounts.put(player.getUniqueId(), stand.getUniqueId());
        stand.addPassenger(player);
        getLogger().info("Spawned mount for " + player.getName());
    }

    private void removeMount(Player player) {
        UUID mountId = mounts.remove(player.getUniqueId());
        if (mountId == null) return;
        Entity mount = Bukkit.getEntity(mountId);
        if (mount != null) {
            mount.eject();
            mount.remove();
        }
        getLogger().info("Removed mount for " + player.getName());
    }
}
