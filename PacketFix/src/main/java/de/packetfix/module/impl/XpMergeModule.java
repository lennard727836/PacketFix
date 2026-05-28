package de.packetfix.module.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import de.packetfix.PacketFixPlugin;
import de.packetfix.module.AbstractModule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.scheduler.BukkitTask;
import de.packetfix.util.SchedulerUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * XP / Item Merge Module.
 *
 * ── Why this matters ─────────────────────────────────────────────────────
 * Vanilla Minecraft spawns individual XP orbs for every drop event.
 * A single mob kill can spawn 5–15 separate XP orb entities, each sending
 * its own SPAWN_EXPERIENCE_ORB packet. In grinder farms or mass-kill events
 * (TNT, area kills) hundreds of orbs spawn simultaneously, which:
 *
 *   - Floods the client with SPAWN_EXPERIENCE_ORB packets.
 *   - Causes client-side FPS drops and packet processing lag.
 *   - Causes server-side TPS drops from entity-tick overhead.
 *   - Triggers "Too many entities" / entity limit kicks on some servers.
 *
 * ── XP Orb Merging ───────────────────────────────────────────────────────
 * When XP orbs are spawned near each other (within `merge-radius` blocks)
 * within the same `merge-window-ticks` window, they are merged into a single
 * orb carrying the combined XP value. This:
 *
 *   - Sends ONE SPAWN_EXPERIENCE_ORB packet instead of many.
 *   - Reduces entity tick count dramatically.
 *   - Respects the vanilla per-orb XP cap (config: `max-orb-value`) so
 *     the client still plays pickup sounds at the right cadence.
 *
 * ── Item Stack Merging ───────────────────────────────────────────────────
 * Dropped items of the same Material within `item-merge-radius` blocks are
 * merged into a single item stack (up to the item's max stack size).
 * This reduces SPAWN_ENTITY packets and entity count.
 *
 * ── Packet-Level Dedup ───────────────────────────────────────────────────
 * As a secondary safety net, outgoing SPAWN_EXPERIENCE_ORB packets that
 * duplicate an orb already sent within the last tick to the same player
 * are silently dropped (prevents double-spawn from race conditions).
 *
 * ── Configuration (config.yml section: xp-merge) ─────────────────────────
 *   enabled              — master switch
 *   merge-radius         — blocks radius for XP orb merging (default 3.0)
 *   merge-window-ticks   — how many ticks to collect orbs before merging (2)
 *   max-orb-value        — max XP per orb after merging (default 2477 = vanilla max)
 *   merge-items          — also merge dropped item stacks
 *   item-merge-radius    — radius for item merging (default 2.5)
 *   dedup-packets        — drop duplicate SPAWN_EXPERIENCE_ORB packets
 *   log-merges           — log merge operations to console (debug)
 */
public class XpMergeModule extends AbstractModule implements Listener {

    // ── Config keys ──────────────────────────────────────────────────────
    private static final String CFG            = "xp-merge";
    private static final String CFG_RADIUS     = CFG + ".merge-radius";
    private static final String CFG_WINDOW     = CFG + ".merge-window-ticks";
    private static final String CFG_MAX_ORB    = CFG + ".max-orb-value";
    private static final String CFG_ITEMS      = CFG + ".merge-items";
    private static final String CFG_ITEM_R     = CFG + ".item-merge-radius";
    private static final String CFG_DEDUP      = CFG + ".dedup-packets";

    // Vanilla max XP value for a single orb (gives 7 levels at 0)
    private static final int VANILLA_MAX_ORB = 2477;

    // ── State ────────────────────────────────────────────────────────────

    /**
     * Pending orb groups: world → list of (location, total xp) buckets.
     * We collect spawning orbs for merge-window-ticks, then merge.
     */
    private final Map<UUID, List<OrbBucket>> pendingOrbs = new ConcurrentHashMap<>();

    /**
     * Per-player dedup: player UUID → set of (entityId) seen this tick.
     * Cleared every tick.
     */
    private final Map<UUID, Set<Integer>> seenOrbIds = new ConcurrentHashMap<>();

    private Object mergeTask;
    private Object dedupClearTask;
    private PacketListenerAbstract peListener;

    // ── Config cache ─────────────────────────────────────────────────────
    private double mergeRadius;
    private int    mergeWindowTicks;
    private int    maxOrbValue;
    private boolean mergeItems;
    private double itemMergeRadius;
    private boolean dedupPackets;

    public XpMergeModule(PacketFixPlugin plugin) { super(plugin); }

    @Override public String getConfigKey() { return CFG; }
    @Override public String getName()      { return "XP & Item Merge (entity / packet reduction)"; }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    public void enable() {
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startMergeTask();
        if (dedupPackets) registerPacketListener();
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (mergeTask     != null) { SchedulerUtil.cancelTask(mergeTask);     mergeTask     = null; }
        if (dedupClearTask != null) { SchedulerUtil.cancelTask(dedupClearTask); dedupClearTask = null; }
        if (peListener    != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(peListener);
            peListener = null;
        }
        pendingOrbs.clear();
        seenOrbIds.clear();
    }

    private void loadConfig() {
        mergeRadius      = plugin.getConfig().getDouble(CFG_RADIUS,  3.0);
        mergeWindowTicks = plugin.getConfig().getInt   (CFG_WINDOW,  2);
        maxOrbValue      = plugin.getConfig().getInt   (CFG_MAX_ORB, VANILLA_MAX_ORB);
        mergeItems       = plugin.getConfig().getBoolean(CFG_ITEMS,  true);
        itemMergeRadius  = plugin.getConfig().getDouble(CFG_ITEM_R,  2.5);
        dedupPackets     = plugin.getConfig().getBoolean(CFG_DEDUP,  true);
    }

    // ── XP Orb Spawning Hook ─────────────────────────────────────────────

    /**
     * Intercept every XP orb spawn. Instead of letting it land in the world
     * immediately, we queue it in a pending bucket. After mergeWindowTicks
     * ticks the merge task processes all buckets and spawns consolidated orbs.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOrbSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof ExperienceOrb)) return;
        ExperienceOrb orb = (ExperienceOrb) event.getEntity();

        Location loc = orb.getLocation();
        UUID worldId = loc.getWorld().getUID();
        int xp = orb.getExperience();

        // Cancel the vanilla orb — we'll spawn our own merged version
        event.setCancelled(true);

        List<OrbBucket> buckets = pendingOrbs.computeIfAbsent(worldId, k -> new ArrayList<>());

        synchronized (buckets) {
            // Find an existing nearby bucket to merge into
            OrbBucket target = null;
            for (OrbBucket b : buckets) {
                if (b.location.getWorld() == loc.getWorld()
                        && b.location.distanceSquared(loc) <= mergeRadius * mergeRadius) {
                    target = b;
                    break;
                }
            }

            if (target != null) {
                target.xp += xp;
                plugin.debug("XpMerge: Merged " + xp + " XP into bucket at "
                        + fmtLoc(target.location) + " → total " + target.xp);
            } else {
                buckets.add(new OrbBucket(loc.clone(), xp));
                plugin.debug("XpMerge: New bucket at " + fmtLoc(loc) + " with " + xp + " XP");
            }
        }
    }

    // ── Item Drop Merging ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDrop(ItemSpawnEvent event) {
        if (!mergeItems) return;

        org.bukkit.entity.Item newItem = event.getEntity();
        org.bukkit.inventory.ItemStack newStack = newItem.getItemStack();
        if (newStack == null || newStack.getType().isAir()) return;

        Location loc  = newItem.getLocation();
        World   world = loc.getWorld();
        double  r2    = itemMergeRadius * itemMergeRadius;
        int     max   = newStack.getMaxStackSize();

        // Look for nearby items of the same type
        for (org.bukkit.entity.Entity nearby : world.getNearbyEntities(loc, itemMergeRadius, itemMergeRadius, itemMergeRadius)) {
            if (!(nearby instanceof org.bukkit.entity.Item existingItem)) continue;
            if (nearby.getEntityId() == newItem.getEntityId()) continue;
            if (nearby.getLocation().distanceSquared(loc) > r2) continue;

            org.bukkit.inventory.ItemStack existingStack = existingItem.getItemStack();
            if (existingStack == null) continue;
            if (!existingStack.isSimilar(newStack)) continue;

            int existing = existingStack.getAmount();
            int adding   = newStack.getAmount();
            int combined = existing + adding;

            if (combined <= max) {
                // Full merge: add all to existing, cancel new spawn
                existingStack.setAmount(combined);
                existingItem.setItemStack(existingStack);
                event.setCancelled(true);
                countFix();
                plugin.debug("ItemMerge: Merged " + adding + "x "
                        + newStack.getType().name() + " into existing stack → " + combined);
                return;
            } else if (existing < max) {
                // Partial merge: fill existing to max, carry remainder
                int remainder = combined - max;
                existingStack.setAmount(max);
                existingItem.setItemStack(existingStack);
                newStack.setAmount(remainder);
                newItem.setItemStack(newStack);
                countFix();
                plugin.debug("ItemMerge: Partial merge → existing=" + max
                        + " remainder=" + remainder);
                // Don't return — carry the remainder and keep looking
            }
        }
    }

    // ── Merge Task ────────────────────────────────────────────────────────

    /**
     * Runs every mergeWindowTicks and flushes pending orb buckets.
     * Each bucket is split into orbs of at most maxOrbValue XP and spawned.
     */
    private void startMergeTask() {
        mergeTask = SchedulerUtil.runTaskTimer(plugin, () -> {

            for (Map.Entry<UUID, List<OrbBucket>> entry : pendingOrbs.entrySet()) {
                World world = plugin.getServer().getWorld(entry.getKey());
                if (world == null) continue;

                List<OrbBucket> buckets;
                synchronized (entry.getValue()) {
                    buckets = new ArrayList<>(entry.getValue());
                    entry.getValue().clear();
                }

                for (OrbBucket bucket : buckets) {
                    int remaining = bucket.xp;
                    int spawned = 0;

                    while (remaining > 0) {
                        int orbXp = Math.min(remaining, maxOrbValue);
                        remaining -= orbXp;

                        // Spread orbs slightly so pickup feels natural
                        Location spawnLoc = bucket.location.clone().add(
                                (Math.random() - 0.5) * 0.5,
                                0,
                                (Math.random() - 0.5) * 0.5
                        );

                        final int finalOrbXp = orbXp;
                        world.spawn(spawnLoc, ExperienceOrb.class, orb -> {
                            orb.setExperience(finalOrbXp);
                        });
                        spawned++;
                    }

                    if (spawned > 0) {
                        countFix();
                        plugin.debug("XpMerge: Flushed bucket " + fmtLoc(bucket.location)
                                + " → " + bucket.xp + " XP in " + spawned + " orb(s)");
                    }
                }
            }

        }, mergeWindowTicks, mergeWindowTicks);
    }

    // ── Packet dedup ─────────────────────────────────────────────────────

    private void registerPacketListener() {
        // Clear dedup sets every tick
        dedupClearTask = SchedulerUtil.runTaskTimer(plugin, () ->
                seenOrbIds.clear(), 1L, 1L);

        peListener = new PacketListenerAbstract(PacketListenerPriority.LOW) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.SPAWN_EXPERIENCE_ORB) return;
                try {
                    WrapperPlayServerSpawnExperienceOrb wrapper =
                            new WrapperPlayServerSpawnExperienceOrb(event);
                    int orbId = wrapper.getEntityId();
                    UUID playerUuid = event.getUser().getUUID();

                    Set<Integer> seen = seenOrbIds.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet());
                    if (!seen.add(orbId)) {
                        // Duplicate orb packet this tick for this player
                        event.setCancelled(true);
                        countFix();
                        plugin.debug("XpMerge: Dropped duplicate SPAWN_EXPERIENCE_ORB id=" + orbId
                                + " for " + event.getUser().getName());
                    }
                } catch (Exception e) {
                    plugin.debug("XpMerge dedup error: " + e.getMessage());
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(peListener);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String fmtLoc(Location l) {
        return String.format("(%.1f,%.1f,%.1f)", l.getX(), l.getY(), l.getZ());
    }

    // ── Inner classes ─────────────────────────────────────────────────────

    private static class OrbBucket {
        Location location;
        int      xp;

        OrbBucket(Location location, int xp) {
            this.location = location;
            this.xp       = xp;
        }
    }
}
