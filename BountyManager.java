package dev.knightsmp.mods.bounty;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.Currency;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BountyManager {
    public record Entry(UUID id, String name, long bounty) {}
    private record Kill(UUID killer, UUID victim, long time) {}
    private static final String TEAM = "knightsmp_wanted";

    private final KnightPlugin plugin;
    private final Deque<Kill> kills = new ArrayDeque<>();
    private UUID wantedId;
    private String wantedName;

    public BountyManager(KnightPlugin plugin) { this.plugin = plugin; }

    private boolean enabled() { return plugin.getConfig().getBoolean("bounty.enabled", true); }

    // ---------- leaderboard ----------
    public CompletableFuture<List<Entry>> leaderboard(int limit, long minBounty) {
        return plugin.db.call(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = Database.bind(c, "SELECT uuid,name,bounty FROM accounts WHERE bounty>=? ORDER BY bounty DESC, rowid ASC LIMIT ?", minBounty, limit);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Entry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
            }
            return out;
        });
    }

    // ---------- PvP kill handling ----------
    /** Only genuine PvP counts: last damage must come from a player (melee or projectile). Environment/mob deaths are ignored. */
    public Player pvpKiller(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent ed) {
            Entity d = ed.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Player s) return s;
            if (d instanceof Player pl) return pl;
        }
        return null;
    }

    public void handleDeath(Player victim) {
        if (!enabled()) return;
        Player killer = pvpKiller(victim);
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        Account va = plugin.economy.get(victim), ka = plugin.economy.get(killer);
        if (va == null || ka == null) return;

        long victimBounty = va.bounty;
        va.bounty = 0; // PvP death always resets the victim
        String blocked = abuseCheck(killer, victim);
        if (blocked == null) {
            long max = plugin.getConfig().getLong("bounty.max-bounty", 1_000_000L);
            ka.bounty = Math.min(max, ka.bounty + plugin.getConfig().getLong("bounty.increase-per-kill", 5));
            kills.addLast(new Kill(killer.getUniqueId(), victim.getUniqueId(), System.currentTimeMillis()));
            prune();
            plugin.send(killer, "bounty-gain", "%bounty%", Text.num(ka.bounty));
            if (victimBounty > 0) {
                Currency cur = Currency.parse(plugin.getConfig().getString("bounty.reward-currency", "iron"));
                if (cur == null) cur = Currency.IRON;
                long reward = Math.round(victimBounty * plugin.getConfig().getDouble("bounty.reward-multiplier", 1.0));
                plugin.economy.reward(killer, cur, reward, "BOUNTY");
                plugin.broadcast("bounty-claimed-broadcast", "%killer%", killer.getName(), "%victim%", victim.getName(), "%reward%", cur.fmt(reward));
            }
        } else {
            plugin.send(killer, blocked);
        }
        if (victimBounty > 0) plugin.send(victim, "bounty-reset", "%bounty%", Text.num(victimBounty));
        plugin.economy.save(va);
        plugin.economy.save(ka);
        refreshRole();
    }

    private void prune() {
        long keep = plugin.getConfig().getLong("bounty.repeat-window-seconds", 3600) * 1000L;
        long now = System.currentTimeMillis();
        while (!kills.isEmpty() && now - kills.peekFirst().time() > keep) kills.pollFirst();
    }

    /** Returns a message key if this kill must not count, else null. */
    private String abuseCheck(Player killer, Player victim) {
        long now = System.currentTimeMillis();
        long minAge = plugin.getConfig().getLong("bounty.min-account-age-minutes", 10) * 60_000L;
        if (minAge > 0 && (now - killer.getFirstPlayed() < minAge || now - victim.getFirstPlayed() < minAge)) return "bounty-blocked-age";

        if (plugin.getConfig().getBoolean("bounty.block-same-ip", true)
                && killer.getAddress() != null && victim.getAddress() != null) {
            InetAddress a = killer.getAddress().getAddress(), b = victim.getAddress().getAddress();
            // loopback/private addresses are shared by Geyser/proxy setups, so they say nothing about alts
            if (a != null && a.equals(b) && !a.isLoopbackAddress() && !a.isAnyLocalAddress() && !a.isSiteLocalAddress()) return "bounty-blocked-ip";
        }
        prune();
        int max = plugin.getConfig().getInt("bounty.max-repeat-kills", 1);
        int n = 0;
        for (Kill k : kills) {
            boolean pair = (k.killer().equals(killer.getUniqueId()) && k.victim().equals(victim.getUniqueId()))
                    || (k.killer().equals(victim.getUniqueId()) && k.victim().equals(killer.getUniqueId()));
            if (pair) n++;
        }
        return n >= max ? "bounty-blocked-repeat" : null;
    }

    // ---------- WANTED #1 role ----------
    public void refreshRole() {
        if (!plugin.getConfig().getBoolean("wanted-role.enabled", true)) return;
        long min = Math.max(1, plugin.getConfig().getLong("wanted-role.min-bounty", 1));
        leaderboard(1, min).thenAccept(list -> {
            try { Bukkit.getScheduler().runTask(plugin, () -> apply(list.isEmpty() ? null : list.get(0))); }
            catch (Exception ignored) { /* plugin disabling */ }
        });
    }

    private Team team() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(TEAM);
        if (t == null) t = sb.registerNewTeam(TEAM);
        t.prefix(Text.c(plugin.getConfig().getString("wanted-role.display", "&5&lKnightSMP - WANTED #1") + " &r"));
        return t;
    }

    private void apply(Entry top) {
        UUID newTop = top == null ? null : top.id();
        if (!Objects.equals(newTop, wantedId)) {
            if (wantedId != null) {
                strip(wantedId, wantedName);
                runCommands("wanted-role.on-lose-commands", wantedName);
            }
            wantedId = newTop;
            wantedName = top == null ? null : top.name();
            if (newTop != null) {
                runCommands("wanted-role.on-gain-commands", wantedName);
                if (plugin.getConfig().getBoolean("wanted-role.announce", true))
                    plugin.broadcast("wanted-gain-broadcast", "%player%", wantedName, "%role%", plugin.getConfig().getString("wanted-role.display", ""));
            }
        }
        if (wantedId != null) { Player p = Bukkit.getPlayer(wantedId); if (p != null) decorate(p); }
    }

    private void decorate(Player p) {
        Team t = team();
        if (!t.hasEntry(p.getName())) t.addEntry(p.getName());
        p.playerListName(Text.c(plugin.getConfig().getString("wanted-role.display", "") + " &r" + p.getName()));
    }
    private void strip(UUID id, String name) {
        if (name != null) { Team t = team(); if (t.hasEntry(name)) t.removeEntry(name); }
        Player p = Bukkit.getPlayer(id);
        if (p != null) p.playerListName(null);
    }
    private void runCommands(String path, String name) {
        if (name == null) return;
        for (String cmd : plugin.getConfig().getStringList(path))
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", name));
    }

    public void onJoin(Player p) {
        if (wantedId != null && wantedId.equals(p.getUniqueId())) decorate(p);
        refreshRole();
    }
    public void shutdown() {
        if (wantedId != null) strip(wantedId, wantedName);
    }

    // ---------- admin ----------
    public void setBounty(Account a, long amount) {
        a.bounty = Math.max(0, amount);
        plugin.economy.save(a);
        refreshRole();
    }
}
