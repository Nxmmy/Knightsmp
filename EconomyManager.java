package dev.knightsmp.mods.economy;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.storage.Sql;
import dev.knightsmp.mods.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Dollar economy. The SQLite database is authoritative; the in-memory Account is only updated AFTER a database
 * transaction commits, so memory and disk can never disagree about money.
 *
 * Every money movement is one SQL transaction (balance change + history row + any extra step such as market stock),
 * executed on the database thread, with its result handled back on the server thread.
 */
public final class EconomyManager {
    public static final String CURRENCY_TAG = "USD";

    public record Tx(long ts, String other, String currency, long delta, String type) {}
    /** One balance movement inside a transaction. A negative delta only succeeds if the balance covers it. */
    public record Change(UUID account, long delta, UUID other, String type) {}
    public record Row(UUID id, String name, long value) {}

    private static final class Pending {
        CompletableFuture<Boolean> future;
        Runnable finish;
        final AtomicBoolean finished = new AtomicBoolean();
    }

    private final KnightPlugin plugin;
    private final Map<UUID, Account> cache = new ConcurrentHashMap<>();
    private final Set<UUID> busy = new HashSet<>();                       // server thread only
    private final Set<Pending> pending = ConcurrentHashMap.newKeySet();

    public EconomyManager(KnightPlugin plugin) { this.plugin = plugin; }

    // ---------- account loading ----------
    public Account cached(UUID id) { return cache.get(id); }
    public Account get(Player p) { return cache.get(p.getUniqueId()); }
    public void forEachCached(Consumer<Account> action) { for (Account a : cache.values()) action.accept(a); }

    /** Called from the async pre-login thread (and once at startup for already-online players). */
    public Account loadBlocking(UUID id, String name) throws Exception {
        Account existing = cache.get(id);
        long start = Math.max(0, plugin.getConfig().getLong("economy.starting-balance", 100));
        Account loaded = plugin.db.call(c -> {
            Database.run(c, Sql.INSERT_ACCOUNT, id.toString(), name, start);
            Database.run(c, Sql.UPDATE_NAME, name, id.toString());
            return read(c, id);
        }).get(10, TimeUnit.SECONDS);
        if (loaded == null) throw new SQLException("account row missing after insert");
        if (existing != null) { existing.name = name; return existing; }
        Account prev = cache.putIfAbsent(id, loaded);
        return prev != null ? prev : loaded;
    }

    /** Runs ok on the server thread with the (possibly offline) account, or fail if no such account exists. */
    public void withAccount(UUID id, Consumer<Account> ok, Runnable fail) {
        Account a = cache.get(id);
        if (a != null) { ok.accept(a); return; }
        plugin.db.call(c -> read(c, id)).whenComplete((acc, ex) -> runMain(() -> {
            if (ex != null || acc == null) { fail.run(); return; }
            Account prev = cache.putIfAbsent(id, acc);
            ok.accept(prev != null ? prev : acc);
        }));
    }

    private Account read(Connection c, UUID id) throws SQLException {
        Account a = null;
        try (PreparedStatement ps = Database.bind(c, Sql.SELECT_ACCOUNT, id.toString()); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) { a = new Account(id, rs.getString(1)); a.dollars = rs.getLong(2); a.bounty = rs.getLong(3); a.kills = rs.getLong(4); a.deaths = rs.getLong(5); a.streak = rs.getLong(6); a.bestStreak = rs.getLong(7); a.playtime = rs.getLong(8); a.animOff = rs.getLong(9) != 0; }
        }
        if (a == null) return null;
        try (PreparedStatement ps = Database.bind(c, Sql.HOME_SELECT, id.toString()); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) a.home = new dev.knightsmp.mods.home.HomeManager.Home(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), (float) rs.getDouble(5), (float) rs.getDouble(6));
        }
        try (PreparedStatement ps = Database.bind(c, Sql.SETTING_SELECT, id.toString()); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) a.settings.put(rs.getString(1), rs.getString(2));
        }
        try (PreparedStatement ps = Database.bind(c, Sql.SELECT_KITS, id.toString()); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) a.kitCooldowns.put(rs.getString(1), rs.getLong(2));
        }
        return a;
    }

    private void runMain(Runnable r) {
        try { Bukkit.getScheduler().runTask(plugin, r); }
        catch (IllegalPluginAccessException e) { /* plugin is disabling: shutdown() finishes pending work itself */ }
    }

    // ---------- limits ----------
    public long max() { return plugin.getConfig().getLong("economy.max-transaction", 1_000_000L); }
    public long cap() { return plugin.getConfig().getLong("economy.balance-cap", 1_000_000_000_000L); }
    public long balance(Player p) { Account a = get(p); return a == null ? 0 : a.dollars; }

    // ---------- per-player lock (rapid clicks / simultaneous transactions) ----------
    public boolean tryLock(UUID id) { return busy.add(id); }
    public void unlock(UUID id) { busy.remove(id); }

    // ---------- the transaction primitive ----------
    /**
     * Commits balance changes plus an optional extra SQL step atomically. Server thread only.
     * @param extra     extra statements in the same transaction; return false to roll everything back
     * @param onSuccess cache updates for non-money state, run on the server thread only if the commit succeeded
     * @param done      always called on the server thread with the outcome
     */
    public void commit(String type, List<Change> changes, Database.SqlFunction<Boolean> extra, Runnable onSuccess, Consumer<Boolean> done) {
        final long ts = System.currentTimeMillis();
        final List<Change> list = List.copyOf(changes);
        CompletableFuture<Boolean> fut = plugin.db.call(c -> {
            c.setAutoCommit(false);
            try {
                for (Change ch : list) {
                    int rows = ch.delta() >= 0
                            ? Database.run(c, Sql.CREDIT, ch.delta(), ch.account().toString())
                            : Database.run(c, Sql.DEBIT, -ch.delta(), ch.account().toString(), -ch.delta());
                    if (rows != 1) { c.rollback(); return false; }
                    Database.run(c, Sql.TX_INSERT, ts, ch.account().toString(), ch.other() == null ? null : ch.other().toString(),
                            CURRENCY_TAG, ch.delta(), ch.type() == null ? type : ch.type());
                }
                if (extra != null && !extra.apply(c)) { c.rollback(); return false; }
                c.commit();
                return true;
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
        Pending p = new Pending();
        p.future = fut;
        p.finish = () -> {
            if (!p.finished.compareAndSet(false, true)) return;
            pending.remove(p);
            boolean ok;
            try { ok = Boolean.TRUE.equals(fut.getNow(false)); } catch (Exception e) { ok = false; }
            if (ok) {
                for (Change ch : list) { Account a = cache.get(ch.account()); if (a != null) a.dollars += ch.delta(); plugin.hud.touch(ch.account()); }
                if (onSuccess != null) onSuccess.run();
            } else {
                plugin.getLogger().severe("Transaction '" + type + "' failed and was rolled back (" + list + "). No money moved.");
            }
            try { done.accept(ok); }
            catch (RuntimeException e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error finishing transaction " + type, e); }
        };
        pending.add(p);
        fut.whenComplete((r, e) -> runMain(p.finish));
    }

    /** Called on plugin disable: waits for in-flight transactions and finishes them so nothing is left half-done. */
    public void shutdown() {
        for (int round = 0; round < 5 && !pending.isEmpty(); round++) {
            for (Pending p : new ArrayList<>(pending)) {
                try { p.future.get(15, TimeUnit.SECONDS); } catch (Exception ignored) { /* handled as failure in finish */ }
                p.finish.run();
            }
        }
    }

    // ---------- /pay ----------
    public void pay(Player from, Account to, long amt, Runnable after) {
        Account fa = get(from);
        if (fa == null) { after.run(); return; }
        if (amt <= 0 || amt > max()) { plugin.send(from, "invalid-amount"); after.run(); return; }
        if (fa.id.equals(to.id)) { plugin.send(from, "pay-self"); after.run(); return; }
        if (to.dollars > cap() - amt) { plugin.send(from, "pay-cap"); after.run(); return; }
        if (fa.dollars < amt) { plugin.send(from, "pay-not-enough"); after.run(); return; }
        if (!tryLock(fa.id)) { plugin.send(from, "tx-busy"); after.run(); return; }
        commit("PAY", List.of(new Change(fa.id, -amt, to.id, "PAY_SENT"), new Change(to.id, amt, fa.id, "PAY_RECEIVED")), null, null, ok -> {
            unlock(fa.id);
            if (ok) {
                plugin.send(from, "pay-sent", "%amount%", Money.fmt(amt), "%player%", to.name);
                Player tp = Bukkit.getPlayer(to.id);
                if (tp != null) {
                    plugin.send(tp, "pay-received", "%amount%", Money.fmt(amt), "%player%", fa.name);
                    try { plugin.fx.play(tp, fa.name, amt); }                       // cosmetic only: can never affect the payment
                    catch (Exception e) { plugin.getLogger().warning("Payment animation failed for " + tp.getName() + ": " + e); }
                }
            } else plugin.send(from, "pay-failed");
            after.run();
        });
    }

    // ---------- admin ----------
    public void adminGive(Account a, long amt, Consumer<Boolean> done) {
        if (amt <= 0 || a.dollars > cap() - amt) { done.accept(false); return; }
        commit("ADMIN_GIVE", List.of(new Change(a.id, amt, null, "ADMIN_GIVE")), null, null, done);
    }
    public void adminTake(Account a, long amt, Consumer<Boolean> done) {
        if (amt <= 0 || a.dollars < amt) { done.accept(false); return; }
        commit("ADMIN_TAKE", List.of(new Change(a.id, -amt, null, "ADMIN_TAKE")), null, null, done);
    }
    public void adminSet(Account a, long amt, Consumer<Boolean> done) {
        if (amt < 0 || amt > cap()) { done.accept(false); return; }
        final long delta = amt - a.dollars;
        final String id = a.id.toString();
        commit("ADMIN_SET", List.of(), c -> {
            if (Database.run(c, Sql.SET_BALANCE, amt, id) != 1) return false;
            Database.run(c, Sql.TX_INSERT, System.currentTimeMillis(), id, null, CURRENCY_TAG, delta, "ADMIN_SET");
            return true;
        }, () -> { a.dollars = amt; plugin.hud.touch(a.id); }, done);
    }

    // ---------- queries ----------
    public CompletableFuture<List<Tx>> history(UUID id, int limit) {
        return plugin.db.call(c -> {
            List<Tx> out = new ArrayList<>();
            try (PreparedStatement ps = Database.bind(c, Sql.SELECT_TX, id.toString(), limit); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Tx(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)));
            }
            return out;
        });
    }
    public CompletableFuture<List<Row>> top(int limit) {
        return plugin.db.call(c -> {
            List<Row> out = new ArrayList<>();
            try (PreparedStatement ps = Database.bind(c, Sql.BALTOP, limit); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
            }
            return out;
        });
    }

    // ---------- legacy Iron/Diamond migration ----------
    /** Returns {accounts, totalIron, totalDiamonds} still waiting to be converted. */
    public CompletableFuture<long[]> migrationPreview() {
        return plugin.db.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(Sql.MIG_PREVIEW); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)};
            }
        });
    }

    /**
     * Converts every not-yet-migrated account in one transaction, after a file backup.
     * Old Iron/Diamond columns are never zeroed or deleted; the per-account 'migrated' flag makes this run-once.
     */
    public void migrate(long ironRate, long diamondRate, Consumer<String> report) {
        final long cap = cap();
        CompletableFuture<Map<UUID, Long>> fut = plugin.db.call(c -> {
            plugin.db.backup(c, "pre-migration");
            Map<UUID, Long> credited = new HashMap<>();
            c.setAutoCommit(false);
            try {
                List<Object[]> rows = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(Sql.MIG_SELECT); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3)});
                }
                long now = System.currentTimeMillis();
                for (Object[] r : rows) {
                    long credit;
                    try { credit = Math.addExact(Math.multiplyExact((long) r[1], ironRate), Math.multiplyExact((long) r[2], diamondRate)); }
                    catch (ArithmeticException e) { credit = cap; }
                    credit = Math.min(credit, cap);
                    if (Database.run(c, Sql.MIG_APPLY, credit, r[0]) == 1) {
                        Database.run(c, Sql.TX_INSERT, now, r[0], null, CURRENCY_TAG, credit, "MIGRATION");
                        credited.put(UUID.fromString((String) r[0]), credit);
                    }
                }
                c.commit();
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally { c.setAutoCommit(true); }
            return credited;
        });
        fut.whenComplete((map, ex) -> runMain(() -> {
            if (ex != null) { report.accept("Migration FAILED and was rolled back; no balances changed. See console."); return; }
            long total = 0;
            for (Map.Entry<UUID, Long> e : map.entrySet()) {
                total += e.getValue();
                Account a = cache.get(e.getKey());
                if (a != null) { a.dollars += e.getValue(); plugin.hud.touch(e.getKey()); }
            }
            plugin.getLogger().info("Migration complete: " + map.size() + " accounts, " + total + " dollars credited.");
            report.accept("Migrated " + map.size() + " accounts (" + Money.fmt(total) + "&7 credited).");
        }));
    }
}
