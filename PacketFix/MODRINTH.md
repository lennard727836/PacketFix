# PacketFix

**PacketFix** is a Paper/Spigot server plugin that bundles eight targeted fixes for common packet-related issues — from crash chunks and ghost blocks to shield animation desync and high-ping auto-kick. Built on [PacketEvents 2.x](https://modrinth.com/plugin/packetevents) for reliable, version-agnostic packet interception.

---

## ✨ Features

### 📦 Packet Fixer
*Port of [PacketFixer by TonimatasDEV](https://modrinth.com/mod/packet-fixer)*

Raises the hard-coded Minecraft packet and NBT size limits that cause cryptic disconnect errors on perfectly valid clients:

- `Tried to read NBT tag that was too big` → NBT limit raised to **64 MB**
- `Badly compressed packet` → compression limit raised to **64 MB**
- `Attempted to send packet over maximum protocol size` → frame limit raised to **128 MB**
- `Payload may not be larger than X bytes` → custom payload limit raised to **32 MB**
- `VarInt / VarLong too big` → suppressed instead of kicking the player
- Connection timeouts → keep-alive window extended to **60 seconds**

---

### 🛡️ Shield Fixes
*Port of [Complete Shield Fixes by Walksy](https://modrinth.com/mod/complete-shield-fixes)*

Fixes two long-standing Minecraft bugs:

- **MC-105068** — Missing shield sounds: plays the correct block/break sound when a shield absorbs a hit or gets disabled by an axe
- **MC-238293** — Blocking animation desync: other players now correctly see your shield arm animation when you block, including the vanilla 5-tick activation delay

---

### 👻 Ghost Block Fix

Eliminates phantom blocks that appear only on the client:

- **Client-side** — resends the real server block state after a cancelled `BlockPlaceEvent`, `BlockBreakEvent`, or denied right-click interaction
- **Server-side** — broadcasts corrective block updates to nearby players after `BlockPhysicsEvent` and `BlockFromToEvent` (pistons, water flow, commands)
- Packet-level interception of `USE_ITEM_ON` and `PLAYER_DIGGING` catches edge-cases that don't fire Bukkit events

---

### 👊 Ghost Hit Fix

Removes the "ghost hit" visual — the red hurt flash and damage tilt that clients show for attacks the server cancelled (God mode, WorldGuard, invulnerability frames, etc.):

- Sends `ENTITY_STATUS` health-reset to all observers within 64 blocks
- Resends the victim's health and food bar to prevent HUD flicker
- Handles both fully cancelled damage events and zero-damage cases

---

### 🚫 Chunk Ban Fix

Protects players from **crash chunks** — region-file entries so malformed or oversized that loading them instantly disconnects the client. Griefers use these to permanently "ban" players by luring them near the chunk:

- Auto-detects oversized chunk packets (> 2 MB by default) and bans the coordinates
- Replaces banned chunks with empty (air) packets so the client loads safely
- Proximity guard: warns and teleports players approaching a known crash chunk
- Persists banned coordinates to `plugins/PacketFix/banned-chunks.txt`
- Admin commands: `/packetfix chunkban add|remove|list|check`

---

### ⚡ XP & Item Merge

Reduces entity and packet count in mob farms and mass-kill scenarios:

- **XP orb merging** — orbs spawning within a configurable radius and time window are merged into one orb, reducing `SPAWN_EXPERIENCE_ORB` packets dramatically
- **Item stack merging** — dropped items of the same type within range are merged up to the max stack size
- **Packet deduplication** — duplicate `SPAWN_EXPERIENCE_ORB` packets within a single tick are silently dropped

---

### 🔐 Exploit Fixes

Blocks well-known crash and grief exploits transmitted via packets:

| Fix | Description |
|-----|-------------|
| Illegal NBT | Drops items with dangerously nested NBT (depth > 512) |
| Book exploit | Blocks signing books with > 100 pages or > 1 024 chars/page |
| Chat overflow | Drops chat/command packets longer than 256 characters |
| Creative item hack | Blocks `CREATIVE_INVENTORY_ACTION` from non-creative players |
| Window-click crash | Drops `CLICK_WINDOW` with out-of-range slot indices |
| Invalid move | Drops move packets with impossible position deltas (> 100 blocks/tick) |
| Sign exploit | Drops `UPDATE_SIGN` with lines exceeding 80 characters |
| Interact spam | Throttles `USE_ENTITY` to 20 interactions per second |
| Transaction spam | Throttles `WINDOW_CONFIRMATION` to 100 packets per second |

---

### 📶 Ping Limiter

Monitors real player latency via `KEEP_ALIVE` round-trip measurement and automatically kicks players whose ping exceeds the configured threshold:

- Rolling sample window (default: 5 samples) for stable, spike-resistant readings
- Sustained-check requirement (default: 3 consecutive checks) before kicking
- Notifies online admins when a player is auto-kicked
- Falls back to Bukkit's `player.getPing()` until enough samples are collected
- Bypass permission: `packetfix.ping.bypass`
- Commands: `/packetfix ping [player|list]`

---

## 🔧 Commands

| Command | Description |
|---------|-------------|
| `/packetfix reload` | Reload configuration |
| `/packetfix status` | Show all active modules and fix counters |
| `/packetfix debug` | Toggle verbose debug logging |
| `/packetfix ping [player]` | Show a player's current ping |
| `/packetfix ping list` | List all players sorted by ping |
| `/packetfix chunkban list` | List all banned chunks |
| `/packetfix chunkban add [world] [x] [z]` | Ban a chunk (omit coords to use your position) |
| `/packetfix chunkban remove [world] [x] [z]` | Unban a chunk |
| `/packetfix chunkban check` | Check if your current chunk is banned |

**Permission:** `packetfix.admin` (default: op)

---

## 📋 Requirements

| Requirement | Version |
|-------------|---------|
| Server | Paper / Spigot 1.20.4+ |
| Java | 17+ |
| [PacketEvents](https://modrinth.com/plugin/packetevents) | 2.7.0+ |

> **Note:** PacketEvents must be installed as a separate plugin on your server. PacketFix lists it as a hard dependency and will not start without it.

---

## ⚙️ Configuration

Every module can be individually enabled or disabled in `config.yml`. All thresholds, radii, sounds, messages, and limits are configurable. See the [default config](https://github.com/your-org/PacketFix/blob/main/src/main/resources/config.yml) for a full annotated reference.

---

## 🛠️ Building from Source

```bash
git clone https://github.com/your-org/PacketFix
cd PacketFix
mvn package
# Output: target/PacketFix.jar
```

---

## 📜 Credits

- **[PacketFixer](https://modrinth.com/mod/packet-fixer)** by TonimatasDEV — original Fabric mod for packet size fixes
- **[Complete Shield Fixes](https://modrinth.com/mod/complete-shield-fixes)** by Walksy — original Fabric mod for MC-105068 and MC-238293
- **[PacketEvents](https://modrinth.com/plugin/packetevents)** by retrooper — the packet interception library that makes this plugin possible
