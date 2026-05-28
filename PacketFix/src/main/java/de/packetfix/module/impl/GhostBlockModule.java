package de.packetfix.module.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import de.packetfix.PacketFixPlugin;
import de.packetfix.module.AbstractModule;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import de.packetfix.util.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ghost Block Module.
 *
 * A "ghost block" is a block that exists only in the client's local world copy
 * but not on the server (or vice-versa), causing a visual mismatch.
 *
 * ── Client-side ghost blocks ──────────────────────────────────────────────
 * Happen when the client predicts a placement / break that the server rejects.
 * Sources: protection plugins, anti-cheat, region flags, permissions.
 *
 * Fix: Listen for cancelled BlockPlaceEvent / BlockBreakEvent and denied
 * PlayerInteractEvent, then resend the correct block state via
 * WrapperPlayServerBlockChange (PacketEvents 2.x).
 *
 * Also intercept USE_ITEM_ON + PLAYER_DIGGING at the packet level to catch
 * edge-cases that don't fire Bukkit events (e.g. placing against a non-solid
 * face, client re-sends break packet twice, etc.).
 *
 * ── Server-side ghost blocks ──────────────────────────────────────────────
 * Happen when the server modifies a block without sending an update packet,
 * or when the update packet gets lost / reordered.
 * Sources: pistons, commands (/setblock), explosions, block-update chains,
 *          water/lava flow being blocked server-side.
 *
 * Fix: Hook BlockPhysicsEvent and BlockFromToEvent.  After any server-side
 * block change we schedule a resend of the affected block to ALL nearby
 * players within the configured radius.
 */
public class GhostBlockModule extends AbstractModule implements Listener {

    private final int  delayTicks;
    private final boolean resendNeighbours;
    private final boolean fixServerSide;
    private final int  serverSideRadius = 4; // chunk sections to re-notify

    /** Players who sent USE_ITEM_ON this tick — used to debounce */
    private final Set<UUID> pendingResend = ConcurrentHashMap.newKeySet();

    private PacketListenerAbstract peListener;

    public GhostBlockModule(PacketFixPlugin plugin) {
        super(plugin);
        this.delayTicks      = plugin.getConfig().getInt("ghost-block-fix.resend-delay-ticks", 2);
        this.resendNeighbours = plugin.getConfig().getBoolean("ghost-block-fix.resend-neighbours", true);
        this.fixServerSide   = plugin.getConfig().getBoolean("ghost-block-fix.fix-server-side", true);
    }

    @Override public String getConfigKey() { return "ghost-block-fix"; }
    @Override public String getName()      { return "Ghost-Block-Fix (Client + Server Side)"; }

    // -----------------------------------------------------------------------
    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerPacketListener();
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (peListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(peListener);
            peListener = null;
        }
        pendingResend.clear();
    }

    // -----------------------------------------------------------------------
    //  PacketEvents listener — USE_ITEM_ON + PLAYER_DIGGING
    // -----------------------------------------------------------------------

    private void registerPacketListener() {
        peListener = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {

            @Override
            public void onPacketReceive(PacketReceiveEvent event) {

                // ── USE_ITEM_ON (right-click place) ──────────────────────────
                if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                    try {
                        WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
                        Vector3i pos = wrapper.getBlockPosition();
                        if (pos == null) return;

                        Player player = (Player) event.getPlayer();
                        if (player == null) return;
                        UUID uuid = player.getUniqueId();

                        // Debounce — only schedule once per player per tick
                        if (!pendingResend.add(uuid)) return;

                        World world = player.getWorld();
                        Location clickedLoc = new Location(world, pos.x, pos.y, pos.z);

                        // Schedule resend after server tick processes the event
                        SchedulerUtil.runTaskLater(plugin, () -> {
                            pendingResend.remove(uuid);
                            if (!player.isOnline()) return;

                            Block clicked  = world.getBlockAt(clickedLoc);
                            Block relative = clicked.getRelative(wrapper.getFace().name()
                                    .equalsIgnoreCase("UP")   ? org.bukkit.block.BlockFace.UP   :
                                    wrapper.getFace().name().equalsIgnoreCase("DOWN") ? org.bukkit.block.BlockFace.DOWN :
                                    wrapper.getFace().name().equalsIgnoreCase("NORTH") ? org.bukkit.block.BlockFace.NORTH :
                                    wrapper.getFace().name().equalsIgnoreCase("SOUTH") ? org.bukkit.block.BlockFace.SOUTH :
                                    wrapper.getFace().name().equalsIgnoreCase("EAST")  ? org.bukkit.block.BlockFace.EAST :
                                    org.bukkit.block.BlockFace.WEST);

                            sendBlockUpdate(player, clicked);
                            sendBlockUpdate(player, relative);
                            if (resendNeighbours) {
                                sendNeighbours(player, clicked);
                                sendNeighbours(player, relative);
                            }
                        }, delayTicks);
                    } catch (Exception e) {
                        plugin.debug("GhostBlock USE_ITEM_ON error: " + e.getMessage());
                    }
                }

                // ── PLAYER_DIGGING (start/abort/finish break) ─────────────
                if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
                    try {
                        WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
                        Vector3i pos = wrapper.getBlockPosition();
                        if (pos == null) return;

                        Player player = (Player) event.getPlayer();
                        if (player == null) return;

                        World world = player.getWorld();
                        Location loc = new Location(world, pos.x, pos.y, pos.z);

                        SchedulerUtil.runTaskLater(plugin, () -> {
                            if (!player.isOnline()) return;
                            Block block = world.getBlockAt(loc);
                            sendBlockUpdate(player, block);
                            if (resendNeighbours) sendNeighbours(player, block);
                        }, delayTicks);
                    } catch (Exception e) {
                        plugin.debug("GhostBlock PLAYER_DIGGING error: " + e.getMessage());
                    }
                }
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(peListener);
    }

    // -----------------------------------------------------------------------
    //  Bukkit events — CLIENT-SIDE ghost blocks
    // -----------------------------------------------------------------------

    /** Cancelled placement → client has a ghost block, resend real state. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.isCancelled()) return;
        scheduleResend(event.getPlayer(), event.getBlock());
    }

    /** Cancelled break → client may show the block as cracked/broken. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) return;
        scheduleResend(event.getPlayer(), event.getBlock());
    }

    /** Denied right-click with a placeable item in hand. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!event.isBlockInHand()) return;
        if (event.useItemInHand() != Event.Result.DENY) return;

        Block clicked  = event.getClickedBlock();
        if (clicked == null) return;
        Block relative = clicked.getRelative(event.getBlockFace());

        Player player = event.getPlayer();
        SchedulerUtil.runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendBlockUpdate(player, clicked);
            sendBlockUpdate(player, relative);
            if (resendNeighbours) {
                sendNeighbours(player, clicked);
                sendNeighbours(player, relative);
            }
            countFix();
        }, delayTicks);
    }

    // -----------------------------------------------------------------------
    //  Bukkit events — SERVER-SIDE ghost blocks
    // -----------------------------------------------------------------------

    /**
     * BlockPhysicsEvent fires when a block receives a physics update.
     * We resend the block to all nearby players so their clients stay in sync.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (!fixServerSide) return;
        Block block = event.getBlock();
        // Resend to all players within radius
        SchedulerUtil.runTaskLater(plugin, () -> {
            broadcastBlockUpdate(block);
        }, delayTicks);
    }

    /**
     * BlockFromToEvent: water/lava flows or dragon egg teleports.
     * The "to" block often ghost-desynchs on clients.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!fixServerSide) return;
        Block to = event.getToBlock();
        SchedulerUtil.runTaskLater(plugin, () -> {
            broadcastBlockUpdate(to);
            broadcastBlockUpdate(event.getBlock());
        }, delayTicks);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void scheduleResend(Player player, Block block) {
        SchedulerUtil.runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendBlockUpdate(player, block);
            if (resendNeighbours) sendNeighbours(player, block);
            countFix();
            plugin.debug("Ghost block resent to " + player.getName()
                    + " @ " + block.getX() + "," + block.getY() + "," + block.getZ());
        }, delayTicks);
    }

    /**
     * Send a WrapperPlayServerBlockChange via PacketEvents 2.x.
     * Uses SpigotConversionUtil to convert Bukkit BlockData → WrappedBlockState.
     */
    private void sendBlockUpdate(Player player, Block block) {
        if (!player.isOnline()) return;
        try {
            Vector3i pos   = new Vector3i(block.getX(), block.getY(), block.getZ());
            com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState state =
                    SpigotConversionUtil.fromBukkitBlockData(block.getBlockData());

            WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(pos, state.getGlobalId());
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        } catch (Exception e) {
            // Fallback to Bukkit API
            try {
                player.sendBlockChange(block.getLocation(), block.getBlockData());
            } catch (Exception ignored) {}
        }
    }

    private void sendNeighbours(Player player, Block center) {
        for (org.bukkit.block.BlockFace face : org.bukkit.block.BlockFace.values()) {
            if (face.isCartesian() && face != org.bukkit.block.BlockFace.SELF) {
                sendBlockUpdate(player, center.getRelative(face));
            }
        }
    }

    /**
     * Broadcast a block update to all players within serverSideRadius chunks.
     */
    private void broadcastBlockUpdate(Block block) {
        Location loc = block.getLocation();
        int radiusBlocks = serverSideRadius * 16;
        for (Player p : block.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= (double) radiusBlocks * radiusBlocks) {
                sendBlockUpdate(p, block);
            }
        }
        countFix();
    }
}
