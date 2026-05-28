package de.packetfix.module.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import de.packetfix.PacketFixPlugin;
import de.packetfix.module.AbstractModule;
import de.packetfix.util.PlatformUtil;
import org.bukkit.Bukkit;
import de.packetfix.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ping Limiter Module.
 *
 * Monitors every player's latency in real-time using PacketEvents KEEP_ALIVE
 * round-trip measurement and takes configurable actions when a player's ping
 * exceeds defined thresholds.
 *
 * ── How ping is measured ─────────────────────────────────────────────────
 * Vanilla keep-alive works by the server sending KEEP_ALIVE (server→client)
 * with a random ID. The client must echo it back. The round-trip time between
 * the server send and the client response is the player's true latency.
 *
 * PacketEvents exposes both sides, so we intercept:
 *   OUTBOUND  WrapperPlayServerKeepAlive  → record send timestamp
 *   INBOUND   WrapperPlayClientKeepAlive  → compute RTT = now - sendTime
 *
 * This gives a per-keep-alive sample. We maintain a rolling window of the
 * last N samples for smoothing and spike detection.
 *
 * ── Actions ──────────────────────────────────────────────────────────────
 *
 *  warn-threshold-ms    — send the player a configurable warning message
 *  kick-threshold-ms    — kick the player with a configurable message
 *  throttle-threshold-ms — reduce the packet rate for high-ping players
 *                          (not yet sent, queued by PacketEvents)
 *
 * All thresholds support a "sustained" check: the player must be above the
 * threshold for at least `sustained-checks` consecutive measurements before
 * the action fires. This prevents false-positives from single-packet spikes.
 *
 * ── Bypass ───────────────────────────────────────────────────────────────
 *  Players with the permission `packetfix.ping.bypass` are never kicked.
 *  Players with `packetfix.ping.warn-bypass` don't receive warnings.
 *
 * ── Commands (handled by PacketFixCommand) ────────────────────────────────
 *  /packetfix ping [player]  — show current ping stats for a player
 *  /packetfix ping list      — list all online players with their ping
 */
public class PingLimiterModule extends AbstractModule implements Listener {

    // ── Config keys ──────────────────────────────────────────────────────
    private static final String CFG                  = "ping-limiter";
    private static final String CFG_KICK_MS          = CFG + ".kick-threshold-ms";
    private static final String CFG_KICK_MSG         = CFG + ".kick-message";
    private static final String CFG_SUSTAINED        = CFG + ".sustained-checks";
    private static final String CFG_SAMPLE_WINDOW    = CFG + ".sample-window-size";
    private static final String CFG_CHECK_INTERVAL   = CFG + ".check-interval-ticks";
    private static final String CFG_WARN_OPS         = CFG + ".notify-ops-on-kick";
    private static final String CFG_USE_BUKKIT_PING  = CFG + ".use-bukkit-ping-fallback";

    // ── Per-player state ─────────────────────────────────────────────────

    /** UUID → circular buffer of recent RTT samples (ms) */
    private final Map<UUID, long[]>  samples           = new ConcurrentHashMap<>();
    /** UUID → write index into the sample buffer */
    private final Map<UUID, Integer> sampleIndex       = new ConcurrentHashMap<>();
    /** UUID → send-time of last outgoing KEEP_ALIVE (ms epoch) */
    private final Map<UUID, Long>    keepAliveSentTime = new ConcurrentHashMap<>();
    /** UUID → ID of the last outgoing KEEP_ALIVE */
    private final Map<UUID, Long>    keepAliveId       = new ConcurrentHashMap<>();
    /** UUID → consecutive ticks above kick threshold */
    private final Map<UUID, Integer> kickStreak        = new ConcurrentHashMap<>();

    // ── Config cache ─────────────────────────────────────────────────────
    private int    warnThresholdMs;
    private int    kickThresholdMs;
    private String kickMessage;
    private int    sustainedChecks;
    private int    sampleWindowSize;
    private boolean notifyOpsOnKick;
    private boolean useBukkitPingFallback;

    private PacketListenerAbstract peListener;
    private Object checkTask;

    public PingLimiterModule(PacketFixPlugin plugin) { super(plugin); }

    @Override public String getConfigKey() { return CFG; }
    @Override public String getName()      { return "Ping-Limiter (latency monitor + kick)"; }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    public void enable() {
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerPacketListener();
        startCheckTask();
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (peListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(peListener);
            peListener = null;
        }
        if (checkTask != null) { SchedulerUtil.cancelTask(checkTask); checkTask = null; }
        samples.clear();
        sampleIndex.clear();
        keepAliveSentTime.clear();
        keepAliveId.clear();
        kickStreak.clear();
    }

    private void loadConfig() {
        kickThresholdMs     = plugin.getConfig().getInt(CFG_KICK_MS,        1000);
        warnThresholdMs     = plugin.getConfig().getInt(CFG + ".warn-threshold-ms", (int)(kickThresholdMs * 0.8));
        kickMessage         = plugin.getConfig().getString(CFG_KICK_MSG,
                "&cYou were kicked due to high ping (&e{ping}ms&c).");
        sustainedChecks     = plugin.getConfig().getInt(CFG_SUSTAINED,      3);
        sampleWindowSize    = Math.max(1, plugin.getConfig().getInt(CFG_SAMPLE_WINDOW, 5));
        notifyOpsOnKick     = plugin.getConfig().getBoolean(CFG_WARN_OPS,   true);
        useBukkitPingFallback = plugin.getConfig().getBoolean(CFG_USE_BUKKIT_PING, true);
    }

    // ── PacketEvents: KEEP_ALIVE measurement ─────────────────────────────

    private void registerPacketListener() {
        peListener = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {

            // Record send time when server sends KEEP_ALIVE
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.KEEP_ALIVE) return;
                try {
                    WrapperPlayServerKeepAlive wrapper = new WrapperPlayServerKeepAlive(event);
                    UUID uuid = event.getUser().getUUID();
                    keepAliveSentTime.put(uuid, System.currentTimeMillis());
                    keepAliveId.put(uuid, wrapper.getId());
                } catch (Exception e) {
                    plugin.debug("PingLimiter send error: " + e.getMessage());
                }
            }

            // Compute RTT when client echoes KEEP_ALIVE
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.KEEP_ALIVE) return;
                try {
                    WrapperPlayClientKeepAlive wrapper = new WrapperPlayClientKeepAlive(event);
                    UUID uuid = event.getUser().getUUID();

                    Long sentId   = keepAliveId.get(uuid);
                    Long sentTime = keepAliveSentTime.get(uuid);

                    // Only count the response if it matches the last sent ID
                    if (sentId == null || sentTime == null) return;
                    if (wrapper.getId() != sentId) return;

                    long rtt = System.currentTimeMillis() - sentTime;
                    recordSample(uuid, rtt);

                    plugin.debug("PingLimiter: " + event.getUser().getName()
                            + " RTT=" + rtt + "ms  avg=" + getAveragePing(uuid) + "ms");

                } catch (Exception e) {
                    plugin.debug("PingLimiter receive error: " + e.getMessage());
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(peListener);
    }

    // ── Periodic check task ──────────────────────────────────────────────

    private void startCheckTask() {
        int interval = plugin.getConfig().getInt(CFG_CHECK_INTERVAL, 40); // every 2s default
        checkTask = SchedulerUtil.runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                checkPlayer(player);
            }
        }, interval, interval);
    }

    private void checkPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        long ping = getMeasuredPing(uuid, player);
        if (ping < 0) return;

        // ── Kick check ───────────────────────────────────────────────────
        if (kickThresholdMs > 0 && ping >= kickThresholdMs) {
            if (!player.hasPermission("packetfix.ping.bypass")) {
                int streak = kickStreak.merge(uuid, 1, Integer::sum);
                if (streak >= sustainedChecks) {
                    kickStreak.put(uuid, 0);
                    String msg = colorize(kickMessage.replace("{ping}", String.valueOf(ping)));
                    SchedulerUtil.runTask(plugin, () -> {
                        if (player.isOnline()) {
                            PlatformUtil.kickPlayer(player, msg);
                            countFix();
                            plugin.log("PingLimiter: " + player.getName()
                                    + " kicked (ping " + ping + "ms >= " + kickThresholdMs + "ms)");
                            if (notifyOpsOnKick) notifyOps(
                                    "§c[PacketFix] §e" + player.getName()
                                    + " §cgekickt — Ping: §e" + ping + "ms");
                        }
                    });
                    return; // don't warn if we just kicked
                }
            }
        } else {
            kickStreak.put(uuid, 0);
        }


    }

    // ── Bukkit events ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        // Initialise sample buffer
        samples.put(uuid, new long[sampleWindowSize]);
        sampleIndex.put(uuid, 0);
        kickStreak.put(uuid, 0);


    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        samples.remove(uuid);
        sampleIndex.remove(uuid);
        keepAliveSentTime.remove(uuid);
        keepAliveId.remove(uuid);
        kickStreak.remove(uuid);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void recordSample(UUID uuid, long rttMs) {
        long[] buf = samples.get(uuid);
        if (buf == null) {
            buf = new long[sampleWindowSize];
            samples.put(uuid, buf);
        }
        int idx = sampleIndex.getOrDefault(uuid, 0);
        buf[idx % sampleWindowSize] = rttMs;
        sampleIndex.put(uuid, idx + 1);
    }

    /**
     * Returns the smoothed average ping (ms) from the rolling sample window.
     * Falls back to Bukkit's player.getPing() if no samples yet.
     */
    public long getAveragePing(UUID uuid) {
        long[] buf = samples.get(uuid);
        if (buf == null) return -1;
        long sum = 0; int count = 0;
        for (long v : buf) { if (v > 0) { sum += v; count++; } }
        return count == 0 ? -1 : sum / count;
    }

    /** Returns the best available ping value for the given player. */
    private long getMeasuredPing(UUID uuid, Player player) {
        long avg = getAveragePing(uuid);
        if (avg > 0) return avg;
        if (useBukkitPingFallback) return PlatformUtil.getPing(player);
        return -1;
    }

    /** Public accessor used by PacketFixCommand for /packetfix ping */
    public long getPing(Player player) {
        return getMeasuredPing(player.getUniqueId(), player);
    }

    /**
     * Returns a formatted ping string with colour coding:
     *   green < warn, yellow < kick, red >= kick
     */
    public String getColouredPing(Player player) {
        long ping  = getPing(player);
        int  warn  = warnThresholdMs;
        int  kick  = kickThresholdMs;
        String colour = ping < warn ? "§a" : ping < kick ? "§e" : "§c";
        return colour + ping + "ms";
    }

    private void notifyOps(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("packetfix.admin")) p.sendMessage(message);
        }
        plugin.getLogger().info(message.replaceAll("§[0-9a-fk-or]", ""));
    }

    private static String colorize(String s) {
        return s.replace("&", "§");
    }
}
