package dev.knightsmp.mods.market;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.EconomyManager.Change;
import dev.knightsmp.mods.storage.Sql;
import dev.knightsmp.mods.util.Money;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Rotating item market: prices/stock re-rolled every refresh-hours (default 24) using wall-clock (UTC epoch) time. */
public final class MarketManager {
    public static final class Item {
        public final Material material; public final int bundle; public final long base;
        public boolean up; public double multiplier = 1; public long price; public int stock;
        Item(Material m, int bundle, long base) { this.material = m; this.bundle = bundle; this.base = base; price = base; }
    }
    public record Category(String id, String name, Material icon, List<Item> items) {}

    private final KnightPlugin plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Random rnd = new Random();
    private YamlConfiguration mcfg = new YamlConfiguration();
    private long nextRefresh;
    private BukkitTask task;

    public MarketManager(KnightPlugin plugin) { this.plugin = plugin; }
    public Map<String, Category> categories() { return categories; }
    public long secondsToRefresh() { return Math.max(0, (nextRefresh - System.currentTimeMillis() + 999) / 1000); }

    // ---------- loading ----------
    public void load() {
        parseDefinitions();
        Map<Material, double[]> state = new HashMap<>();
        try {
            nextRefresh = plugin.db.call(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k='market_next'"); ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Long.parseLong(rs.getString(1)) : 0L;
                }
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            plugin.db.call(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT material,up,multiplier,price,stock FROM market_state"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Material m = Material.matchMaterial(rs.getString(1));
                        if (m != null) state.put(m, new double[]{rs.getInt(2), rs.getDouble(3), rs.getLong(4), rs.getInt(5)});
                    }
                }
                return null;
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) { plugin.getLogger().warning("Could not load market state, regenerating: " + e.getMessage()); nextRefresh = 0; }

        boolean missing = false;
        for (Category cat : categories.values())
            for (Item it : cat.items()) {
                double[] s = state.get(it.material);
                if (s == null) { missing = true; roll(it); }
                else { it.up = s[0] == 1; it.multiplier = s[1]; it.price = (long) s[2]; it.stock = (int) s[3]; }
            }
        if (nextRefresh <= System.currentTimeMillis()) refresh(false);
        else if (missing) persist();
    }

    /** /knight market reload: re-read market.yml but keep current prices for unchanged items. */
    public void reloadDefinitions() {
        Map<Material, Item> old = new HashMap<>();
        for (Category c : categories.values()) for (Item i : c.items()) old.put(i.material, i);
        parseDefinitions();
        for (Category c : categories.values())
            for (Item i : c.items()) {
                Item o = old.get(i.material);
                if (o != null && o.base == i.base && o.bundle == i.bundle) { i.up = o.up; i.multiplier = o.multiplier; i.price = o.price; i.stock = o.stock; }
                else roll(i);
            }
        persist();
    }

    private void parseDefinitions() {
        categories.clear();
        YamlConfiguration y = plugin.yaml("market.yml");
        mcfg = y;
        ConfigurationSection root = y.getConfigurationSection("categories");
        if (root == null) { plugin.getLogger().warning("market.yml has no 'categories' section."); return; }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            List<Item> items = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("items")) {
                Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
                if (mat == null || !mat.isItem()) { plugin.getLogger().warning("market.yml: skipping invalid item " + m); continue; }
                int bundle = m.get("bundle") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                long base = m.get("price") instanceof Number n ? Math.max(1, n.longValue()) : 1;
                items.add(new Item(mat, bundle, base));
            }
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            categories.put(id, new Category(id, s.getString("name", id), icon == null ? Material.CHEST : icon, items));
        }
    }

    // ---------- demand rolls ----------
    private double between(double a, double b) { return a + rnd.nextDouble() * Math.max(0, b - a); }
    private void roll(Item it) {
        var c = mcfg;
        it.up = rnd.nextDouble() < c.getDouble("up-chance", 0.5);
        it.multiplier = it.up ? between(c.getDouble("up-multiplier.min", 1.3), c.getDouble("up-multiplier.max", 2.0))
                              : between(c.getDouble("down-multiplier.min", 0.4), c.getDouble("down-multiplier.max", 0.8));
        it.price = Math.max(1, Math.round(it.base * it.multiplier));
        String k = it.up ? "stock.up" : "stock.down";
        int lo = c.getInt(k + ".min", 32), hi = Math.max(lo, c.getInt(k + ".max", 256));
        it.stock = lo + rnd.nextInt(hi - lo + 1);
    }

    public void refresh(boolean announce) {
        for (Category c : categories.values()) for (Item i : c.items()) roll(i);
        long period = Math.max(1, Math.round(mcfg.getDouble("refresh-hours", 24) * 3_600_000L));
        long now = System.currentTimeMillis();
        long next = nextRefresh > 0 ? nextRefresh : now;
        do { next += period; } while (next <= now); // no drift: stays on the original schedule
        nextRefresh = next;
        persist();
        if (announce && mcfg.getBoolean("announce-refresh", true)) plugin.broadcast("market-refreshed");
    }

    private void persist() {
        record Row(String m, int up, double mult, long price, int stock) {}
        List<Row> rows = new ArrayList<>();
        for (Category c : categories.values()) for (Item i : c.items()) rows.add(new Row(i.material.name(), i.up ? 1 : 0, i.multiplier, i.price, i.stock));
        long next = nextRefresh;
        plugin.db.call(c -> {
            c.setAutoCommit(false);
            try {
                try (var st = c.createStatement()) { st.executeUpdate("DELETE FROM market_state"); }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO market_state(material,up,multiplier,price,stock) VALUES(?,?,?,?,?)")) {
                    for (Row r : rows) { ps.setString(1, r.m()); ps.setInt(2, r.up()); ps.setDouble(3, r.mult()); ps.setLong(4, r.price()); ps.setInt(5, r.stock()); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = Database.bind(c, "INSERT OR REPLACE INTO meta(k,v) VALUES('market_next',?)", String.valueOf(next))) { ps.executeUpdate(); }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
            return null;
        });
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (System.currentTimeMillis() >= nextRefresh) refresh(true); }, 600L, 600L);
    }

    // ---------- selling ----------
    public int maxSaleBundles() { return Math.max(1, mcfg.getInt("max-sale-bundles", 64)); }

    /**
     * Sale = one atomic database transaction (credit the seller + take market stock, conditional on stock), THEN the items are
     * removed. If the items cannot be removed after payment (dropped/moved/player left) the payment and the stock are reversed.
     * Items are therefore never taken without payment, and money is never paid without items.
     *
     * @param wanted bundles to sell, or -1 for "as many as possible"
     */
    public void sell(Player p, Item it, int wanted, Runnable after) {
        Account a = plugin.economy.get(p);
        if (a == null) { plugin.getLogger().severe("Sale refused: no account loaded for " + p.getName()); plugin.send(p, "sell-failed"); after.run(); return; }
        if (it.stock <= 0) { plugin.send(p, "sell-soldout", "%item%", Text.pretty(it.material)); after.run(); return; }
        if (!plugin.economy.tryLock(a.id)) { plugin.send(p, "tx-busy"); after.run(); return; }

        long have = Items.count(p.getInventory(), it.material);
        long bundles = have / it.bundle;
        long n = wanted < 0 ? bundles : Math.min(wanted, bundles);
        n = Math.min(Math.min(n, it.stock), maxSaleBundles());
        if (n <= 0) { plugin.economy.unlock(a.id); plugin.send(p, "sell-none", "%item%", Text.pretty(it.material), "%bundle%", it.bundle); after.run(); return; }

        final long fn = n;
        final long units = Math.multiplyExact(fn, (long) it.bundle);
        final long pay;
        try { pay = Math.multiplyExact(fn, it.price); }
        catch (ArithmeticException e) { plugin.economy.unlock(a.id); plugin.send(p, "sell-failed"); after.run(); return; }
        if (a.dollars > plugin.economy.cap() - pay) { plugin.economy.unlock(a.id); plugin.send(p, "pay-cap"); after.run(); return; }

        final String mat = it.material.name();
        plugin.economy.commit("SELL", List.of(new Change(a.id, pay, null, "SELL")),
                c -> Database.run(c, Sql.MARKET_STOCK_TAKE, fn, mat, fn) == 1,
                () -> it.stock -= (int) fn,
                ok -> {
                    if (!ok) {
                        plugin.economy.unlock(a.id);
                        plugin.getLogger().warning("Sale of " + units + "x " + mat + " by " + p.getName() + " was not committed; no items taken.");
                        plugin.send(p, "sell-failed");
                        after.run();
                        return;
                    }
                    if (p.isOnline() && Items.remove(p.getInventory(), it.material, units)) {
                        plugin.economy.unlock(a.id);
                        plugin.send(p, "sell-done", "%amount%", units, "%item%", Text.pretty(it.material), "%price%", Money.fmt(pay));
                        after.run();
                    } else {
                        plugin.getLogger().warning("Items vanished before removal for " + p.getName() + " (" + units + "x " + mat + "); reversing payment of " + pay);
                        plugin.economy.commit("SELL_REVERSE", List.of(new Change(a.id, -pay, null, "SELL_REVERSE")),
                                c -> Database.run(c, Sql.MARKET_STOCK_RETURN, fn, mat) == 1,
                                () -> it.stock += (int) fn,
                                ok2 -> {
                                    if (!ok2) plugin.getLogger().severe("SALE REVERSAL FAILED for " + p.getName() + ": was paid " + pay + " without handing over items - check manually.");
                                    plugin.economy.unlock(a.id);
                                    plugin.send(p, "sell-failed");
                                    after.run();
                                });
                    }
                });
    }
}
