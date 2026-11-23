package dev.knightsmp.mods.commands;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.EconomyManager.Row;
import dev.knightsmp.mods.gui.BountyMenu;
import dev.knightsmp.mods.gui.EconomyMenu;
import dev.knightsmp.mods.gui.KitMenu;
import dev.knightsmp.mods.gui.SellMenu;
import dev.knightsmp.mods.gui.ShopMainMenu;
import dev.knightsmp.mods.util.Money;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Commands implements CommandExecutor, TabCompleter {
    private final KnightPlugin plugin;
    public Commands(KnightPlugin plugin) { this.plugin = plugin; }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        plugin.send(s, "player-only");
        return null;
    }
    /** Strict whole-dollar parse; -1 for anything invalid (letters, decimals, negatives, overflow). */
    private static long amount(String s) {
        try { long v = Long.parseLong(s); return v > 0 ? v : -1; } catch (NumberFormatException e) { return -1; }
    }
    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }
    private boolean perm(CommandSender s, String node) {
        if (s.hasPermission("knightsmp.admin") || s.hasPermission(node)) return true;
        plugin.send(s, "no-permission");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "reset" -> {
                // strictly operators: the real OP status is checked every time the command runs
                if (!(sender instanceof Player p) || !p.isOp()) { plugin.send(sender, "no-permission"); return true; }
                dev.knightsmp.mods.gui.ResetMenu.show(plugin, p);
            }
            case "tps" -> { Player p = player(sender); if (p != null) plugin.spawn.tps(p); }
            case "settings" -> { Player p = player(sender); if (p != null) new dev.knightsmp.mods.gui.SettingsMenu(plugin, p).open(); }
            case "stat" -> stat(sender, a);
            case "payanim" -> payAnim(sender, a);
            case "sethome" -> { Player p = player(sender); if (p != null) plugin.home.setHome(p); }
            case "home" -> { Player p = player(sender); if (p != null) plugin.home.home(p); }
            case "deletehome" -> { Player p = player(sender); if (p != null) plugin.home.deleteHome(p); }
            case "bounty" -> { Player p = player(sender); if (p != null) BountyMenu.show(plugin, p); }
            case "shop" -> { Player p = player(sender); if (p != null) new ShopMainMenu(plugin, p).open(); }
            case "shopkits" -> { Player p = player(sender); if (p != null) new KitMenu(plugin, p).open(); }
            case "sell" -> { Player p = player(sender); if (p != null) new SellMenu(plugin, p).open(); }
            case "money" -> money(sender, a);
            case "pay" -> pay(sender, a);
            case "baltop" -> baltop(sender);
            case "moneyadmin" -> moneyAdmin(sender, a);
            case "knight" -> knight(sender, a);
            default -> { return false; }
        }
        return true;
    }

    // ---------- /money /pay /baltop ----------
    private void money(CommandSender s, String[] a) {
        if (a.length == 0) {
            Player p = player(s); if (p == null) return;
            plugin.send(p, "balance-self", "%balance%", Money.fmt(plugin.economy.balance(p)));
            new EconomyMenu(plugin, p).open();
            return;
        }
        if (!s.hasPermission("knightsmp.money.others") && !(s instanceof Player pl && pl.getName().equalsIgnoreCase(a[0]))) { plugin.send(s, "no-permission"); return; }
        OfflinePlayer op = resolve(a[0]);
        if (op == null) { plugin.send(s, "player-not-found"); return; }
        plugin.economy.withAccount(op.getUniqueId(),
                acc -> plugin.send(s, "balance-other", "%player%", acc.name, "%balance%", Money.fmt(acc.dollars)),
                () -> plugin.send(s, "player-not-found"));
    }

    private void pay(CommandSender s, String[] a) {
        Player p = player(s); if (p == null) return;
        if (a.length < 2) { plugin.send(p, "usage", "%usage%", "/pay <player> <amount>"); return; }
        OfflinePlayer op = resolve(a[0]);
        long n = amount(a[1]);
        if (op == null) { plugin.send(p, "player-not-found"); return; }
        if (n <= 0) { plugin.send(p, "invalid-amount"); return; }
        plugin.economy.withAccount(op.getUniqueId(), acc -> { if (p.isOnline()) plugin.economy.pay(p, acc, n, () -> {}); },
                () -> plugin.send(p, "player-not-found"));
    }

    private void baltop(CommandSender s) {
        int size = Math.max(1, Math.min(50, plugin.getConfig().getInt("economy.baltop-size", 10)));
        plugin.economy.top(size).whenComplete((rows, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) { plugin.send(s, "db-error"); return; }
            plugin.send(s, "baltop-header");
            int i = 1;
            for (Row r : rows) s.sendMessage(Text.c("&8#" + i++ + " &f" + r.name() + " &8- " + Money.fmt(r.value())));
        }));
    }

    // ---------- /stat and /payanim ----------
    private void stat(CommandSender s, String[] a) {
        Player me = player(s); if (me == null) return;
        if (a.length == 0) {
            Account acc = plugin.economy.get(me);
            if (acc == null) { plugin.send(me, "db-error"); return; }
            dev.knightsmp.mods.gui.StatMenu.show(plugin, me, acc);
            return;
        }
        if (!me.hasPermission("knightsmp.stat.others") && !me.getName().equalsIgnoreCase(a[0])) { plugin.send(me, "no-permission"); return; }
        OfflinePlayer op = resolve(a[0]);
        if (op == null) { plugin.send(me, "player-not-found"); return; }
        plugin.economy.withAccount(op.getUniqueId(), acc -> { if (me.isOnline()) dev.knightsmp.mods.gui.StatMenu.show(plugin, me, acc); },
                () -> plugin.send(me, "player-not-found"));
    }

    private void payAnim(CommandSender s, String[] a) {
        Player p = player(s); if (p == null) return;
        Account acc = plugin.economy.get(p);
        if (acc == null) { plugin.send(p, "db-error"); return; }
        boolean off;
        if (a.length > 0 && a[0].equalsIgnoreCase("on")) off = false;
        else if (a.length > 0 && a[0].equalsIgnoreCase("off")) off = true;
        else off = !acc.animOff;                               // no argument: toggle
        plugin.stats.setAnimOff(acc, off);
        plugin.send(p, off ? "payanim-off" : "payanim-on");
    }

    // ---------- /moneyadmin ----------
    private void moneyAdmin(CommandSender s, String[] a) {
        if (!perm(s, "knightsmp.admin.money")) return;
        if (a.length == 0) { plugin.send(s, "usage", "%usage%", "/moneyadmin <give/take/set/migrate> ..."); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("migrate")) { migrate(s, a); return; }
        if (!(sub.equals("give") || sub.equals("take") || sub.equals("set")) || a.length < 3) {
            plugin.send(s, "usage", "%usage%", "/moneyadmin <give/take/set> <player> <amount>"); return;
        }
        OfflinePlayer op = resolve(a[1]);
        if (op == null) { plugin.send(s, "player-not-found"); return; }
        long n;
        if (sub.equals("set")) { try { n = Long.parseLong(a[2]); } catch (NumberFormatException e) { n = -1; } if (n < 0) { plugin.send(s, "invalid-amount"); return; } }
        else { n = amount(a[2]); if (n <= 0) { plugin.send(s, "invalid-amount"); return; } }
        final long amt = n;
        plugin.economy.withAccount(op.getUniqueId(), acc -> {
            java.util.function.Consumer<Boolean> done = ok -> {
                if (ok) plugin.send(s, "admin-" + sub, "%amount%", Money.fmt(amt), "%player%", acc.name);
                else plugin.send(s, "admin-failed", "%player%", acc.name);
            };
            switch (sub) {
                case "give" -> plugin.economy.adminGive(acc, amt, done);
                case "take" -> plugin.economy.adminTake(acc, amt, done);
                default -> plugin.economy.adminSet(acc, amt, done);
            }
        }, () -> plugin.send(s, "player-not-found"));
    }

    private void migrate(CommandSender s, String[] a) {
        long ir = plugin.getConfig().getLong("migration.iron-rate", -1), dr = plugin.getConfig().getLong("migration.diamond-rate", -1);
        if (ir < 0 || dr < 0) { plugin.send(s, "migrate-unset"); return; }
        boolean confirm = a.length > 1 && a[1].equalsIgnoreCase("confirm");
        plugin.economy.migrationPreview().whenComplete((r, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) { plugin.send(s, "db-error"); return; }
            if (r[0] == 0) { plugin.send(s, "migrate-none"); return; }
            if (!confirm) {
                plugin.send(s, "migrate-preview", "%accounts%", r[0], "%iron%", Text.num(r[1]), "%diamonds%", Text.num(r[2]), "%irate%", ir, "%drate%", dr);
                return;
            }
            plugin.economy.migrate(ir, dr, msg -> s.sendMessage(Text.c(plugin.raw("prefix") + "&7" + msg)));
        }));
    }

    // ---------- /knight ----------
    private void knight(CommandSender s, String[] a) {
        String usage = "/knight <reload/setbounty/resetbounty/market>";
        if (a.length == 0) { plugin.send(s, "usage", "%usage%", usage); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!perm(s, "knightsmp.admin.reload")) return;
                plugin.reloadAll();
                plugin.send(s, "reloaded");
            }
            case "setbounty", "resetbounty" -> {
                if (!perm(s, "knightsmp.admin.setbounty")) return;
                boolean reset = a[0].equalsIgnoreCase("resetbounty");
                if (a.length < (reset ? 2 : 3)) { plugin.send(s, "usage", "%usage%", reset ? "/knight resetbounty <player>" : "/knight setbounty <player> <amount>"); return; }
                OfflinePlayer op = resolve(a[1]);
                if (op == null) { plugin.send(s, "player-not-found"); return; }
                long n = 0;
                if (!reset) { try { n = Long.parseLong(a[2]); } catch (NumberFormatException e) { n = -1; } if (n < 0) { plugin.send(s, "invalid-amount"); return; } }
                final long amt = n;
                plugin.economy.withAccount(op.getUniqueId(), acc ->
                        plugin.bounty.setBounty(acc, amt, ok -> {
                            if (ok) plugin.send(s, "bounty-set", "%player%", acc.name, "%bounty%", Money.fmt(acc.bounty));
                            else plugin.send(s, "db-error");
                        }), () -> plugin.send(s, "player-not-found"));
            }
            case "market" -> {
                if (!perm(s, "knightsmp.admin.market")) return;
                if (a.length < 2) { plugin.send(s, "usage", "%usage%", "/knight market <reload/refresh>"); return; }
                if (a[1].equalsIgnoreCase("reload")) { plugin.market.reloadDefinitions(); plugin.send(s, "market-reloaded"); }
                else if (a[1].equalsIgnoreCase("refresh")) { plugin.market.refresh(true); plugin.send(s, "market-forced"); }
                else plugin.send(s, "usage", "%usage%", "/knight market <reload/refresh>");
            }
            default -> plugin.send(s, "usage", "%usage%", usage);
        }
    }

    // ---------- tab completion ----------
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>(), pool = new ArrayList<>();
        String cur = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "pay", "money", "stat" -> { if (a.length == 1) players(pool); }
            case "payanim" -> { if (a.length == 1) pool.addAll(List.of("on", "off")); }
            case "moneyadmin" -> {
                if (a.length == 1) pool.addAll(List.of("give", "take", "set", "migrate"));
                else if (a.length == 2 && a[0].equalsIgnoreCase("migrate")) pool.add("confirm");
                else if (a.length == 2) players(pool);
            }
            case "knight" -> {
                if (a.length == 1) pool.addAll(List.of("reload", "setbounty", "resetbounty", "market"));
                else if (a.length == 2 && a[0].equalsIgnoreCase("market")) pool.addAll(List.of("reload", "refresh"));
                else if (a.length == 2) players(pool);
            }
            default -> {}
        }
        for (String x : pool) if (x.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(x);
        return out;
    }
    private void players(List<String> pool) { for (Player p : Bukkit.getOnlinePlayers()) pool.add(p.getName()); }
}
