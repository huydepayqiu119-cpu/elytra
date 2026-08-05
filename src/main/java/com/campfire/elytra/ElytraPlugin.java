package com.campfire.elytra;

import org.bukkit.plugin.java.JavaPlugin;

public class ElytraPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("Geyser-Spigot") == null) {
            getLogger().severe("Geyser not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new ElytraListener(this), this);
        getLogger().info("ElytraPlugin enabled.");
    }
}
