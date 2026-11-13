# Mods For KnightSMP

Anarchy-style Paper plugin: **Dollar ($) economy**, a full /shop (every block, enchantment book and potion), /shopkits, /sell with a 24-hour dynamic market,
bounties with a WANTED #1 role, kill/money/bounty sidebar HUD, `/home`, and combat logging. Server-side only; Java and Bedrock (Geyser/Floodgate) use the same plain chest menus.
There is **no random teleport** and no /tpa, /spawn, /back (vanilla `/tp` is untouched).

Requirements: Paper 26.3, Java 25. Optional: ViaVersion, ViaBackwards, Geyser-Spigot, Floodgate.

## Changelog (this update)
* **HUD**: sidebar showing Money, Bounty, Kills; updates only when a value changes (see limitation below). `hud.*` in config.
* **Kill counter** (persistent, counts valid PvP kills only) shown in the HUD.
* **Starting balance $150**, given once at account creation. Existing accounts are never topped up or reset.
* **Prices rebalanced** to a harder economy; every price you specified is applied EXACTLY (kits, ores+TNT, armor sets, swords, free trims).
* **Kits**: Starter free, Beginner $75, Intermediate $150, Advanced $1,500, Endgame $5,000 (contents unchanged).
* **Complete block shop**: every obtainable block the server knows is sold, auto-priced by `blocks.yml` rules and sorted into categories (Stone, Wood, Colored & Glass, Metal & Gems, Nether & End, Utility & Decor, Redstone, and five Nature categories).
* **Combat shop**: Full Iron $150 / Diamond $250 / Netherite $3,500 armor sets (all four pieces), swords, **armor trims free**.
* **Enchantment books**: every enchantment x every level, tiered prices (`enchants.yml`).
* **Potions**: every potion type as potion / splash / lingering, tiered prices (`potions.yml`).
* **/sethome, /home, /deletehome** (alias /delhome): saved in SQLite, validated, countdown cancelled by movement/damage/combat, /home blocked in combat.
* GUI: category menu is paged; purchase screen for every item; items can't be taken out of any menu.

## Commands
| Command | What it does |
|---|---|
| `/money` `/money <player>` `/pay <player> <amount>` `/baltop` | Balance, wallet menu, payments, richest players |
| `/shop` `/shopkits` `/sell` `/bounty` | Shop, kit shop, market, bounty leaderboard |
| `/sethome` `/home` `/deletehome` | Home (one per player) |
| `/moneyadmin give/take/set <player> <amount>`, `/moneyadmin migrate [confirm]` | Admin money / old-currency migration |
| `/knight reload`, `setbounty`, `resetbounty`, `market reload/refresh` | Admin |

## HUD limitation (left side)
Vanilla Minecraft draws the server sidebar on the **right** edge of the screen, and Geyser maps it to Bedrock's right-hand sidebar. Placing it on the left needs a client mod or a custom resource pack, so the closest compatible option is used: the standard sidebar (score numbers hidden). It needs no client mods, works for Java and Bedrock, and is event-driven (no repeating task).

## Shop pricing
* **Exact (from the owner's specification):** coal 16/$15, raw iron 8/$25, iron ingot 8/$25, gold 8/$20, copper 16/$15, lapis 32/$50, redstone 16/$35, diamonds 16/$185, emerald 4/$15, ancient debris 2/$750, netherite scrap 2/$500, TNT 32/$75; iron/diamond/netherite armor sets $150/$250/$3,500; iron/diamond/netherite sword $35/$450/$1,400; armor trims free; kits as above.
* **Everything else** uses a consistent scale anchored on those numbers (iron ~$3, diamond ~$11.6, emerald ~$3.75): common blocks $0.1-$1 each, rare blocks (beacon, dragon egg, netherite block, sculk catalysts, heads) hundreds to thousands, non-craftable rarities (elytra, totem, enchanted golden apple, nether star) priced as premium items.
* **Enchantment books**: price = base x (1 + 0.9 x (level-1)). Common table enchants ~$12-$30 at level I, villager/loot-only enchants $40-$70, treasure and structure-only books $110-$190, Mending $450.
* **Potions**: base price by ingredient rarity (sugar $25 ... ghast tear $70, phantom membrane $80, turtle shell $120); long x1.5, strong x1.8, splash x1.3, lingering x3.0 (dragon's breath).
* **Market**: sell value = 35% of the shop price per unit; even at the maximum x2.0 daily multiplier it stays below the buy price, so buy-and-resell never profits.
Everything is in `shop.yml`, `blocks.yml`, `enchants.yml`, `potions.yml`, `kits.yml`, `market.yml` and reloads with `/knight reload`. `gen_economy.py` regenerates all six from one table.

## Money safety
Every money movement is one SQL transaction (conditional debit, history row, any extra step); the in-memory balance changes only after the commit. /sell commits payment+stock first, then removes items (reversing the payment if the items are gone); shop and kit purchases charge first, then deliver (refund if delivery is impossible). One transaction per player at a time. Bounty claims are compare-and-set, so a bounty pays once.

## Upgrading an old database
The plugin adds missing columns (`dollars`, `migrated`, `kills`) after writing a `.bak` copy, and keeps old Iron/Diamond values, bounties, homes and UUIDs. Old balances convert only when you set `migration.iron-rate`/`diamond-rate` in `config.yml` and run `/moneyadmin migrate confirm` (preview first). Existing accounts do not receive the $150 starting balance.

## Build
`gradle build` with JDK 25; GitHub Actions uploads `ModsForKnightSMP-1.0.0.jar`. The flat repository layout uses the included `build.gradle.kts`.

## Tests (run by hand; they do not replace testing on a real server)
* `python3 sql_tests.py Sql.java` - the plugin's real SQL on SQLite (transactions, sell/buy/refund, no negative balances, bounty single-claim, kills, homes, restart persistence, upgrade/migration, starting balance once).
* `python3 static_checks.py .` - exact prices/quantities, command and permission consistency, rule coverage of ~800 block names, enchantment/potion coverage, market-vs-shop invariants.
