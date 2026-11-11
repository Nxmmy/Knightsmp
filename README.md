# Mods For KnightSMP

All-in-one Paper plugin: KnightTP, bounties, iron/diamond economy, shop, kit shop, dynamic market, combat log.
Server plugin only - players need **no** client mods. Works for Java and Bedrock (Geyser/Floodgate): all menus are plain chest GUIs,
no sign/anvil input, no right-click-only actions.

## Requirements
- Paper 26.3 (experimental build at time of writing), Java 25
- Optional: ViaVersion, ViaBackwards, Geyser-Spigot, Floodgate (soft-depended, never touched)

## Build
```
gradle build          # or ./gradlew build if you add a wrapper
```
Jar: `build/libs/ModsForKnightSMP-1.0.0.jar`. Requires JDK 25 and internet access to `repo.papermc.io`.
If `26.3.build.+` is not yet in Paper's Maven repo, use the newest available `26.x.build.+` for compilation.

## Install
1. Stop the server, drop the jar into `plugins/`, start once to generate `config.yml`, `shop.yml`, `kits.yml`, `market.yml`.
2. Edit the configs, then `/knight reload`.
3. Run `/setspawn` where you want `/spawn` to lead.

## Commands
Players: /tpa /tpaccept /tpdeny /home /sethome /delhome /back /spawn /rtp /bounty /money [player] /money deposit/withdraw <iron/diamonds> <n/all>
/pay <player> <iron/diamonds> <amount> /shop /shopkits /sell
Admin: /knight reload / give / take / setbounty / resetbounty / market reload / market refresh / setspawn

## Economy model
Each player has a persistent iron balance and diamond balance (SQLite). Physical ingots/diamonds are converted with deposit/withdraw
(GUI buttons or `/money deposit/withdraw`). Config `economy.spend-from-inventory` lets purchases use loose items after the balance;
`economy.payout-mode` chooses whether rewards arrive as balance or items. Iron and diamonds never convert into each other.
Only plain, unrenamed, unenchanted items count as currency/sellable.

## Notes
- WANTED #1 is shown as a name-tag/tab prefix via a scoreboard team (main scoreboard); add LuckPerms commands in `wanted-role.on-gain-commands`.
- Spears in the Endgame kit are skipped automatically (console warning) on versions without them.
