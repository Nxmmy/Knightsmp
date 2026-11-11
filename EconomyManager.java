package dev.knightsmp.mods.economy;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.teleport.TeleportManager;
import dev.knightsmp.mods.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Balances live in the database (and an in-memory cache). Physical iron ingots / diamonds are converted
 * to and from balances via deposit / withdraw, and can optionally be spent directly from the inventory.
 * All mutation happens on the server thread, so a purchase can never be processed twice concurrently.
 */
public final class EconomyManager {
    public record Tx(long ts, String other, String currency, long delta, String type) {}

    private final KnightPlugin plugin;
    private final Map<UUID, Account> cache = new ConcurrentHashMap<>();

    public EconomyManager(KnightPlugin plugin) { this.plugin = plugin; }

    // ---------- loading ----------
    public Account cached(UUID id) { return cache.get(id); }
    public Account get(Player p) { return cache.get(p.getUniqueId()); }

    public Account loadBlocking(UUID id, String name) throws Exception {
        Account a = cache.get(id);
        if (a != null) { a.name = name; return a; }
        Account loaded = plugin.db.call(c -> read(c, id, name, true)).get(10, TimeUnit.SECONDS);
        Account prev = cache.putIfAbsent(id, loaded);
        return prev != null ? prev : loaded;
    }

    /** Runs ok on the server thread with the (possibly offline) account, or fail if it does not exist. */
    public void withAccount(UUID id, Consumer<Account> ok, Runnable fail) {
        Account a = cache.get(id);
        if (a != null) { ok.accept(a); return; }
        plugin.db.call(c -> read(c, id, null, false)).whenComplete((acc, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null || acc == null) { fail.run(); return; }
                    Account prev = cache.putIfAbsent(id, acc);
                    ok.accept(prev != null ? prev : acc);
                }));
    }

    private Account read(Connection c, UUID id, String name, boolean create) throws SQLException {
        String u = id.toString();
        if (create) {
            try (PreparedStatement ps = Database.bind(c, "INSERT OR IGNORE INTO accounts(uuid,name) VALUES(?,?)", u, name)) { ps.executeUpdate(); }
        }
        Account a = null;
        try (PreparedStatement ps = Database.bind(c, "SELECT name,iron,diamonds,bounty FROM accounts WHERE uuid=?", u);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) { a = new Account(id, rs.getString(1)); a.iron = rs.getLong(2); a.diamonds = rs.getLong(3); a.bounty = rs.getLong(4); }
        }
        if (a == null) return null;
        if (name != null) a.name = name;
        try (PreparedStatement ps = Database.bind(c, "SELECT world,x,y,z,yaw,pitch FROM homes WHERE uuid=?", u);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) a.home = new TeleportManager.Home(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), (float) rs.getDouble(5), (float) rs.getDouble(6));
        }
        try (PreparedStatement ps = Database.bind(c, "SELECT kit,ts FROM kit_cooldowns WHERE uuid=?", u);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) a.kitCooldowns.put(rs.getString(1), rs.getLong(2));
        }
        return a;
    }

    public void save(Account a) {
        plugin.db.update("UPDATE accounts SET name=?, iron=?, diamonds=?, bounty=? WHERE uuid=?", a.name, a.iron, a.diamonds, a.bounty, a.id.toString());
    }
    public void saveAll() { for (Account a : cache.values()) save(a); }

    public void log(UUID who, UUID other, Currency c, long delta, String type) {
        plugin.db.update("INSERT INTO tx(ts,uuid,other,currency,delta,type) VALUES(?,?,?,?,?,?)",
                System.currentTimeMillis(), who.toString(), other == null ? null : other.toString(), c.name(), delta, type);
    }
    public CompletableFuture<List<Tx>> history(UUID id, int limit) {
        return plugin.db.call(c -> {
            List<Tx> out = new ArrayList<>();
            try (PreparedStatement ps = Database.bind(c, "SELECT ts,other,currency,delta,type FROM tx WHERE uuid=? ORDER BY id DESC LIMIT ?", id.toString(), limit);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Tx(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)));
            }
            return out;
        });
    }

    // ---------- helpers ----------
    public long max() { return plugin.getConfig().getLong("economy.max-transaction", 1_000_000L); }
    private long cap() { return plugin.getConfig().getLong("economy.balance-cap", 1_000_000_000_000L); }
    public long inventoryCount(Player p, Currency c) { return Items.count(p.getInventory(), c.material); }
    public long spendable(Player p, Currency c) {
        Account a = get(p);
        if (a == null) return 0;
        long inv = plugin.getConfig().getBoolean("economy.spend-from-inventory", true) ? inventoryCount(p, c) : 0;
        return a.get(c) + inv;
    }

    /** Spends money: account balance first, then (if enabled) physical items. Returns false without side effects if funds are insufficient. */
    public boolean charge(Player p, Currency c, long amt, String type, UUID other) {
        Account a = get(p);
        if (a == null || amt < 0) return false;
        if (amt == 0) return true;
        long fromBal = Math.min(a.get(c), amt);
        long rem = amt - fromBal;
        if (rem > 0) {
            if (!plugin.getConfig().getBoolean("economy.spend-from-inventory", true)) return false;
            if (!Items.remove(p.getInventory(), c.material, rem)) return false;
        }
        a.set(c, a.get(c) - fromBal);
        save(a);
        log(a.id, other, c, -amt, type);
        return true;
    }

    /** Pays money to an online player, as physical items or account balance depending on economy.payout-mode. */
    public void reward(Player p, Currency c, long amt, String type) {
        Account a = get(p);
        if (a == null || amt <= 0) return;
        long toBalance = amt;
        if ("INVENTORY".equalsIgnoreCase(plugin.getConfig().getString("economy.payout-mode", "BALANCE")))
            toBalance = Items.giveUpTo(p, c.material, amt); // overflow that does not fit goes to the balance
        if (toBalance > 0) { a.set(c, Math.min(cap(), a.get(c) + toBalance)); save(a); }
        log(a.id, null, c, amt, type);
    }

    public void deposit(Player p, Currency c, long amt) {
        Account a = get(p);
        if (a == null) return;
        long have = inventoryCount(p, c);
        long n = amt < 0 ? have : amt;
        if (n <= 0 || n > have) { plugin.send(p, "deposit-none", "%currency%", c.display); return; }
        if (a.get(c) + n > cap()) { plugin.send(p, "pay-cap"); return; }
        if (!Items.remove(p.getInventory(), c.material, n)) return;
        a.set(c, a.get(c) + n);
        save(a);
        log(a.id, null, c, n, "DEPOSIT");
        plugin.send(p, "deposit-done", "%amount%", c.fmt(n));
    }

    public void withdraw(Player p, Currency c, long amt) {
        Account a = get(p);
        if (a == null) return;
        if (!plugin.getConfig().getBoolean("economy.allow-withdraw", true)) { plugin.send(p, "withdraw-disabled"); return; }
        if (amt <= 0) { plugin.send(p, "invalid-amount"); return; }
        if (amt > plugin.getConfig().getLong("economy.withdraw-max", 2304)) { plugin.send(p, "withdraw-limit", "%max%", plugin.getConfig().getLong("economy.withdraw-max", 2304)); return; }
        if (a.get(c) < amt) { plugin.send(p, "withdraw-not-enough", "%currency%", c.display); return; }
        List<ItemStack> st = Items.stacks(c.material, amt);
        if (!Items.canFit(p, st)) { plugin.send(p, "withdraw-no-space"); return; }
        a.set(c, a.get(c) - amt);
        save(a);
        Items.give(p, st);
        log(a.id, null, c, -amt, "WITHDRAW");
        plugin.send(p, "withdraw-done", "%amount%", c.fmt(amt));
    }

    /** Player-to-player payment. Takes from balance (then inventory if enabled) and credits the receiver's account. */
    public void pay(Player from, Account to, Currency c, long amt) {
        Account fa = get(from);
        if (fa == null) return;
        if (amt <= 0 || amt > max()) { plugin.send(from, "invalid-amount"); return; }
        if (fa.id.equals(to.id)) { plugin.send(from, "pay-self"); return; }
        if (to.get(c) + amt > cap()) { plugin.send(from, "pay-cap"); return; }
        if (spendable(from, c) < amt) { plugin.send(from, "pay-not-enough", "%currency%", c.display); return; }
        if (!charge(from, c, amt, "PAY_SENT", to.id)) { plugin.send(from, "pay-not-enough", "%currency%", c.display); return; }
        to.set(c, to.get(c) + amt);
        save(to);
        log(to.id, fa.id, c, amt, "PAY_RECEIVED");
        plugin.send(from, "pay-sent", "%amount%", c.fmt(amt), "%player%", to.name);
        Player tp = Bukkit.getPlayer(to.id);
        if (tp != null) plugin.send(tp, "pay-received", "%amount%", c.fmt(amt), "%player%", fa.name);
    }

    public boolean adminGive(Account a, Currency c, long amt) {
        if (amt <= 0 || a.get(c) + amt > cap()) return false;
        a.set(c, a.get(c) + amt); save(a); log(a.id, null, c, amt, "ADMIN_GIVE");
        return true;
    }
    public boolean adminTake(Account a, Currency c, long amt) {
        if (amt <= 0 || a.get(c) < amt) return false;
        a.set(c, a.get(c) - amt); save(a); log(a.id, null, c, -amt, "ADMIN_TAKE");
        return true;
    }
}
