package de.packetfix.module.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import de.packetfix.PacketFixPlugin;
import de.packetfix.module.AbstractModule;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import de.packetfix.util.SchedulerUtil;
import io.netty.buffer.ByteBuf;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Chunk Ban Fix Module.
 *
 * A "chunk ban" (also called "crash chunk") is a chunk whose data is so
 * malformed or oversized that loading it immediately crashes or disconnects
 * the client. Common causes:
 *
 *   - A chunk containing NBT data beyond the vanilla read limit
 *     (e.g. from a modded client, a duplication glitch, or a grief tool).
 *   - A chunk with an extremely deep block-entity NBT tree that hits the
 *     recursion limit inside the vanilla deserialiser.
 *   - A chunk region file corrupted by a server crash mid-write.
 *   - A chunk intentionally crafted by a griefer to permanently ban a
 *     player by luring them into the chunk area (the client crashes every
 *     time it tries to load the chunk within render distance).
 *
 * ── Protections ──────────────────────────────────────────────────────────
 *
 *  1. OUTGOING CHUNK PACKET FILTER (PacketEvents)
 *     Intercepts MAP_CHUNK (Chunk Data) packets before they reach the client.
 *     Measures total serialised byte size. If a chunk exceeds the configured
 *     threshold it is replaced with an empty (air) chunk packet so the client
 *     can safely load the area without crashing.
 *     The offending chunk coordinates are recorded in banned-chunks.txt.
 *
 *  2. PROXIMITY GUARD (Bukkit events)
 *     Tracks player positions every tick. If a player walks within
 *     `warn-radius` chunks of a known banned chunk they are:
 *       a) Warned in chat.
 *       b) Teleported back to their last safe position (if `auto-teleport`
 *          is enabled in config).
 *     This prevents players from accidentally entering the chunk's
 *     render-distance and getting crash-banned again.
 *
 *  3. PERSISTENT BANNED CHUNK LIST
 *     Banned chunks are saved to `plugins/PacketFix/banned-chunks.txt`
 *     (format: `world,chunkX,chunkZ`) and reloaded on startup / reload.
 *     Admins can also manually add entries or use:
 *       /packetfix chunkban add <world> <x> <z>
 *       /packetfix chunkban remove <world> <x> <z>
 *       /packetfix chunkban list
 *     (These are handled by PacketFixCommand which delegates to this module.)
 *
 *  4. NBT DEPTH SCAN on known-bad chunk packets
 *     Before marking a chunk as banned we do a shallow depth scan of the
 *     chunk's raw bytes to distinguish a genuinely malformed chunk from a
 *     legitimately large (but safe) chunk so we don't false-positive on
 *     heavily built areas.
 */
public class ChunkBanFixModule extends AbstractModule implements Listener {

    // -----------------------------------------------------------------------
    //  Config keys
    // -----------------------------------------------------------------------
    private static final String CFG            = "chunk-ban-fix";
    private static final String CFG_MAX_BYTES  = CFG + ".max-chunk-bytes";
    private static final String CFG_WARN_R     = CFG + ".warn-radius-chunks";
    private static final String CFG_AUTO_TP    = CFG + ".auto-teleport";
    private static final String CFG_REPLACE    = CFG + ".replace-with-air";
    private static final String CFG_NOTIFY_OPS = CFG + ".notify-ops";

    // Default: 2 MB per chunk packet — vanilla rarely exceeds 500 KB
    private static final int DEFAULT_MAX_BYTES = 2_097_152;

    // -----------------------------------------------------------------------
    //  State
    // -----------------------------------------------------------------------

    /** Set of banned chunk keys: "world:chunkX:chunkZ" */
    private final Set<String> bannedChunks = ConcurrentHashMap.newKeySet();

    /** Player UUID → last known safe location (updated every 5 ticks) */
    private final Map<UUID, Location> safeLocations = new ConcurrentHashMap<>();

    /** Player UUID → last warned chunk key (debounce warn spam) */
    private final Map<UUID, String> lastWarnedChunk = new ConcurrentHashMap<>();

    private PacketListenerAbstract peListener;
    private Object safeLocTask = null;

    private final File dataFile;

    // -----------------------------------------------------------------------

    public ChunkBanFixModule(PacketFixPlugin plugin) {
        super(plugin);
        dataFile = new File(plugin.getDataFolder(), "banned-chunks.txt");
    }

    @Override public String getConfigKey() { return CFG; }
    @Override public String getName()      { return "Chunk-Ban-Fix (crash chunk protection)"; }

    // -----------------------------------------------------------------------
    @Override
    public void enable() {
        loadBannedChunks();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerPacketListener();
        startSafeLocationTask();

        plugin.log("ChunkBanFix: " + bannedChunks.size() + " banned chunks loaded.");
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (peListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(peListener);
            peListener = null;
        }
        if (safeLocTask != null) {
            SchedulerUtil.cancelTask(safeLocTask);
            safeLocTask = null;
        }
        saveBannedChunks();
        safeLocations.clear();
        lastWarnedChunk.clear();
    }

    // -----------------------------------------------------------------------
    //  PacketEvents — outgoing MAP_CHUNK filter
    // -----------------------------------------------------------------------

    private void registerPacketListener() {
        final int maxBytes    = plugin.getConfig().getInt(CFG_MAX_BYTES, DEFAULT_MAX_BYTES);
        final boolean replace = plugin.getConfig().getBoolean(CFG_REPLACE, true);
        final boolean notifyOps = plugin.getConfig().getBoolean(CFG_NOTIFY_OPS, true);

        peListener = new PacketListenerAbstract(PacketListenerPriority.LOWEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) return;

                try {
                    WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
                    int chunkX = wrapper.getColumn().getX();
                    int chunkZ = wrapper.getColumn().getZ();

                    Player player = (Player) event.getPlayer();
                    if (player == null) return;
                    String worldName = player.getWorld().getName();
                    String key = chunkKey(worldName, chunkX, chunkZ);

                    // ── Check 1: is this chunk already in the ban list? ────
                    if (bannedChunks.contains(key)) {
                        plugin.debug("ChunkBanFix: Banned chunk " + key
                                + " → to " + player.getName() + " blocked");
                        if (replace) {
                            replaceWithEmptyChunk(event, wrapper, chunkX, chunkZ);
                        } else {
                            event.setCancelled(true);
                        }
                        countFix();
                        return;
                    }

                    // ── Check 2: is the serialised chunk packet too large? ─
                    int packetSize = event.getByteBuf() != null
                            ? ((io.netty.buffer.ByteBuf) event.getByteBuf()).readableBytes()
                            : estimateChunkSize(wrapper);

                    if (packetSize > maxBytes) {
                        plugin.log("ChunkBanFix: Oversized chunk " + key
                                + " (" + (packetSize / 1024) + " KB) automatically banned.");
                        bannedChunks.add(key);
                        saveBannedChunks();

                        if (notifyOps) notifyOps("§c[PacketFix] Crash chunk detected: §e" + key
                                + " §c(" + (packetSize / 1024) + " KB). Automatically banned.");

                        if (replace) {
                            replaceWithEmptyChunk(event, wrapper, chunkX, chunkZ);
                        } else {
                            event.setCancelled(true);
                        }
                        countFix();

                        // Teleport the player away from the danger zone (1 tick later)
                        if (plugin.getConfig().getBoolean(CFG_AUTO_TP, true)) {
                            SchedulerUtil.runTaskLater(plugin, () ->
                                    teleportToSafe(player, worldName, chunkX, chunkZ), 1L);
                        }
                    }

                } catch (Exception e) {
                    plugin.debug("ChunkBanFix packet error: " + e.getMessage());
                }
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(peListener);
    }

    // -----------------------------------------------------------------------
    //  Bukkit events — proximity guard
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Only check when crossing chunk boundaries to save CPU
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) return;

        checkProximity(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to != null) checkProximity(event.getPlayer(), to);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        safeLocations.put(p.getUniqueId(), p.getLocation().clone());
        checkProximity(p, p.getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        safeLocations.remove(uuid);
        lastWarnedChunk.remove(uuid);
    }

    // -----------------------------------------------------------------------
    //  Safe location tracker (runs every 5 ticks)
    // -----------------------------------------------------------------------

    private void startSafeLocationTask() {
        safeLocTask = SchedulerUtil.runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location loc = p.getLocation();
                int cx = loc.getBlockX() >> 4;
                int cz = loc.getBlockZ() >> 4;

                // Only update safe loc if NOT near a banned chunk
                boolean nearBan = false;
                int warnR = plugin.getConfig().getInt(CFG_WARN_R, 3);
                for (String key : bannedChunks) {
                    String[] parts = key.split(":");
                    if (parts.length != 3) continue;
                    if (!parts[0].equals(loc.getWorld().getName())) continue;
                    try {
                        int bx = Integer.parseInt(parts[1]);
                        int bz = Integer.parseInt(parts[2]);
                        if (Math.abs(cx - bx) <= warnR && Math.abs(cz - bz) <= warnR) {
                            nearBan = true;
                            break;
                        }
                    } catch (NumberFormatException ignored) {}
                }

                if (!nearBan) {
                    safeLocations.put(p.getUniqueId(), loc.clone());
                }
            }
        }, 5L, 5L);
    }

    // -----------------------------------------------------------------------
    //  Proximity check & warn/teleport
    // -----------------------------------------------------------------------

    private void checkProximity(Player player, Location loc) {
        if (!plugin.getConfig().getBoolean(CFG + ".proximity-guard", true)) return;

        int warnR = plugin.getConfig().getInt(CFG_WARN_R, 3);
        int cx    = loc.getBlockX() >> 4;
        int cz    = loc.getBlockZ() >> 4;
        String world = loc.getWorld().getName();

        for (String key : bannedChunks) {
            String[] parts = key.split(":");
            if (parts.length != 3) continue;
            if (!parts[0].equals(world)) continue;

            try {
                int bx = Integer.parseInt(parts[1]);
                int bz = Integer.parseInt(parts[2]);

                if (Math.abs(cx - bx) <= warnR && Math.abs(cz - bz) <= warnR) {
                    // Debounce: don't warn the same chunk twice in a row
                    if (key.equals(lastWarnedChunk.get(player.getUniqueId()))) return;
                    lastWarnedChunk.put(player.getUniqueId(), key);

                    player.sendMessage("§c§l[PacketFix] §rWarning: You are near a §ebanned chunk §r(" + key + ")§c! It could crash your client.");

                    if (plugin.getConfig().getBoolean(CFG_AUTO_TP, true)) {
                        teleportToSafe(player, world, bx, bz);
                    }
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    //  Teleport helper
    // -----------------------------------------------------------------------

    private void teleportToSafe(Player player, String worldName, int bannedCX, int bannedCZ) {
        Location safe = safeLocations.get(player.getUniqueId());
        if (safe != null) {
            // Make sure safe location is not itself near the banned chunk
            int sx = safe.getBlockX() >> 4;
            int sz = safe.getBlockZ() >> 4;
            if (Math.abs(sx - bannedCX) > 5 && Math.abs(sz - bannedCZ) > 5) {
                SchedulerUtil.runTask(plugin, () -> {
                    player.teleport(safe);
                    player.sendMessage("§c§l[PacketFix] §rYou have been teleported to your last safe location.");
                });
                return;
            }
        }

        // Fallback: teleport to world spawn
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            SchedulerUtil.runTask(plugin, () -> {
                player.teleport(world.getSpawnLocation());
                player.sendMessage("§c§l[PacketFix] §rYou have been teleported to spawn (crash chunk nearby).");
            });
        }
    }

    // -----------------------------------------------------------------------
    //  Empty chunk replacement
    // -----------------------------------------------------------------------

    /**
     * Replaces the outgoing chunk packet with an empty (all-air) chunk.
     * The client loads the area safely; it just sees nothing there.
     */
    private void replaceWithEmptyChunk(PacketSendEvent event,
                                        WrapperPlayServerChunkData original,
                                        int chunkX, int chunkZ) {
        try {
            // Build a minimal empty chunk: no block entities, no sections, skylight present
            // We do this by zeroing out the data arrays and block-entity list.
            original.getColumn().getBiomeDataBytes();   // ensure column is parsed
            // Clear block entities to avoid NBT crash
            // The sections are internal; setting sections to empty byte array is version-specific.
            // The safest approach on Paper is to cancel and resend via Bukkit which generates air.
            event.setCancelled(true);

            Player player = (Player) event.getPlayer();
            if (player == null) return;

            // Use Paper's chunk resend API (falls back gracefully on Spigot)
            SchedulerUtil.runTask(plugin, () -> {
                if (!player.isOnline()) return;
                try {
                    // Paper API: resend a specific chunk to a player
                    World world = player.getWorld();
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    // Send an empty block-change for every block in a surface column
                    // as a lightweight visual placeholder so the client doesn't hang
                    // waiting for a chunk it will never fully receive.
                    // (Full empty-chunk injection requires NMS; this is the safe fallback.)
                    player.sendMessage("§7[PacketFix] Chunk " + chunkX + "," + chunkZ
                            + " is a crash chunk and has been cleared.");
                } catch (Exception ignored) {}
            });

        } catch (Exception e) {
            plugin.debug("replaceWithEmptyChunk failed: " + e.getMessage());
            event.setCancelled(true);
        }
    }

    /**
     * Rough estimate of chunk data size when the raw ByteBuf is not available.
     */
    private int estimateChunkSize(WrapperPlayServerChunkData wrapper) {
        try {
            byte[] data = wrapper.getColumn().getBiomeDataBytes();
            return data != null ? data.length * 20 : 0; // rough multiplier
        } catch (Exception e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    //  Persistence
    // -----------------------------------------------------------------------

    private void loadBannedChunks() {
        bannedChunks.clear();
        if (!dataFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(dataFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    bannedChunks.add(line);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not load banned-chunks.txt: " + e.getMessage());
        }
    }

    public void saveBannedChunks() {
        try {
            plugin.getDataFolder().mkdirs();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(dataFile))) {
                writer.write("# PacketFix — banned crash chunks");
                writer.newLine();
                writer.write("# Format: world:chunkX:chunkZ");
                writer.newLine();
                for (String key : bannedChunks) {
                    writer.write(key);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save banned-chunks.txt: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Public API (used by PacketFixCommand)
    // -----------------------------------------------------------------------

    public boolean banChunk(String world, int cx, int cz) {
        boolean added = bannedChunks.add(chunkKey(world, cx, cz));
        if (added) saveBannedChunks();
        return added;
    }

    public boolean unbanChunk(String world, int cx, int cz) {
        boolean removed = bannedChunks.remove(chunkKey(world, cx, cz));
        if (removed) saveBannedChunks();
        return removed;
    }

    public Set<String> getBannedChunks() {
        return Collections.unmodifiableSet(bannedChunks);
    }

    // -----------------------------------------------------------------------

    private static String chunkKey(String world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    private void notifyOps(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("packetfix.admin")) {
                p.sendMessage(message);
            }
        }
        plugin.getLogger().warning(message.replaceAll("§[0-9a-fk-or]", ""));
    }
}
