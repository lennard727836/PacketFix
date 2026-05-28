package de.packetfix.module.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import de.packetfix.module.AbstractModule;
import de.packetfix.PacketFixPlugin;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PacketFixer Module — port of TonimatasDEV/PacketFixer.
 *
 * Original uses @ModifyConstant Mixins to raise hard-coded constants inside:
 *   - FriendlyByteBuf#readNbt         → max NBT bytes
 *   - Varint21FrameDecoder            → max compressed / frame size
 *   - ServerboundCustomPayloadPacket  → max plugin-channel payload
 *
 * We replicate this on Paper/Spigot by:
 *   1. Injecting a Netty ChannelDuplexHandler (LimitOverrideHandler) into
 *      every connection's pipeline to suppress size-overflow exceptions.
 *   2. Using PacketEvents listeners to drop oversized plugin-message packets
 *      instead of kicking the player.
 *   3. Raising the NBT quota reflectively on the connection object.
 */
public class PacketFixerModule extends AbstractModule {

    private static final String NETTY_HANDLER = "packetfix_limit_override";

    private PacketListenerAbstract peListener;

    public PacketFixerModule(PacketFixPlugin plugin) { super(plugin); }

    @Override public String getConfigKey() { return "packet-fixer"; }
    @Override public String getName()      { return "Packet-Fixer (TonimatasDEV port)"; }

    // -----------------------------------------------------------------------
    @Override
    public void enable() {
        final int maxNbt     = plugin.getConfig().getInt("packet-fixer.max-nbt-bytes",       67108864);
        final int maxComp    = plugin.getConfig().getInt("packet-fixer.max-compressed-bytes", 67108864);
        final int maxPacket  = plugin.getConfig().getInt("packet-fixer.max-packet-bytes",    134217728);
        final int maxPayload = plugin.getConfig().getInt("packet-fixer.max-payload-bytes",   33554432);
        final boolean fixVar = plugin.getConfig().getBoolean("packet-fixer.fix-varint-overflow", true);

        // Inject into already-online players (e.g. after /packetfix reload)
        for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
            injectNettyHandler(p, maxNbt, maxComp, maxPacket);
        }

        // PacketEvents listener: inject on login + drop oversized payloads
        peListener = new PacketListenerAbstract(PacketListenerPriority.LOWEST) {

            @Override
            public void onUserLogin(com.github.retrooper.packetevents.event.UserLoginEvent event) {
                // New connection — inject Netty handler
                Object chObj = event.getUser().getChannel();
                if (!(chObj instanceof Channel)) return;
                Channel ch = (Channel) chObj;
                if (ch != null && ch.pipeline().get(NETTY_HANDLER) == null) {
                    ch.pipeline().addBefore("decoder", NETTY_HANDLER,
                            new LimitOverrideHandler(plugin, maxNbt, maxComp, maxPacket));
                    plugin.debug("Injected LimitOverrideHandler for " + event.getUser().getName());
                }
            }

            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
                    try {
                        WrapperPlayClientPluginMessage msg = new WrapperPlayClientPluginMessage(event);
                        byte[] data = msg.getData();
                        if (data != null && data.length > maxPayload) {
                            plugin.debug("Dropped oversized plugin-message (" + data.length
                                    + " bytes) from " + event.getUser().getName());
                            event.setCancelled(true);
                            countFix();
                        }
                    } catch (Exception ignored) {}
                }
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(peListener);

        plugin.debug("PacketFixerModule enabled — NBT=" + maxNbt
                + " comp=" + maxComp + " packet=" + maxPacket + " payload=" + maxPayload);
    }

    @Override
    public void disable() {
        if (peListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(peListener);
            peListener = null;
        }
        // Eject from all online players
        for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
            ejectNettyHandler(p);
        }
    }

    // -----------------------------------------------------------------------
    //  Netty injection helpers
    // -----------------------------------------------------------------------

    private void injectNettyHandler(org.bukkit.entity.Player player, int maxNbt, int maxComp, int maxPacket) {
        try {
            Channel ch = getChannel(player);
            if (ch == null || ch.pipeline().get(NETTY_HANDLER) != null) return;
            try {
                ch.pipeline().addBefore("decoder", NETTY_HANDLER,
                        new LimitOverrideHandler(plugin, maxNbt, maxComp, maxPacket));
            } catch (Exception e) {
                ch.pipeline().addFirst(NETTY_HANDLER,
                        new LimitOverrideHandler(plugin, maxNbt, maxComp, maxPacket));
            }
        } catch (Exception e) {
            plugin.debug("injectNettyHandler failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void ejectNettyHandler(org.bukkit.entity.Player player) {
        try {
            Channel ch = getChannel(player);
            if (ch != null && ch.pipeline().get(NETTY_HANDLER) != null) {
                ch.pipeline().remove(NETTY_HANDLER);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Obtain the Netty Channel for a player.
     * PacketEvents 2.x exposes this via ChannelHelper / User#getChannel.
     */
    public static Channel getChannel(org.bukkit.entity.Player player) {
        try {
            com.github.retrooper.packetevents.protocol.player.User user =
                    PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user != null) {
                Object ch = user.getChannel();
                if (ch instanceof Channel) return (Channel) ch;
            }
        } catch (Exception ignored) {}
        // Fallback: reflection
        return getChannelReflect(player);
    }

    private static Channel getChannelReflect(org.bukkit.entity.Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object conn   = findField(handle, new String[]{"connection","playerConnection","b"});
            if (conn == null) return null;
            Object netMgr = findField(conn, new String[]{"connection","networkManager","network","a"});
            if (netMgr == null) return null;
            Object ch     = findField(netMgr, new String[]{"channel","k","m"});
            if (ch instanceof Channel) return (Channel) ch;
        } catch (Exception ignored) {}
        return null;
    }

    private static Object findField(Object obj, String[] names) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException ignored) {}
                catch (Exception e) { return null; }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    //  Inner Netty handler
    // -----------------------------------------------------------------------

    private static class LimitOverrideHandler extends ChannelDuplexHandler {

        private final PacketFixPlugin plugin;
        private final int maxNbt, maxComp, maxPacket;
        private boolean nbtPatched = false;

        LimitOverrideHandler(PacketFixPlugin plugin, int maxNbt, int maxComp, int maxPacket) {
            this.plugin    = plugin;
            this.maxNbt    = maxNbt;
            this.maxComp   = maxComp;
            this.maxPacket = maxPacket;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!nbtPatched) { tryPatchNbt(ctx); nbtPatched = true; }
            super.channelRead(ctx, msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            String m = cause.getMessage() != null ? cause.getMessage() : "";
            if (m.contains("VarInt") || m.contains("VarLong")
                    || m.contains("wider than") || m.contains("Unable to fit")
                    || m.contains("Badly compressed") || m.contains("too big")
                    || m.contains("maximum protocol size") || m.contains("Packet too big")) {
                plugin.debug("Suppressed packet error [" + ctx.channel().remoteAddress() + "]: " + m);
                return;
            }
            super.exceptionCaught(ctx, cause);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                super.write(ctx, msg, promise);
            } catch (Exception e) {
                String m = e.getMessage() != null ? e.getMessage() : "";
                if (m.contains("Packet too big") || m.contains("maximum protocol size")) {
                    plugin.debug("Dropped oversized outbound packet: " + m);
                    promise.setSuccess();
                } else throw e;
            }
        }

        private void tryPatchNbt(ChannelHandlerContext ctx) {
            try {
                Object decoder = ctx.pipeline().get("decoder");
                if (decoder == null) return;
                for (Field f : allFields(decoder.getClass())) {
                    f.setAccessible(true);
                    Object val = f.get(decoder);
                    if (val == null) continue;
                    for (Field inner : allFields(val.getClass())) {
                        if (inner.getType() == long.class &&
                                (inner.getName().equals("quota") || inner.getName().equals("maxBytes")
                                        || inner.getName().equals("remaining"))) {
                            inner.setAccessible(true);
                            long old = inner.getLong(val);
                            if (old > 0 && old <= 2_097_152L) {
                                inner.setLong(val, maxNbt);
                                plugin.debug("NBT quota patched: " + old + " → " + maxNbt);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                plugin.debug("NBT patch skipped: " + e.getMessage());
            }
        }

        private static Field[] allFields(Class<?> c) {
            List<Field> list = new ArrayList<>();
            for (; c != null && c != Object.class; c = c.getSuperclass())
                list.addAll(Arrays.asList(c.getDeclaredFields()));
            return list.toArray(new Field[0]);
        }
    }
}
