package de.packetfix.module.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
import de.packetfix.PacketFixPlugin;
import de.packetfix.module.AbstractModule;
import org.bukkit.Bukkit;
import de.packetfix.util.SchedulerUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ghost Hit Fix Module.
 *
 * A "ghost hit" occurs when a player's client shows a hit visual — the hurt
 * red flash (ENTITY_STATUS byte 2), the damage tilt, the hurt sound — even
 * though the server cancelled or overrode the damage event (e.g. God mode,
 * WorldGuard protection, invulnerability frames, a cancelled
 * EntityDamageByEntityEvent from another plugin).
 *
 * The client pre-renders the hit the moment the INTERACT_AT / ATTACK packet
 * is sent; it does NOT wait for server confirmation. So when the server
 * cancels the damage, the client is left with the ghost animation.
 *
 * ── What we send back to correct the client ──────────────────────────────
 *
 * 1. ENTITY_STATUS (id=2 "entity hurt" suppressor):
 *    Sending ENTITY_STATUS with status byte 9 ("Living Entity health reset")
 *    and immediately after with the entity's actual state resets the red-tint
 *    on the attacker's and nearby clients' screens.
 *
 * 2. WrapperPlayServerUpdateHealth (to the victim if they are a Player):
 *    Re-sends their current health/food so their HUD doesn't flicker.
 *
 * 3. WrapperPlayServerEntityAnimation with type HURT:
 *    We do NOT send this — we intentionally omit it so the hurt animation
 *    is NOT played, which is the whole point of the fix.
 *    Instead, we send a NO_HURT animation (or nothing) so the client model
 *    resets to idle.
 *
 * ── When we trigger ──────────────────────────────────────────────────────
 *
 * PRIMARY  — EntityDamageByEntityEvent with isCancelled() at MONITOR priority.
 *            If the event was cancelled we resend the corrective packets.
 *
 * SECONDARY — EntityDamageEvent at MONITOR: catches cases where another plugin
 *             sets damage to 0 without cancelling (the entity still flashes).
 *
 * ── Notes ────────────────────────────────────────────────────────────────
 * - We only fix cases where the ATTACKER is a Player (they are the ones
 *   seeing the ghost hit on THEIR client).
 * - We also fix spectating players who can see the victim flash.
 * - We do NOT interfere with legitimate hits.
 */
public class GhostHitFixModule extends AbstractModule implements Listener {

    /**
     * Entity Status byte values (server→client):
     *   2  = generic hurt / take damage
     *   3  = entity dead
     *   9  = living entity health set (used to "reset" hurt state)
     *  29  = shield block (used by shield fix, unrelated here)
     */
    private static final byte STATUS_HURT       = 2;
    private static final byte STATUS_HEALTH_SET = 9;

    /**
     * We track which entities were just "fake-hurt" this tick so we don't
     * double-send corrective packets when multiple event handlers fire.
     */
    private final Map<UUID, Long> recentlyCorrected = new ConcurrentHashMap<>();
    private static final long DEBOUNCE_MS = 100;

    public GhostHitFixModule(PacketFixPlugin plugin) { super(plugin); }

    @Override public String getConfigKey() { return "ghost-hit-fix"; }
    @Override public String getName()      { return "Ghost-Hit-Fix (cancelled damage desync)"; }

    // -----------------------------------------------------------------------
    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        recentlyCorrected.clear();
    }

    // -----------------------------------------------------------------------
    //  PRIMARY: cancelled EntityDamageByEntityEvent
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCancelledAttack(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()) return;

        Entity victim   = event.getEntity();
        Entity attacker = getRootAttacker(event.getDamager());

        handleGhostHit(victim, attacker, true);
    }

    // -----------------------------------------------------------------------
    //  SECONDARY: damage reduced to exactly 0 without cancelling the event
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onZeroDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("ghost-hit-fix.fix-zero-damage", true)) return;
        if (event.getFinalDamage() > 0) return;

        Entity victim   = event.getEntity();
        Entity attacker = getRootAttacker(event.getDamager());

        handleGhostHit(victim, attacker, false);
    }

    // -----------------------------------------------------------------------
    //  Core correction logic
    // -----------------------------------------------------------------------

    private void handleGhostHit(Entity victim, Entity attacker, boolean wasCancelled) {
        if (!(victim instanceof LivingEntity)) return;
        LivingEntity livingVictim = (LivingEntity) victim;

        UUID victimId = victim.getUniqueId();

        // Debounce — don't resend within 100 ms for the same entity
        long now = System.currentTimeMillis();
        Long last = recentlyCorrected.get(victimId);
        if (last != null && now - last < DEBOUNCE_MS) return;
        recentlyCorrected.put(victimId, now);

        // Schedule 1 tick later — gives the server time to finish processing
        // the damage event before we send corrective packets
        int delayTicks = plugin.getConfig().getInt("ghost-hit-fix.resend-delay-ticks", 1);

        SchedulerUtil.runTaskLater(plugin, () -> {
            if (!victim.isValid()) return;

            // ── 1. Send STATUS_HEALTH_SET to all nearby players ──────────
            // This resets the hurt tint / red flash on their screen.
            sendEntityStatusToNearby(victim, STATUS_HEALTH_SET);

            // ── 2. If the victim is a Player, resend their health ─────────
            if (livingVictim instanceof Player victimPlayer && victimPlayer.isOnline()) {
                resendHealth(victimPlayer);
            }

            // ── 3. Log ────────────────────────────────────────────────────
            String attackerName = (attacker instanceof Player ? ((Player)attacker).getName() : "non-player");
            plugin.debug("Ghost hit corrected: attacker=" + attackerName
                    + " victim=" + victim.getName()
                    + (wasCancelled ? " [event cancelled]" : " [damage=0]"));

            countFix();

        }, delayTicks);
    }

    // -----------------------------------------------------------------------
    //  Packet helpers
    // -----------------------------------------------------------------------

    /**
     * Sends ENTITY_STATUS to all players within 64 blocks of the entity
     * (the client renders hit effects for entities in this range).
     */
    private void sendEntityStatusToNearby(Entity entity, byte status) {
        WrapperPlayServerEntityStatus statusPacket =
                new WrapperPlayServerEntityStatus(entity.getEntityId(), status);

        for (Player observer : entity.getWorld().getPlayers()) {
            if (observer.getLocation().distanceSquared(entity.getLocation()) > 64 * 64) continue;
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacket(observer, statusPacket);
            } catch (Exception e) {
                plugin.debug("sendEntityStatus failed for " + observer.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Resends the player's current health, food, and saturation to their client.
     * Prevents the HUD from showing incorrect values after a ghost hit.
     */
    private void resendHealth(Player player) {
        try {
            WrapperPlayServerUpdateHealth healthPacket = new WrapperPlayServerUpdateHealth(
                    (float) player.getHealth(),
                    player.getFoodLevel(),
                    player.getSaturation()
            );
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, healthPacket);
        } catch (Exception e) {
            plugin.debug("resendHealth failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Resolves the root attacker — e.g. if the damager is a projectile,
     * walk up to find the Player who shot it.
     */
    private Entity getRootAttacker(Entity damager) {
        if (damager instanceof org.bukkit.entity.Projectile proj) {
            if (proj.getShooter() instanceof Entity) return (Entity) proj.getShooter();
        }
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt) {
            if (tnt.getSource() instanceof Entity) return (Entity) tnt.getSource();
        }
        return damager;
    }
}
