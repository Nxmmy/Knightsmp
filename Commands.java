package dev.knightsmp.mods.commands;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.Currency;
import dev.knightsmp.mods.gui.BountyMenu;
import dev.knightsmp.mods.gui.EconomyMenu;
import dev.knightsmp.mods.gui.KitMenu;
import dev.knightsmp.mods.gui.SellMenu;
import dev.knightsmp.mods.gui.ShopMainMenu;
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
    private static long amount(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }
    /** Online player, or an offline player the server has seen before. */
    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "tpa" -> {
                Player p = player(sender); if (p == null) return true;
                if (a.length < 1) { plugin.send(p, "usage", "%usage%", "/tpa <player>"); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) { plugin.send(p, "player-not-found"); return true; }
                plugin.teleport.tpa(p, t);
            }
            case "tpaccept" -> { Player p = player(sender); if (p != null) plugin.teleport.accept(p); }
            case "tpdeny" -> { Player p = player(sender); if (p != null) plugin.teleport.deny(p); }
            case "home" -> { Player p = player(sender); if (p != null) plugin.teleport.home(p); }
            case "sethome" -> { Player p = player(sender); if (p != null) plugin.teleport.setHome(p); }
            case "delhome" -> { Player p = player(sender); if (p != null) plugin.teleport.delHome(p); }
            case "back" -> { Player p = player(sender); if (p != null) plugin.teleport.back(p); }
            case "spawn" -> { Player p = player(sender); if (p != null) plugin.teleport.spawn(p); }
            case "setspawn" -> { Player p = player(sender); if (p != null) plugin.teleport.setSpawn(p); }
            case "rtp" -> { Player p = player(sender); if (p != null) plugin.teleport.rtp(p); }
            case "bounty" -> { Player p = player(sender); if (p != null) BountyMenu.show(plugin, p); }
            case "shop" -> { Player p = player(sender); if (p != null) new ShopMainMenu(plugin, p).open(); }
            case "shopkits" -> { Player p = player(sender); if (p != null) new KitMenu(plugin, p).open(); }
            case "sell" -> { Player p = player(sender); if (p != null) new SellMenu(plugin, p).open(); }
            case "money" -> money(sender, a);
            case "pay" -> pay(sender, a);
            case "knight" -> knight(sender, a);
            default -> { return false; }
        }
        return true;
    }

    // ---------- /money ----------
    private void money(CommandSender s, String[] a) {
        if (a.length == 0) { Player p = player(s); if (p != null) new EconomyMenu(plugin, p).open(); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("deposit") || sub.equals("withdraw")) {
            Player p = player(s); if (p == null) return;
            Currency c = a.length > 1 ? Currency.parse(a[1]) : null;
            if (c == null) { plugin.send(p, "invalid-currency"); return; }
            boolean all = a.length > 2 && a[2].equalsIgnoreCase("all");
            long n = all ? -1 : (a.length > 2 ? amount(a[2]) : 0);
            if (sub.equals("deposit")) {
                if (!all && n <= 0) { plugin.send(p, "invalid-amount"); return; }
                plugin.economy.deposit(p, c, n);
            } else {
                Account acc = plugin.economy.get(p);
                if (all && acc != null) n = Math.min(acc.get(c), plugin.getConfig().getLong("economy.withdraw-max", 2304));
                plugin.economy.withdraw(p, c, n);
            }
            return;
        }
        if (!s.hasPermission("knightsmp.money.others") && !(s instanceof Player pl && pl.getName().equalsIgnoreCase(a[0]))) { plugin.send(s, "no-permission"); return; }
        OfflinePlayer op = resolve(a[0]);
        if (op == null) { plugin.send(s, "player-not-found"); return; }
        plugin.economy.withAccount(op.getUniqueId(),
                acc -> plugin.send(s, "eco-balance-other", "%player%", acc.name, "%iron%", Text.num(acc.iron), "%diamonds%", Text.num(acc.diamonds)),
                () -> plugin.send(s, "player-not-found"));
    }

    // ---------- /pay ----------
    private void pay(CommandSender s, String[] a) {
        Player p = player(s); if (p == null) return;
        if (a.length < 3) { plugin.send(p, "usage", "%usage%", "/pay <player> <iron/diamonds> <amount>"); return; }
        OfflinePlayer op = resolve(a[0]);
        Currency c = Currency.parse(a[1]);
        long n = amount(a[2]);
        if (op == null) { plugin.send(p, "player-not-found"); return; }
        if (c == null) { plugin.send(p, "invalid-currency"); return; }
        if (n <= 0) { plugin.send(p, "invalid-amount"); return; }
        plugin.economy.withAccount(op.getUniqueId(), acc -> { if (p.isOnline()) plugin.economy.pay(p, acc, c, n); },
                () -> plugin.send(p, "player-not-found"));
    }

    // ---------- /knight ----------
    private boolean perm(CommandSender s, String node) {
        if (s.hasPermission("knightsmp.admin") || s.hasPermission(node)) return true;
        plugin.send(s, "no-permission");
        return false;
    }

    private void knight(CommandSender s, String[] a) {
        if (a.length == 0) { plugin.send(s, "usage", "%usage%", "/knight <reload/give/take/setbounty/resetbounty/market/setspawn>"); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!perm(s, "knightsmp.admin.reload")) return;
                plugin.reloadAll();
                plugin.send(s, "reloaded");
            }
            case "give", "take" -> {
                boolean give = a[0].equalsIgnoreCase("give");
                if (!perm(s, give ? "knightsmp.admin.give" : "knightsmp.admin.take")) return;
                if (a.length < 4) { plugin.send(s, "usage", "%usage%", "/knight " + a[0] + " <player> <iron/diamonds> <amount>"); return; }
                OfflinePlayer op = resolve(a[1]);
                Currency c = Currency.parse(a[2]);
                long n = amount(a[3]);
                if (op == null) { plugin.send(s, "player-not-found"); return; }
                if (c == null) { plugin.send(s, "invalid-currency"); return; }
                if (n <= 0) { plugin.send(s, "invalid-amount"); return; }
                plugin.economy.withAccount(op.getUniqueId(), acc -> {
                    boolean ok = give ? plugin.economy.adminGive(acc, c, n) : plugin.economy.adminTake(acc, c, n);
                    if (ok) plugin.send(s, give ? "admin-given" : "admin-taken", "%amount%", c.fmt(n), "%player%", acc.name);
                    else plugin.send(s, "admin-failed", "%player%", acc.name);
                }, () -> plugin.send(s, "player-not-found"));
            }
            case "setbounty" -> {
                if (!perm(s, "knightsmp.admin.setbounty")) return;
                if (a.length < 3) { plugin.send(s, "usage", "%usage%", "/knight setbounty <player> <amount>"); return; }
                OfflinePlayer op = resolve(a[1]);
                long n = amount(a[2]);
                if (op == null) { plugin.send(s, "player-not-found"); return; }
                if (n < 0) { plugin.send(s, "invalid-amount"); return; }
                plugin.economy.withAccount(op.getUniqueId(), acc -> {
                    plugin.bounty.setBounty(acc, n);
                    plugin.send(s, "bounty-set", "%player%", acc.name, "%bounty%", Text.num(n));
                }, () -> plugin.send(s, "player-not-found"));
            }
            case "resetbounty" -> {
                if (!perm(s, "knightsmp.admin.setbounty")) return;
                if (a.length < 2) { plugin.send(s, "usage", "%usage%", "/knight resetbounty <player>"); return; }
                OfflinePlayer op = resolve(a[1]);
                if (op == null) { plugin.send(s, "player-not-found"); return; }
                plugin.economy.withAccount(op.getUniqueId(), acc -> {
                    plugin.bounty.setBounty(acc, 0);
                    plugin.send(s, "bounty-set", "%player%", acc.name, "%bounty%", "0");
                }, () -> plugin.send(s, "player-not-found"));
            }
            case "market" -> {
                if (!perm(s, "knightsmp.admin.market")) return;
                if (a.length < 2) { plugin.send(s, "usage", "%usage%", "/knight market <reload/refresh>"); return; }
                if (a[1].equalsIgnoreCase("reload")) { plugin.market.reloadDefinitions(); plugin.send(s, "market-reloaded"); }
                else if (a[1].equalsIgnoreCase("refresh")) { plugin.market.refresh(true); plugin.send(s, "market-forced"); }
                else plugin.send(s, "usage", "%usage%", "/knight market <reload/refresh>");
            }
            case "setspawn" -> {
                if (!perm(s, "knightsmp.admin.setspawn")) return;
                Player p = player(s); if (p != null) plugin.teleport.setSpawn(p);
            }
            default -> plugin.send(s, "usage", "%usage%", "/knight <reload/give/take/setbounty/resetbounty/market/setspawn>");
        }
    }

    // ---------- tab completion ----------
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String cur = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> pool = new ArrayList<>();
        switch (name) {
            case "tpa" -> { if (a.length == 1) players(pool); }
            case "pay" -> { if (a.length == 1) players(pool); else if (a.length == 2) pool.addAll(List.of("iron", "diamonds")); }
            case "money" -> {
                if (a.length == 1) { players(pool); pool.addAll(List.of("deposit", "withdraw")); }
                else if (a.length == 2) pool.addAll(List.of("iron", "diamonds"));
                else if (a.length == 3) pool.add("all");
            }
            case "knight" -> {
                if (a.length == 1) pool.addAll(List.of("reload", "give", "take", "setbounty", "resetbounty", "market", "setspawn"));
                else if (a.length == 2 && a[0].equalsIgnoreCase("market")) pool.addAll(List.of("reload", "refresh"));
                else if (a.length == 2) players(pool);
                else if (a.length == 3 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("take"))) pool.addAll(List.of("iron", "diamonds"));
            }
            default -> {}
        }
        for (String x : pool) if (x.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(x);
        return out;
    }
    private void players(List<String> pool) { for (Player p : Bukkit.getOnlinePlayers()) pool.add(p.getName()); }
}
