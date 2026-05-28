package de.packetfix;

import com.github.retrooper.packetevents.PacketEvents;
import de.packetfix.commands.PacketFixCommand;
import de.packetfix.module.ModuleManager;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

public class PacketFixPlugin extends JavaPlugin {

    public static final String PREFIX =
            ChatColor.DARK_AQUA + "[" + ChatColor.AQUA + "PacketFix" + ChatColor.DARK_AQUA + "] " + ChatColor.RESET;

    private static PacketFixPlugin instance;
    private ModuleManager moduleManager;

    // -----------------------------------------------------------------------
    //  PacketEvents MUST be loaded before onEnable
    // -----------------------------------------------------------------------
    @Override
    public void onLoad() {
        instance = this;
        // Register our plugin with PacketEvents so it injects into the Netty pipeline
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Finalise PacketEvents initialisation
        PacketEvents.getAPI().init();

        moduleManager = new ModuleManager(this);
        moduleManager.enableAll();

        getCommand("packetfix").setExecutor(new PacketFixCommand(this));

        getLogger().info("PacketFix v" + getDescription().getVersion()
                + " started — " + moduleManager.getActiveCount() + " modules active.");
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) moduleManager.disableAll();
        PacketEvents.getAPI().terminate();
        getLogger().info("PacketFix disabled.");
    }

    // -----------------------------------------------------------------------

    public void reload() {
        reloadConfig();
        moduleManager.disableAll();
        moduleManager.enableAll();
    }

    public void log(String msg) {
        if (getConfig().getBoolean("logging.log-fixes", true)) getLogger().info(msg);
    }

    public void debug(String msg) {
        if (getConfig().getBoolean("logging.debug", false)) getLogger().info("[DEBUG] " + msg);
    }

    public ModuleManager getModuleManager() { return moduleManager; }
    public static PacketFixPlugin get() { return instance; }
}
