package dev.knightsmp.mods.hud;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.util.Text;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sidebar HUD: Money / Bounty / Kills. Vanilla Minecraft (and Geyser for Bedrock) only offers the sidebar on the
 * RIGHT of the screen; there is no server-side way to put it on the left without a client mod or resource pack.
 * Updates are event-driven (only when a value changes), batched to one refresh per player per tick, and a line is
 * only re-sent when its text actually changed.
 */
public final class HudManager {
    private static final int MAX_LINES = 8;

    private static final class Board {
        Scoreboard scoreboard;
        Objective objective;
        int lines;
        final String[] last = new String[MAX_LINES];
    }

    private final KnightPlugin plugin;
    private final Map<UUID, Board> boards = new HashMap<>();   // server thread only
    private final Set<UUID> dirty = new HashSet<>();

    public HudManager(KnightPlugin plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("hud.enabled", true); }

    private static String entry(int i) { return "\u00a7" + "0123456789abcdef".charAt(i) + "\u00a7r"; }

    public void attach(Player p) {
        if (!enabled() || boards.containsKey(p.getUniqueId())) return;
        List<String> template = plugin.getConfig().getStringList("hud.lines");
        int lines = Math.min(MAX_LINES, template.size());
        if (lines == 0) return;
        Board b = new Board();
        b.lines = lines;
        b.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        b.objective = b.scoreboard.registerNewObjective("knighthud", Criteria.DUMMY, Text.c(plugin.getConfig().getString("hud.title", "&5&lKnightSMP")));
        b.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        b.objective.numberFormat(NumberFormat.blank());          // hide the red score numbers
        for (int i = 0; i < lines; i++) {
            Team t = b.scoreboard.registerNewTeam("hud" + i);
            t.addEntry(entry(i));
            b.objective.getScore(entry(i)).setScore(lines - i);   // first line on top
        }
        boards.put(p.getUniqueId(), b);
        plugin.bounty.syncBoard(b.scoreboard);                    // keep the WANTED #1 name-tag prefix on this scoreboard too
        p.setScoreboard(b.scoreboard);
        refresh(p);
    }

    public void detach(Player p) {
        boards.remove(p.getUniqueId());
        dirty.remove(p.getUniqueId());
    }

    /** Marks a player's HUD as out of date; the actual update happens once, next tick. */
    public void touch(UUID id) {
        if (!boards.containsKey(id) || !dirty.add(id)) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                dirty.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null) refresh(p);
            });
        } catch (Exception e) { dirty.remove(id); /* plugin disabling */ }
    }

    public void refresh(Player p) {
        Board b = boards.get(p.getUniqueId());
        Account a = plugin.economy.get(p);
        if (b == null || a == null) return;
        List<String> template = plugin.getConfig().getStringList("hud.lines");
        for (int i = 0; i < b.lines && i < template.size(); i++) {
            String text = template.get(i)
                    .replace("%money%", Text.num(a.dollars))
                    .replace("%bounty%", Text.num(a.bounty))
                    .replace("%kills%", Text.num(a.kills));
            if (text.equals(b.last[i])) continue;                 // unchanged line: nothing sent
            b.last[i] = text;
            Team t = b.scoreboard.getTeam("hud" + i);
            if (t != null) t.prefix(Text.c(text));
        }
    }

    /** Re-applies the title/lines from config to everybody (used by /knight reload). */
    public void reloadAll() {
        List<UUID> ids = new ArrayList<>(boards.keySet());
        boards.clear();
        dirty.clear();
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            attach(p);
        }
        if (!enabled()) for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        else for (Player p : Bukkit.getOnlinePlayers()) attach(p);
    }

    public Collection<Scoreboard> boards() {
        List<Scoreboard> out = new ArrayList<>();
        for (Board b : boards.values()) out.add(b.scoreboard);
        return out;
    }

    public void shutdown() {
        for (UUID id : boards.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
        dirty.clear();
    }
}
