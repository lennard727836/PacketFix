package de.packetfix.module.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import de.packetfix.PacketFixPlugin;
import de.packetfix.module.AbstractModule;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import de.packetfix.util.SchedulerUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shield Fix Module — port of Walksy/ShieldFixes.
 *
 * MC-105068: Vanilla does not play sounds when a shield absorbs a hit or
 *            gets disabled by an axe. We fire them via Bukkit events.
 *
 * MC-238293: The shield blocking animation is missing for OTHER players because
 *            the server only sends ENTITY_EQUIPMENT when the off-hand item changes,
 *            not when the "is blocking" flag changes.
 *            We intercept outgoing ENTITY_EQUIPMENT packets and inject the
 *            shield into the off-hand slot whenever the target is blocking,
 *            so the recipient's client renders the parry animation correctly.
 */
public class ShieldFixModule extends AbstractModule implements Listener {

    /** uuid → ms when the player started blocking (for 5-tick delay) */
    private final Map<UUID, Long> blockingStart = new ConcurrentHashMap<>();

    private PacketListenerAbstract peListener;

    public ShieldFixModule(PacketFixPlugin plugin) { super(plugin); }

    @Override public String getConfigKey() { return "shield-fixes"; }
    @Override public String getName()      { return "Shield-Fixes (Walksy port) — MC-105068 + MC-238293"; }

    // -----------------------------------------------------------------------
    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        if (plugin.getConfig().getBoolean("shield-fixes.fix-blocking-animation", true)) {
            registerAnimationFix();
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (peListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(peListener);
            peListener = null;
        }
        blockingStart.clear();
    }

    // -----------------------------------------------------------------------
    //  MC-238293 — blocking animation fix via PacketEvents 2.x
    // -----------------------------------------------------------------------

    private void registerAnimationFix() {
        boolean respect5Tick = plugin.getConfig().getBoolean("shield-fixes.respect-5-tick-delay", true);

        peListener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.ENTITY_EQUIPMENT) return;

                try {
                    WrapperPlayServerEntityEquipment wrapper = new WrapperPlayServerEntityEquipment(event);
                    int entityId = wrapper.getEntityId();

                    // Find the Bukkit player this equipment packet is about
                    User receiver = event.getUser();
                    org.bukkit.entity.Player receiverBukkit =
                            plugin.getServer().getPlayer(receiver.getUUID());
                    if (receiverBukkit == null) return;

                    // Find the entity in the receiver's world
                    org.bukkit.entity.Entity entity = receiverBukkit.getWorld()
                            .getEntities().stream()
                            .filter(e -> e.getEntityId() == entityId)
                            .findFirst().orElse(null);

                    if (!(entity instanceof Player)) return;
        Player target = (Player) entity;

                    // Only care about players with a shield in off-hand
                    org.bukkit.inventory.ItemStack offHand = target.getInventory().getItemInOffHand();
                    if (offHand.getType() != Material.SHIELD) return;
                    if (!target.isBlocking()) return;

                    // 5-tick delay check
                    if (respect5Tick) {
                        long now = System.currentTimeMillis();
                        blockingStart.putIfAbsent(target.getUniqueId(), now);
                        if (now - blockingStart.get(target.getUniqueId()) < 250) return;
                    }

                    // Check if OFF_HAND slot with shield is already in the packet
                    List<Equipment> slots =
                            new ArrayList<>(wrapper.getEquipment());

                    boolean hasOffhand = slots.stream().anyMatch(e ->
                            e.getSlot() == EquipmentSlot.OFF_HAND
                                    && e.getItem() != null
                                    && e.getItem().getType() == ItemTypes.SHIELD);

                    if (!hasOffhand) {
                        // Build a PacketEvents ItemStack for the shield
                        ItemStack peShield = ItemStack.builder()
                                .type(ItemTypes.SHIELD)
                                .amount(1)
                                .build();

                        slots.add(new Equipment(
                                EquipmentSlot.OFF_HAND, peShield));
                        wrapper.setEquipment(slots);
                        countFix();
                        plugin.debug("Shield injected into ENTITY_EQUIPMENT for "
                                + target.getName() + " → " + receiver.getName());
                    }
                } catch (Exception e) {
                    plugin.debug("ShieldFixModule packet error: " + e.getMessage());
                }
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(peListener);
    }

    // -----------------------------------------------------------------------
    //  MC-105068 — shield sounds via Bukkit events
    // -----------------------------------------------------------------------

    /** Shield absorbed a hit → play shield-block sound. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageAbsorbed(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("shield-fixes.sounds.hit.enabled", true)) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player defender = (Player) event.getEntity();
        if (!defender.isBlocking()) return;

        playSound(defender, "shield-fixes.sounds.hit", "ITEM_SHIELD_BLOCK", 1.0f, 1.0f);
        countFix();
    }

    /** Axe disables shield → play shield-break sound one tick later. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShieldDisabled(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("shield-fixes.sounds.disable.enabled", true)) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player defender = (Player) event.getEntity();
        // DamageCause.BLOCKING was not available in Paper 1.20.4; detect via axe + shield combo
        org.bukkit.entity.Entity attacker = event.getDamager();
        if (!(attacker instanceof Player)) return;
        Player attackerPlayer = (Player) attacker;
        org.bukkit.inventory.ItemStack mainHand = attackerPlayer.getInventory().getItemInMainHand();
        if (!mainHand.getType().name().contains("_AXE")) return;

        SchedulerUtil.runTaskLater(plugin, () -> {
            if (defender.getCooldown(Material.SHIELD) > 0) {
                playSound(defender, "shield-fixes.sounds.disable", "ITEM_SHIELD_BREAK", 1.0f, 0.8f);
                blockingStart.remove(defender.getUniqueId());
                countFix();
            }
        }, 1L);
    }

    /** Track when blocking stops (for 5-tick delay reset). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        if (!p.isBlocking()) blockingStart.remove(p.getUniqueId());
    }

    // -----------------------------------------------------------------------

    private void playSound(Player p, String path, String fallbackSound, float vol, float pitch) {
        String soundName = plugin.getConfig().getString(path + ".sound", fallbackSound);
        float  v         = (float) plugin.getConfig().getDouble(path + ".volume", vol);
        float  pi        = (float) plugin.getConfig().getDouble(path + ".pitch",  pitch);
        try {
            Sound s = Sound.valueOf(soundName);
            p.getWorld().playSound(p.getLocation(), s, v, pi);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown sound: " + soundName);
        }
    }
}
