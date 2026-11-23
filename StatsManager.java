package dev.knightsmp.mods.stats;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.storage.Sql;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Player statistics that are saved in SQLite: kills, deaths, kill streaks (stored on the account row),
 * playtime (counted by this plugin from join to quit, flushed every few minutes and on quit/shutdown),
 * plus totals read straight from the transaction history (earned from sales, spent in shops, payments, bounties).
 */
public final class StatsManager {
    /** Everything /stat shows that needs a database query. */
    public record Extra(Map<String, long[]> totals, long moneyRank, long killsRank) {
        public long sum(String type) { long[] v = totals.get(type); return v == null ? 0 : v[0]; }
        public long count(String type) { long[] v = totals.get(type); return v == null ? 0 : v[1]; }
    }

    private final KnightPlugin plugin;
    private BukkitTask flushTask;
    private final Set<UUID> deathCounted = new HashSet<>();   // players whose current death is already counted (cleared on respawn)

    public StatsManager(KnightPlugin plugin) { this.plugin = plugin; }

    public void start() {
        long every = Math.max(1, plugin.getConfig().getLong("stats.playtime-flush-minutes", 5)) * 60L * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushAll, every, every);
    }

    // ---------- playtime ----------
    public void onJoin(Player p) {
        Account a = plugin.economy.get(p);
        if (a != null) a.sessionStart = System.currentTimeMillis();
    }

    /** Counts the time since the last flush into the account and saves the increment. */
    public void flush(Account a) {
        if (a.sessionStart <= 0) return;
        long now = System.currentTimeMillis();
        long seconds = (now - a.sessionStart) / 1000;
        if (seconds <= 0) return;
        a.playtime += seconds;
        a.sessionStart += seconds * 1000;       // keep the sub-second remainder for the next flush
        plugin.db.update(Sql.PLAYTIME_ADD, seconds, a.id.toString());
    }
    public void flushAll() {
        for (Player p : Bukkit.getOnlinePlayers()) { Account a = plugin.economy.get(p); if (a != null) flush(a); }
    }
    public void onQuit(Player p) {
        Account a = plugin.economy.get(p);
        if (a == null) return;
        flush(a);
        a.sessionStart = 0;
        deathCounted.remove(p.getUniqueId());
    }
    public void shutdown() {
        if (flushTask != null) flushTask.cancel();
        flushAll();
    }

    /** Total playtime in seconds including the session that is still running. */
    public long playtime(Account a) {
        if (a.sessionStart <= 0) return a.playtime;
        return a.playtime + Math.max(0, (System.currentTimeMillis() - a.sessionStart) / 1000);
    }

    // ---------- deaths ----------
    public void onDeath(Player victim) {
        Account a = plugin.economy.get(victim);
        if (a == null || !deathCounted.add(victim.getUniqueId())) return;   // one death is counted once, even if the event repeats
        a.deaths++;
        a.streak = 0;
        plugin.db.update(Sql.DEATH_ADD, a.id.toString());
    }

    public void onRespawn(Player p) { deathCounted.remove(p.getUniqueId()); }

    // ---------- /payanim preference ----------
    public void setAnimOff(Account a, boolean off) {
        a.animOff = off;
        plugin.db.update(Sql.ANIM_SET, off ? 1 : 0, a.id.toString());
    }

    // ---------- /stat data ----------
    public CompletableFuture<Extra> load(UUID id) {
        return plugin.db.call(c -> {
            Map<String, long[]> totals = new HashMap<>();
            try (PreparedStatement ps = Database.bind(c, Sql.STAT_TOTALS, id.toString()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) totals.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
            }
            long money = 0, kills = 0;
            try (PreparedStatement ps = Database.bind(c, Sql.RANK_MONEY, id.toString()); ResultSet rs = ps.executeQuery()) { if (rs.next()) money = rs.getLong(1); }
            try (PreparedStatement ps = Database.bind(c, Sql.RANK_KILLS, id.toString()); ResultSet rs = ps.executeQuery()) { if (rs.next()) kills = rs.getLong(1); }
            return new Extra(totals, money, kills);
        });
    }
}
