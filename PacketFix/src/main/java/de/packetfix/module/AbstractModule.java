package de.packetfix.module;

import de.packetfix.PacketFixPlugin;

public abstract class AbstractModule {

    protected final PacketFixPlugin plugin;
    private long fixCount = 0;

    public AbstractModule(PacketFixPlugin plugin) {
        this.plugin = plugin;
    }

    /** Config key under which enabled: true/false is stored. */
    public abstract String getConfigKey();

    /** Human-readable name shown in /packetfix status */
    public abstract String getName();

    /** Called when the module should register its listeners / handlers. */
    public abstract void enable();

    /** Called when the module should clean up. */
    public abstract void disable();

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(getConfigKey() + ".enabled", true);
    }

    protected void countFix() { fixCount++; }
    protected void countFix(int n) { fixCount += n; }

    public long getFixCount() { return fixCount; }
}
