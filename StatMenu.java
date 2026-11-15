package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.stats.StatsManager.Extra;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Money;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /stat - every number comes from saved data (account row, transaction history, saved playtime). */
public final class StatMenu extends Menu {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private final UUID targetId;
    private final Extra extra;

    private StatMenu(KnightPlugin plugin, Player viewer, UUID targetId, Extra extra) {
        super(plugin, viewer, 5, "&5&lKnightSMP &8» &7Statistics");
        this.targetId = targetId; this.extra = extra;
    }

    /** Loads the data off-thread, then opens the menu. {@code target} must already have an account. */
    public static void show(KnightPlugin plugin, Player viewer, Account target) {
        plugin.stats.load(target.id).whenComplete((extra, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            if (ex != null) { plugin.send(viewer, "db-error"); return; }
            new StatMenu(plugin, viewer, target.id, extra).open();
        }));
    }

    private static String duration(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (d > 0 || h > 0) sb.append(h).append("h ");
        sb.append(m).append("m");
        return sb.toString();
    }

    @Override
    protected void build() {
        frame();
        Account a = plugin.economy.cached(targetId);
        if (a == null) { set(22, Items.make(Material.BARRIER, "&cNo data"), null); return; }
        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(targetId);
        long kd100 = a.deaths == 0 ? a.kills * 100 : Math.round(a.kills * 100.0 / a.deaths);
        String kd = String.format(Locale.US, "%d.%02d", kd100 / 100, kd100 % 100);
        long earned = Math.max(0, extra.sum("SELL")), spent = Math.max(0, -(extra.sum("SHOP") + extra.sum("KIT") + extra.sum("SHOP_REFUND") + extra.sum("KIT_REFUND")));
        long paid = Math.max(0, -extra.sum("PAY_SENT")), received = Math.max(0, extra.sum("PAY_RECEIVED")), bounties = Math.max(0, extra.sum("BOUNTY"));

        set(4, Items.head(op, "&d&l" + a.name, List.of("&8First joined: &f" + (op.getFirstPlayed() > 0 ? DATE.format(Instant.ofEpochMilli(op.getFirstPlayed())) : "unknown")), true), null);
        set(19, Items.make(Material.GOLD_INGOT, "&6&lMoney", List.of("&8Balance: " + Money.fmt(a.dollars), "&8Richest rank: &f#" + Text.num(extra.moneyRank())), false), null);
        set(20, Items.make(Material.IRON_SWORD, "&c&lKills", List.of("&8PvP kills: &f" + Text.num(a.kills), "&8Kills rank: &f#" + Text.num(extra.killsRank())), false), null);
        set(21, Items.make(Material.SKELETON_SKULL, "&7&lDeaths", List.of("&8Deaths: &f" + Text.num(a.deaths), "&8K/D ratio: &f" + kd), false), null);
        set(22, Items.make(Material.CLOCK, "&b&lPlaytime", List.of("&8Total: &f" + duration(plugin.stats.playtime(a)), "&8Counted by KnightSMP since it started tracking."), false), null);
        set(23, Items.make(Material.BLAZE_POWDER, "&e&lKill Streak", List.of("&8Current: &f" + Text.num(a.streak), "&8Best: &f" + Text.num(a.bestStreak)), false), null);
        set(24, Items.make(Material.NETHER_STAR, "&5&lBounty", List.of("&8Current bounty: " + Money.fmt(a.bounty), "&8Claimed from kills: " + Money.fmt(bounties)), false), null);
        set(25, Items.make(Material.EMERALD, "&a&lTrading", List.of("&8Earned selling: " + Money.fmt(earned), "&8Spent in shops: " + Money.fmt(spent),
                "&8Payments sent: " + Money.fmt(paid), "&8Payments received: " + Money.fmt(received)), false), null);
        set(40, Items.make(Material.BARRIER, "&cClose"), e -> close());
    }
}
