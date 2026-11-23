package dev.knightsmp.mods.home;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one "stand still for N seconds" countdown shared by /home and /tps, so a player can only be waiting for ONE teleport
 * at a time. Messages are looked up as "<kind>-countdown", "<kind>-countdown-bar", "<kind>-cancel-move|combat|damage".
 */
public final class TeleportCountdown {
    private static final class Pending { BukkitTask task; Location start; int left; String kind; }

    private final KnightPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();     // server thread only

    public TeleportCountdown(KnightPlugin plugin) { this.plugin = plugin; }

    public boolean isPending(Player p) { return pending.containsKey(p.getUniqueId()); }

    /** Runs {@code action} after the delay unless the player moves, is hit, enters combat or leaves. */
    public void start(Player p, String kind, int delaySeconds, boolean cancelOnMove, boolean blockInCombat, Runnable action) {
        if (delaySeconds <= 0) { action.run(); return; }
        Pending pd = new Pending();
        pd.start = p.getLocation().clone();
        pd.left = delaySeconds;
        pd.kind = kind;
        pending.put(p.getUniqueId(), pd);
        plugin.send(p, kind + "-countdown", "%seconds%", delaySeconds);
        pd.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { stop(p); return; }
            if (blockInCombat && plugin.combat.inCombat(p)) { cancelFor(p, "combat"); return; }
            if (cancelOnMove && moved(p, pd.start)) { cancelFor(p, "move"); return; }
            pd.left--;
            if (pd.left <= 0) { stop(p); action.run(); }
            else p.sendActionBar(Text.c(plugin.raw(kind + "-countdown-bar", "%seconds%", pd.left)));
        }, 20L, 20L);
    }

    private boolean moved(Player p, Location s) {
        Location n = p.getLocation();
        return n.getWorld() != s.getWorld() || n.getBlockX() != s.getBlockX() || n.getBlockY() != s.getBlockY() || n.getBlockZ() != s.getBlockZ();
    }

    private Pending stop(Player p) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd != null && pd.task != null) pd.task.cancel();
        return pd;
    }

    /** Cancels a running countdown and tells the player why ("move", "combat", "damage"); reason null = silent. */
    public void cancelFor(Player p, String reason) {
        Pending pd = stop(p);
        if (pd != null && reason != null) plugin.send(p, pd.kind + "-cancel-" + reason);
    }

    public void shutdown() { for (Pending pd : pending.values()) if (pd.task != null) pd.task.cancel(); pending.clear(); }
}
