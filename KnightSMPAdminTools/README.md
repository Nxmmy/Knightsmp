# KnightSMPAdminTools

OP-only administration plugin for the KnightSMP Paper server (Minecraft 26.3, Java 25, Geyser + Floodgate friendly).
Independent of ModsForKnightSMP - it shares no code, config, or database with it.

> **Build status: NOT YET COMPILED.** The source was written in an environment with no Gradle and no network, so
> it has never been built against the Paper API or run on a server. Read `TESTING.md` for exactly what was and was not verified.

## Requirements
- Java 25 JDK, Gradle 9.1+ (needed for Java 25), internet access for the first build (downloads the Paper API).
- Paper 26.3 server. The SQLite driver is bundled in Paper, so no extra libraries are shaded.

## Build
```
gradle build          # or ./gradlew build if you generated a wrapper: gradle wrapper --gradle-version 9.1
```
Output: `build/libs/KnightSMPAdminTools.jar`. Drop it in `plugins/` and restart.

If dependency resolution fails, 26.3 may still be an alpha build: check https://repo.papermc.io for the exact
`paper-api` version and edit `build.gradle.kts` (currently `26.3.build.+`). If Paper changed an API name, the compiler
will point at the line; the most likely candidates are `ItemFlag.HIDE_ADDITIONAL_TOOLTIP`, `Attribute.MAX_HEALTH`,
`Chunk#getTileEntities(boolean)` and `World#getChunkIfLoaded`.

## Commands (all OP-only; console allowed for non-GUI commands)
| Command | Purpose |
|---|---|
| `/admin` (`/ksa`, `/adminmenu`) | Open the GUI. `/admin reload` reloads config |
| `/inventorycheck <username>` | Inspect/confiscate an ONLINE player's inventory |
| `/kick <player> <reason>` | Kick |
| `/ban <player> <reason>` / `/unban <player>` | Permanent ban / unban (works for offline names) |
| `/mute <player> <duration> <reason>` / `/unmute <player>` | Durations: `30m`, `2h`, `1d12h`, `7d`, `perm` |
| `/warn <player> <reason>` | Warning (stored in history) |
| `/history <player>` | Last 10 punishments |
| `/freeze <player>` / `/unfreeze <player>` | Freeze (memory only; survives relog, not restarts) |

## Security model
- Authorization is `Player#isOp()` only - permission nodes are never consulted, so permission plugins cannot grant access.
- `plugin.yml` deliberately has no `permission:` keys; otherwise Bukkit would reject non-OPs before the plugin could log them.
- Checked on: every command, every GUI open, every GUI click, and again inside every service action and at confiscation time.
- Denied attempts go to the `staff_log` table (action `ACCESS_DENIED`), the console, and online OPs.
- Tab completion returns nothing to non-OPs. Command blocks / RCON are denied; the console is trusted.
- OPs cannot be punished through the plugin while `protect-operators: true`.
- X-ray tools read server-side block data for the admin's GUI only. No packets are sent to anyone else and nothing alters what normal players see.

## Inventory inspection safety
The GUI only holds clones; every click/drag is cancelled. Items move only via the confiscate action, which re-reads the live slot,
requires it to be identical to what you saw, simulates that your inventory can hold it, then moves it on the main thread.
Anything unexpectedly unplaced is returned to the target. Each confiscation is logged with admin, target, and the serialized item
(`confiscations` table, `item_data` blob) so it can be restored. You cannot inspect yourself (that would duplicate items).

## Data
`plugins/KnightSMPAdminTools/staff.db` (SQLite): `staff_log`, `punishments`, `confiscations`.

## Geyser / Floodgate
Only standard chest inventories and left-clicks are used, and no Floodgate API is required. Bedrock names usually carry a prefix (default `.`),
so type it: `/ban .BedrockName reason`.

## Known limitations
- Scans (ore/containers) only look at already-loaded chunks near you and run on the main thread; keep radii modest.
- Inventory inspection is online-only. Freeze state is not persisted across restarts.
- DB access is synchronous (small local SQLite file in WAL mode) so bans/mutes take effect immediately.
