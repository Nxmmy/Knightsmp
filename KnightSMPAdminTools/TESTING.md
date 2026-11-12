# Testing status (honest record)

Environment used to write this: Ubuntu container, JRE/JDK 21 compiler module only, **no Gradle, no network, no Paper server**.

## Actually performed
| Check | Result |
|---|---|
| `javac` parse of all 33 source files | No syntax errors. Remaining errors are only "cannot find symbol / package does not exist" for Paper/Bukkit/Adventure classes, which were not available. |
| Real compile of Paper-independent classes (`TimeUtil`, `db/*`) | Compiled OK (Java 21 compiler). |
| `TimeUtil` duration parse/format checks (9 cases, throwaway harness) | All 9 passed. |

## NOT performed (do these before trusting the plugin)
- Gradle build against Paper 26.3 / Java 25 (so no JAR was produced).
- Running on a server. Everything below is untested:
  1. Non-OP runs every command -> denied, row with ACCESS_DENIED in staff_log, no GUI opens.
  2. Non-OP with a permission-plugin node `*` -> still denied. De-op an admin with a menu open -> next click closes it.
  3. `/inventorycheck` on yourself -> refused; on offline name -> refused.
  4. Confiscate one item, full stack, armor piece, offhand; with a full inventory (expect "no room", nothing moves).
  5. Change an item in the target's slot while the confirm screen is open -> "item changed", nothing moves.
  6. Shift-click, number keys, drag, double-click collect in every menu -> nothing leaves the menus.
  7. Count total items of target+admin before/after confiscation (must be identical); check `confiscations` row.
  8. Ban -> kicked, reconnect refused; unban -> allowed. Timed mute expires. Restart server -> bans/mutes/logs persist.
  9. Ore scan with hidden-only on/off; chest scan; container view; teleport safety.
  10. Bedrock client via Geyser: open every menu, confirm screens, confiscation.
  11. `/admin reload`, Settings toggles persist to config.yml.
