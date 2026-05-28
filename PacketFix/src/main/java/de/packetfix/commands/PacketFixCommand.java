package de.packetfix.commands;

import de.packetfix.PacketFixPlugin;
import de.packetfix.module.AbstractModule;
import de.packetfix.module.impl.ChunkBanFixModule;
import de.packetfix.module.impl.PingLimiterModule;
import de.packetfix.util.PlatformUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PacketFixCommand implements CommandExecutor, TabCompleter {

    private final PacketFixPlugin plugin;

    public PacketFixCommand(PacketFixPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("packetfix.admin")) {
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length == 0) { sendHelp(sender); return true; }

        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            plugin.reload();
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.GREEN
                    + "Configuration reloaded. "
                    + plugin.getModuleManager().getActiveCount() + " modules active.");

        } else if (sub.equals("status")) {
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.AQUA
                    + "PacketFix v" + plugin.getDescription().getVersion()
                    + " — " + PlatformUtil.describe() + " — Status:");
            if (plugin.getModuleManager().getActive().isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "  No modules active.");
            } else {
                for (AbstractModule m : plugin.getModuleManager().getActive()) {
                    sender.sendMessage(
                            ChatColor.DARK_AQUA + "  » " + ChatColor.WHITE + m.getName()
                                    + ChatColor.GRAY + " — "
                                    + ChatColor.YELLOW + m.getFixCount()
                                    + ChatColor.GRAY + " fixes");
                }
            }

        } else if (sub.equals("debug")) {
            boolean on = !plugin.getConfig().getBoolean("logging.debug", false);
            plugin.getConfig().set("logging.debug", on);
            plugin.saveConfig();
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.YELLOW
                    + "Debug logging: " + (on ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));

        } else if (sub.equals("chunkban")) {
            handleChunkBan(sender, args);

        } else if (sub.equals("ping")) {
            handlePing(sender, args);

        } else {
            sendHelp(sender);
        }
        return true;
    }

    // -----------------------------------------------------------------------
    //  Chunk ban sub-commands
    // -----------------------------------------------------------------------

    private void handleChunkBan(CommandSender sender, String[] args) {
        ChunkBanFixModule module = getChunkBanModule();
        if (module == null) {
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED
                    + "Chunk-Ban-Fix module is not active.");
            return;
        }

        if (args.length < 2) { sendChunkBanHelp(sender); return; }

        String sub = args[1].toLowerCase();
        if (sub.equals("list")) {
            Set<String> chunks = module.getBannedChunks();
            if (chunks.isEmpty()) {
                sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.GRAY + "No chunks are banned.");
                return;
            }
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.AQUA
                    + "Banned chunks (" + chunks.size() + "):");
            int i = 1;
            for (String key : chunks) {
                String[] parts = key.split(":");
                if (parts.length == 3) {
                    sender.sendMessage(ChatColor.DARK_AQUA + "  " + i++ + ". "
                            + ChatColor.WHITE + "World: " + parts[0]
                            + ChatColor.GRAY + " X=" + parts[1] + " Z=" + parts[2]);
                }
            }

        } else if (sub.equals("add")) {
            int[] coords = resolveChunkCoords(sender, args, 2);
            if (coords == null) return;
            String world = resolveWorld(sender, args, 2);
            if (world == null) return;
            boolean added = module.banChunk(world, coords[0], coords[1]);
            sender.sendMessage(PacketFixPlugin.PREFIX
                    + (added
                        ? ChatColor.GREEN + "Chunk " + world + ":" + coords[0] + ":" + coords[1] + " has been banned."
                        : ChatColor.YELLOW + "Chunk is already banned."));

        } else if (sub.equals("remove") || sub.equals("unban")) {
            int[] coords = resolveChunkCoords(sender, args, 2);
            if (coords == null) return;
            String world = resolveWorld(sender, args, 2);
            if (world == null) return;
            boolean removed = module.unbanChunk(world, coords[0], coords[1]);
            sender.sendMessage(PacketFixPlugin.PREFIX
                    + (removed
                        ? ChatColor.GREEN + "Chunk " + world + ":" + coords[0] + ":" + coords[1] + " has been unbanned."
                        : ChatColor.YELLOW + "Chunk was not banned."));

        } else if (sub.equals("check")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED + "Only for players.");
                return;
            }
            Player player = (Player) sender;
            int cx = player.getLocation().getBlockX() >> 4;
            int cz = player.getLocation().getBlockZ() >> 4;
            String world = player.getWorld().getName();
            String key = world + ":" + cx + ":" + cz;
            boolean banned = module.getBannedChunks().contains(key);
            sender.sendMessage(PacketFixPlugin.PREFIX
                    + "Current chunk " + ChatColor.YELLOW + key
                    + ChatColor.RESET + ": "
                    + (banned ? ChatColor.RED + "BANNED" : ChatColor.GREEN + "SAFE"));

        } else {
            sendChunkBanHelp(sender);
        }
    }

    // -----------------------------------------------------------------------
    //  Ping sub-commands
    // -----------------------------------------------------------------------

    private void handlePing(CommandSender sender, String[] args) {
        PingLimiterModule module = getPingModule();
        if (module == null) {
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED
                    + "Ping-Limiter module is not active.");
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.AQUA
                    + "Ping of all players (" + Bukkit.getOnlinePlayers().size() + "):");
            Bukkit.getOnlinePlayers().stream()
                    .sorted(Comparator.comparingLong(module::getPing).reversed())
                    .forEach(p -> sender.sendMessage(
                            ChatColor.DARK_AQUA + "  » " + ChatColor.WHITE + p.getName()
                                    + ChatColor.GRAY + ": " + module.getColouredPing(p)));
            return;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED
                        + "Player '" + args[1] + "' not found.");
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED
                    + "Usage: /packetfix ping <player|list>");
            return;
        }

        sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.WHITE + target.getName()
                + ChatColor.GRAY + " — Ping: " + module.getColouredPing(target)
                + ChatColor.GRAY + "  (Bukkit: §f" + PlatformUtil.getPing(target) + "ms§7)");
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private int[] resolveChunkCoords(CommandSender sender, String[] args, int offset) {
        if (args.length >= offset + 3) {
            try {
                int cx = Integer.parseInt(args[offset + 1]);
                int cz = Integer.parseInt(args[offset + 2]);
                return new int[]{cx, cz};
            } catch (NumberFormatException e) {
                sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED
                        + "Invalid chunk coordinates. Please provide integers.");
                return null;
            }
        }
        if (sender instanceof Player) {
            Player player = (Player) sender;
            return new int[]{
                    player.getLocation().getBlockX() >> 4,
                    player.getLocation().getBlockZ() >> 4
            };
        }
        sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED
                + "Usage: /packetfix chunkban add <world> <chunkX> <chunkZ>");
        return null;
    }

    private String resolveWorld(CommandSender sender, String[] args, int offset) {
        if (args.length >= offset + 3) {
            String worldName = args[offset];
            if (Bukkit.getWorld(worldName) == null) {
                sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED
                        + "World '" + worldName + "' not found.");
                return null;
            }
            return worldName;
        }
        if (sender instanceof Player) return ((Player) sender).getWorld().getName();
        sender.sendMessage(PacketFixPlugin.PREFIX + ChatColor.RED + "World name required.");
        return null;
    }

    private ChunkBanFixModule getChunkBanModule() {
        for (AbstractModule m : plugin.getModuleManager().getActive()) {
            if (m instanceof ChunkBanFixModule) return (ChunkBanFixModule) m;
        }
        return null;
    }

    private PingLimiterModule getPingModule() {
        for (AbstractModule m : plugin.getModuleManager().getActive()) {
            if (m instanceof PingLimiterModule) return (PingLimiterModule) m;
        }
        return null;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(PacketFixPlugin.PREFIX + ChatColor.AQUA + "Available commands:");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix reload            " + ChatColor.GRAY + "— Reload configuration");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix status            " + ChatColor.GRAY + "— Module statistics");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix debug             " + ChatColor.GRAY + "— Toggle debug logging");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix chunkban list     " + ChatColor.GRAY + "— List all banned chunks");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix chunkban add      " + ChatColor.GRAY + "— Ban a chunk (use your position)");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix chunkban remove   " + ChatColor.GRAY + "— Unban a chunk");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix chunkban check    " + ChatColor.GRAY + "— Check your current chunk");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix ping [player]     " + ChatColor.GRAY + "— Show a player's ping");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix ping list         " + ChatColor.GRAY + "— List all players sorted by ping");
    }

    private void sendChunkBanHelp(CommandSender s) {
        s.sendMessage(PacketFixPlugin.PREFIX + ChatColor.AQUA + "Chunk ban commands:");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix chunkban list");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix chunkban add [world] [chunkX] [chunkZ]");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix chunkban remove [world] [chunkX] [chunkZ]");
        s.sendMessage(ChatColor.DARK_AQUA + "  /packetfix chunkban check");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1)
            return Arrays.asList("reload", "status", "debug", "chunkban", "ping");
        if (args.length == 2 && args[0].equalsIgnoreCase("chunkban"))
            return Arrays.asList("list", "add", "remove", "check");
        if (args.length == 2 && args[0].equalsIgnoreCase("ping")) {
            List<String> names = new ArrayList<>();
            names.add("list");
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("chunkban")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            return Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
