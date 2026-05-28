package de.packetfix.module;

import de.packetfix.PacketFixPlugin;
import de.packetfix.module.impl.*;

import java.util.ArrayList;
import java.util.List;

public class ModuleManager {

    private final PacketFixPlugin plugin;
    private final List<AbstractModule> modules = new ArrayList<>();
    private final List<AbstractModule> active   = new ArrayList<>();

    public ModuleManager(PacketFixPlugin plugin) {
        this.plugin = plugin;
    }

    public void enableAll() {
        modules.clear();
        active.clear();

        modules.add(new PacketFixerModule(plugin));
        modules.add(new ShieldFixModule(plugin));
        modules.add(new GhostBlockModule(plugin));
        modules.add(new GhostHitFixModule(plugin));
        modules.add(new ChunkBanFixModule(plugin));
        modules.add(new XpMergeModule(plugin));
        modules.add(new PingLimiterModule(plugin));
        modules.add(new ExploitFixModule(plugin));

        for (AbstractModule m : modules) {
            if (m.isEnabled()) {
                m.enable();
                active.add(m);
                plugin.log("Module enabled: " + m.getName());
            }
        }
    }

    public void disableAll() {
        for (AbstractModule m : active) {
            m.disable();
        }
        active.clear();
        modules.clear();
    }

    public List<AbstractModule> getActive() { return active; }
    public int getActiveCount()             { return active.size(); }
}
