package de.packetfix.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Runtime platform detection and cross-version compatibility helpers.
 *
 * Supports: Bukkit · Spigot · Paper · Purpur · Folia  ·  1.16.x – 1.21.x
 */
public final class PlatformUtil {

    // ── Platform flags ───────────────────────────────────────────────────
    public static final boolean IS_FOLIA;
    public static final boolean IS_PAPER;     // Paper or any fork thereof
    public static final boolean IS_PURPUR;
    public static final boolean HAS_ADVENTURE; // Paper 1.16.5+ Adventure API

    // ── Version info ─────────────────────────────────────────────────────
    public static final int MINOR;  // e.g. 20 for 1.20.x
    public static final int PATCH;  // e.g. 4  for 1.20.4

    // ── Cached reflection handles ─────────────────────────────────────────
    private static final Method GET_PING_METHOD;   // Player#getPing (Paper 1.17+)
    private static final Method KICK_COMPONENT;    // Player#kick(Component) (Paper 1.16.5+)
    private static final Method KICK_STRING;       // Player#kickPlayer(String) (all versions)

    static {
        // Folia
        boolean folia = false;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); folia = true; }
        catch (ClassNotFoundException ignored) {}
        IS_FOLIA = folia;

        // Purpur
        boolean purpur = false;
        try { Class.forName("org.purpurmc.purpur.PurpurConfig"); purpur = true; }
        catch (ClassNotFoundException ignored) {}
        IS_PURPUR = purpur;

        // Paper (includes Purpur and Folia)
        boolean paper = false;
        try { Class.forName("com.destroystokyo.paper.PaperConfig"); paper = true; }
        catch (ClassNotFoundException ignored) {}
        if (!paper) {
            try { Class.forName("io.papermc.paper.configuration.Configuration"); paper = true; }
            catch (ClassNotFoundException ignored) {}
        }
        IS_PAPER = paper || purpur || folia;

        // Adventure (Paper 1.16.5+)
        boolean adventure = false;
        try { Class.forName("net.kyori.adventure.text.Component"); adventure = true; }
        catch (ClassNotFoundException ignored) {}
        HAS_ADVENTURE = adventure;

        // Parse server version
        String v = Bukkit.getBukkitVersion(); // e.g. "1.20.4-R0.1-SNAPSHOT"
        int minor = 20, patch = 0;
        try {
            String[] parts = v.split("-")[0].split("\\.");
            minor = Integer.parseInt(parts[1]);
            if (parts.length > 2) patch = Integer.parseInt(parts[2]);
        } catch (Exception ignored) {}
        MINOR = minor;
        PATCH = patch;

        // Reflection: Player#getPing (Paper 1.17+)
        Method getPing = null;
        try {
            getPing = Player.class.getMethod("getPing");
        } catch (NoSuchMethodException ignored) {}
        GET_PING_METHOD = getPing;

        // Reflection: Player#kick(Component) (Paper)
        Method kickComp = null;
        if (HAS_ADVENTURE) {
            try {
                Class<?> comp = Class.forName("net.kyori.adventure.text.Component");
                kickComp = Player.class.getMethod("kick", comp);
            } catch (Exception ignored) {}
        }
        KICK_COMPONENT = kickComp;

        // Reflection: Player#kickPlayer(String) (all versions)
        Method kickStr = null;
        try { kickStr = Player.class.getMethod("kickPlayer", String.class); }
        catch (NoSuchMethodException ignored) {}
        KICK_STRING = kickStr;
    }

    private PlatformUtil() {}

    // ── API ───────────────────────────────────────────────────────────────

    /**
     * Get a player's ping in milliseconds.
     * Uses Player#getPing() on Paper 1.17+, falls back to PacketEvents on
     * older builds, falls back to 0 if neither is available.
     */
    public static int getPing(Player player) {
        if (GET_PING_METHOD != null) {
            try {
                return (int) GET_PING_METHOD.invoke(player);
            } catch (Exception ignored) {}
        }
        // Fallback: try CraftPlayer reflection (works on all Bukkit/Spigot builds)
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            // Field name varies by version: "latency" (old) or "e" (obfuscated)
            for (String name : new String[]{"latency", "e", "ping"}) {
                try {
                    java.lang.reflect.Field f = handle.getClass().getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(handle);
                    if (val instanceof Integer) return (Integer) val;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Kick a player with a message, compatible across all platforms.
     * Uses Adventure Component on Paper; legacy kickPlayer(String) elsewhere.
     */
    public static void kickPlayer(Player player, String message) {
        // Paper: prefer kick(Component)
        if (KICK_COMPONENT != null && HAS_ADVENTURE) {
            try {
                Class<?> legacySerializer = Class.forName(
                        "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
                Object serializer = legacySerializer.getMethod("legacyAmpersand").invoke(null);
                Object component  = legacySerializer.getMethod("deserialize", String.class)
                        .invoke(serializer, message);
                KICK_COMPONENT.invoke(player, component);
                return;
            } catch (Exception ignored) {}
        }
        // All versions fallback
        if (KICK_STRING != null) {
            try {
                // Strip & colour codes to plain text for legacy kick
                KICK_STRING.invoke(player, message.replaceAll("&[0-9a-fk-or]", "")
                        .replaceAll("§[0-9a-fk-or]", ""));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Returns a formatted version string, e.g. "Paper 1.20.4".
     */
    public static String describe() {
        String flavor = IS_FOLIA ? "Folia" : IS_PURPUR ? "Purpur" : IS_PAPER ? "Paper" : "Spigot/Bukkit";
        return flavor + " 1." + MINOR + (PATCH > 0 ? "." + PATCH : "");
    }
}
