# Mods For KnightSMP 2.0.0

Anarchy-style Paper plugin: **Dollar ($) economy**, /shop (blocks, nature, redstone, combat, tools, food, enchantment books, potions), /shopkits, /sell with a 24-hour dynamic market,
bounties with a WANTED #1 role, a Money/Bounty/Kills sidebar, **/stat**, **/pay with a receiver animation**, `/home`, and combat logging. Server-side only; Java and Bedrock (Geyser/Floodgate) use the same plain chest menus.
There is **no random teleport** and no /tpa, /spawn, /back (vanilla `/tp` is untouched).

Requirements: Paper 26.3, Java 25. Optional: ViaVersion, ViaBackwards, Geyser-Spigot, Floodgate. Build output: `ModsForKnightSMP-2.0.0.jar`.

## What's new in 2.0.0
* **/stat [player]** - dark GUI with money, kills, deaths, K/D, playtime, kill streak (current/best), bounty, richest/kills rank, and trading totals (earned selling, spent in shops, payments sent/received). Everything is read from saved data; deaths, streaks and playtime are newly tracked and stored in SQLite.
* **/pay <player> <amount>** (already atomic) now plays a **payment animation** for the receiver (below). `/payanim [on/off]` lets a player turn the popup off.
* **Blocks and Redstone shops cleaned up**: unobtainable/admin blocks (command blocks, bedrock, barriers, structure blocks, jigsaw, end portal frames, spawners, infested blocks...) are excluded, only real blocks are listed (no ingots, gems or dusts), and **anything /sell already buys is not repeated** (the /sell list in market.yml is read when the shop loads).
* **Item-by-item prices in every category** from one model (see Pricing). Redstone components are derived from their ingredients and crafting steps.
* **Combat**: Trident $350; ALL armor trim templates free ($0); sets for leather/chainmail/golden/iron/diamond/netherite (+copper where the server has it); spectral arrows, horse and wolf armor, spears, mace, more.
* Everything from the previous update is kept: Dollar economy, HUD, homes, starting balance $150 once, exact kit prices, exact ore/TNT/combat prices, enchantment books, potions, safe transactions, combat log.

## Payment animation (receiver)
When a payment is committed the receiver sees, in order: a centred popup (their own head in a golden ring with rays that grow outward, amount and sender underneath), a shimmering rarity title (COMMON / UNCOMMON / RARE / EPIC / **LEGENDARY**, set by the amount in `payment-animation.rarities`), a growing ray line in the action bar, orbiting golden particles and a level-up sound. It runs about 3.5 seconds, one clean notification per payment; payments that arrive meanwhile wait in line (max 5, bursts are merged into one summary). The popup is skipped while the player is in combat or has another menu open (title, particles and sounds still play). It uses only chest menus, titles, the action bar, particles and sounds, so Java and Bedrock (Geyser) players get the same effect. A failing animation can never affect the payment.
Limitation: vanilla clients cannot draw custom images or big graphics without a resource pack, so the "ring and rays" are built from menu items rather than artwork.

## Commands
| Command | What it does |
|---|---|
| `/money` `/money <player>` `/pay <player> <amount>` `/baltop` `/payanim [on/off]` | Balance, wallet menu, payments, richest players, payment popup on/off |
| `/stat [player]` | Statistics menu |
| `/shop` `/shopkits` `/sell` `/bounty` | Shop, kit shop, market, bounty leaderboard |
| `/sethome` `/home` `/deletehome` | Home (one per player) |
| `/moneyadmin give/take/set <player> <amount>`, `/moneyadmin migrate [confirm]` | Admin money / old-currency migration |
| `/knight reload`, `setbounty`, `resetbounty`, `market reload/refresh` | Admin |

## Database changes
New columns on `accounts` (added automatically, after a `.bak` backup, nothing is deleted): `dollars`, `migrated`, `kills`, `deaths`, `streak`, `best_streak`, `playtime`, `anim_off`. Existing players keep every balance, bounty, home and Iron/Diamond value; new statistics start at 0 (there is no earlier history to count). Convert old Iron/Diamond balances with `/moneyadmin migrate` after setting `migration.iron-rate` / `diamond-rate`.

## HUD limitation (left side)
Vanilla Minecraft draws the server sidebar on the **right** edge of the screen, and Geyser maps it to Bedrock's right-hand sidebar. Placing it on the left needs a client mod or a custom resource pack, so the closest compatible option is used: the standard sidebar (score numbers hidden). It needs no client mods, works for Java and Bedrock, and is event-driven (no repeating task).

## Shop pricing
* **Exact (from the owner's specification):** coal 16/$15, raw iron 8/$25, iron ingot 8/$25, gold 8/$20, copper 16/$15, lapis 32/$50, redstone 16/$35, diamonds 16/$185, emerald 4/$15, ancient debris 2/$750, netherite scrap 2/$500, TNT 32/$75; iron/diamond/netherite armor sets $150/$250/$3,500; iron/diamond/netherite sword $35/$450/$1,400; armor trims free; kits as above.
* **Everything else** uses a consistent scale anchored on those numbers (iron ~$3, diamond ~$11.6, emerald ~$3.75): common blocks $0.1-$1 each, rare blocks (beacon, dragon egg, netherite block, sculk catalysts, heads) hundreds to thousands, non-craftable rarities (elytra, totem, enchanted golden apple, nether star) priced as premium items.
* **Enchantment books**: price = base x (1 + 0.9 x (level-1)). Common table enchants ~$12-$30 at level I, villager/loot-only enchants $40-$70, treasure and structure-only books $110-$190, Mending $450.
* **Potions**: base price by ingredient rarity (sugar $25 ... ghast tear $70, phantom membrane $80, turtle shell $120); long x1.5, strong x1.8, splash x1.3, lingering x3.0 (dragon's breath).
* **Redstone** (examples, all derived): torch $11 per 4 (redstone + stick), repeater $21 per 2, comparator $13, piston $16 per 2, sticky piston $31 per 2, hopper $23, crafter $35, redstone block $25. Markup is x1.25 for one crafting step, x1.35 for two, x1.45 for three or more.
* **Market**: sell value = 35% of the shop price per unit; even at the maximum x2.0 daily multiplier it stays below the buy price, so buy-and-resell never profits.
Everything is in `shop.yml`, `blocks.yml`, `enchants.yml`, `potions.yml`, `kits.yml`, `market.yml` and reloads with `/knight reload`. `gen_economy.py` regenerates all six from one table.

## Money safety
Every money movement is one SQL transaction (conditional debit, history row, any extra step); the in-memory balance changes only after the commit. /sell commits payment+stock first, then removes items (reversing the payment if the items are gone); shop and kit purchases charge first, then deliver (refund if delivery is impossible). One transaction per player at a time. Bounty claims are compare-and-set, so a bounty pays once.

## Upgrading an old database
The plugin adds missing columns (`dollars`, `migrated`, `kills`) after writing a `.bak` copy, and keeps old Iron/Diamond values, bounties, homes and UUIDs. Old balances convert only when you set `migration.iron-rate`/`diamond-rate` in `config.yml` and run `/moneyadmin migrate confirm` (preview first). Existing accounts do not receive the $150 starting balance.

## Build
`gradle build` with JDK 25; GitHub Actions uploads `ModsForKnightSMP-1.0.0.jar`. The flat repository layout uses the included `build.gradle.kts`.

## Notes
* The Nature shop still sells dirt, sand, logs and leaves that /sell also buys (the duplicate rule was written for Blocks and Redstone). To remove those too, add `exclude-sold: true` to the `nature_*` categories in shop.yml.
* Redstone Dust is hand-listed in the Redstone shop on request even though /sell buys it; it is the one deliberate overlap.

## Tests (run by hand; they do not replace testing on a real server)
* `python3 sql_tests.py Sql.java` - the plugin's real SQL on SQLite (transactions, sell/buy/refund, no negative balances, bounty single-claim, kills, homes, restart persistence, upgrade/migration, starting balance once).
* `python3 static_checks.py .` - exact prices/quantities, command and permission consistency, rule coverage of ~800 block names, enchantment/potion coverage, market-vs-shop invariants.
