package dev.knightsmp.mods.combat;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CombatManager {
    private final KnightPlugin plugin;
    private final Map<UUID, Long> tags = new ConcurrentHashMap<>();
    private BukkitTask task;

    public CombatManager(KnightPlugin plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("combat.enabled", true); }
    public int duration() { return plugin.getConfig().getInt("combat.duration-seconds", 30); }

    public boolean inCombat(Player p) {
        if (!enabled() || p.hasPermission("knightsmp.bypass.combat")) return false;
        Long t = tags.get(p.getUniqueId());
        return t != null && t > System.currentTimeMillis();
    }
    public long remaining(Player p) {
        Long t = tags.get(p.getUniqueId());
        if (t == null) return 0;
        return Math.max(0, (long) Math.ceil((t - System.currentTimeMillis()) / 1000.0));
    }

    /** Called for every valid PvP hit; (re)starts the timer. */
    public void tag(Player p) {
        if (!enabled() || p.hasPermission("knightsmp.bypass.combat")) return;
        boolean was = inCombat(p);
        tags.put(p.getUniqueId(), System.currentTimeMillis() + duration() * 1000L);
        if (!was) {
            plugin.send(p, "combat-tagged", "%seconds%", duration());
            plugin.teleport.cancelFor(p, true);
        }
        show(p);
    }
    public void untag(Player p) { tags.remove(p.getUniqueId()); }

    private void show(Player p) {
        p.sendActionBar(Text.c(plugin.raw("combat-actionbar", "%time%", remaining(p))));
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Long> e : tags.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) { if (e.getValue() <= now) tags.remove(e.getKey()); continue; }
                if (e.getValue() <= now) {
                    tags.remove(e.getKey());
                    plugin.send(p, "combat-ended");
                    p.sendActionBar(Text.c(plugin.raw("combat-ended-bar")));
                } else show(p);
            }
        }, 20L, 20L);
    }
    public void stop() { if (task != null) task.cancel(); tags.clear(); }
}
