package dev.aegistitan;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class AegisTitan extends JavaPlugin {

    private Terrain terrain;
    private TitanTrident trident;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        updateConfig();

        Items items = new Items(this);
        terrain = new Terrain(this);
        WallManager walls = new WallManager(this, items);
        TitanAxe axe = new TitanAxe(this, items, walls, terrain);
        trident = new TitanTrident(this, items, walls, terrain);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(walls, this);
        pm.registerEvents(terrain, this);
        pm.registerEvents(axe, this);
        pm.registerEvents(trident, this);
        walls.start();

        Commands commands = new Commands(this, items, walls, axe, trident);
        for (String name : List.of("getshield", "shieldsize", "getaxe", "axesize", "axecooldown", "gettrident", "tridentsize", "tridentcooldown", "aegistitan")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }
        getLogger().info("AegisTitan ready: /getshield, /shieldsize, /getaxe, /axesize, /axecooldown, /gettrident, /tridentsize, /tridentcooldown");
    }

    /**
     * Adds any new settings to an existing config.yml and moves old, laggy limits down to the new safer ones,
     * so nobody has to delete their config after an update.
     */
    private void updateConfig() {
        getConfig().options().copyDefaults(true);
        int version = getConfig().getInt("config-version", 1);
        if (version < 3) {
            getConfig().set("axe.blocks-per-tick", Math.min(getConfig().getInt("axe.blocks-per-tick", 2500), 2500));
            getConfig().set("trident.max-size", Math.min(getConfig().getDouble("trident.max-size", 30), 30));
            getConfig().set("trident.hole-max-radius", Math.min(getConfig().getDouble("trident.hole-max-radius", 8), 8));
            getConfig().set("trident.hole-max-depth", Math.min(getConfig().getDouble("trident.hole-max-depth", 70), 70));
            getConfig().set("trident.max-shockwave-radius", Math.min(getConfig().getDouble("trident.max-shockwave-radius", 90), 90));
            getConfig().set("config-version", 3);
        }
        saveConfig();
    }

    @Override
    public void onDisable() {
        if (trident != null) {
            trident.shutdown();
        }
        if (terrain != null) {
            terrain.restoreAll();
        }
    }
}
