package dev.knightsmp.mods.home;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.storage.Sql;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** /sethome /home /deletehome - one saved home per player, stored in SQLite. (There is no random teleport.) */
public final class HomeManager {
    public record Home(String world, double x, double y, double z, float yaw, float pitch) {
        public static Home of(Location l) { return new Home(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()); }
        /** Null if the world is not loaded. */
        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }
    private static final class Pending { BukkitTask task; Location start; int left; }

    private final KnightPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();   // server thread only

    public HomeManager(KnightPlugin plugin) { this.plugin = plugin; }

    /** Returns a message key if the location cannot be saved as a home, else null. */
    private String invalid(Location l) {
        World w = l.getWorld();
        if (w == null) return "home-invalid";
        if (!Double.isFinite(l.getX()) || !Double.isFinite(l.getY()) || !Double.isFinite(l.getZ())) return "home-invalid";
        if (l.getY() < w.getMinHeight() || l.getY() > w.getMaxHeight()) return "home-invalid";
        if (!w.getWorldBorder().isInside(l)) return "home-invalid";
        return null;
    }

    public void setHome(Player p) {
        Account a = plugin.economy.get(p);
        if (a == null) { plugin.send(p, "db-error"); return; }
        Location l = p.getLocation();
        String bad = invalid(l);
        if (bad != null) { plugin.send(p, bad); return; }
        final Home h = Home.of(l);
        final String id = a.id.toString();
        // saved first, then shown as set: a failed write never reports success
        plugin.db.call(c -> Database.run(c, Sql.HOME_SET, id, h.world(), h.x(), h.y(), h.z(), (double) h.yaw(), (double) h.pitch()) == 1)
                .whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null || !Boolean.TRUE.equals(ok)) { plugin.send(p, "home-save-failed"); return; }
                    a.home = h;
                    plugin.send(p, "home-set");
                }));
    }

    public void deleteHome(Player p) {
        Account a = plugin.economy.get(p);
        if (a == null) { plugin.send(p, "db-error"); return; }
        if (a.home == null) { plugin.send(p, "home-none"); return; }
        final String id = a.id.toString();
        plugin.db.call(c -> Database.run(c, Sql.HOME_DELETE, id) >= 0)
                .whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null || !Boolean.TRUE.equals(ok)) { plugin.send(p, "home-save-failed"); return; }
                    a.home = null;
                    plugin.send(p, "home-deleted");
                }));
    }

    public void home(Player p) {
        Account a = plugin.economy.get(p);
        if (a == null) { plugin.send(p, "db-error"); return; }
        if (a.home == null) { plugin.send(p, "home-none"); return; }
        Location dest = a.home.toLocation();
        if (dest == null) { plugin.send(p, "home-world"); return; }
        if (plugin.combat.inCombat(p) && plugin.getConfig().getBoolean("home.block-in-combat", true)) { plugin.send(p, "home-combat"); return; }
        if (pending.containsKey(p.getUniqueId())) { plugin.send(p, "home-busy"); return; }

        int delay = p.hasPermission("knightsmp.bypass.delay") ? 0 : Math.max(0, plugin.getConfig().getInt("home.delay-seconds", 5));
        if (delay == 0) { go(p, dest); return; }
        Pending pd = new Pending();
        pd.start = p.getLocation().clone();
        pd.left = delay;
        pending.put(p.getUniqueId(), pd);
        plugin.send(p, "home-countdown", "%seconds%", delay);
        final boolean cancelOnMove = plugin.getConfig().getBoolean("home.cancel-on-move", true);
        pd.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { cancel(p, null); return; }
            if (plugin.combat.inCombat(p) && plugin.getConfig().getBoolean("home.block-in-combat", true)) { cancel(p, "home-cancel-combat"); return; }
            if (cancelOnMove && moved(p, pd.start)) { cancel(p, "home-cancel-move"); return; }
            pd.left--;
            if (pd.left <= 0) { cancel(p, null); go(p, dest); }
            else p.sendActionBar(Text.c(plugin.raw("home-countdown-bar", "%seconds%", pd.left)));
        }, 20L, 20L);
    }

    private void go(Player p, Location dest) {
        if (!p.isOnline()) return;
        p.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok ->
                Bukkit.getScheduler().runTask(plugin, () -> plugin.send(p, Boolean.TRUE.equals(ok) ? "home-done" : "home-failed")));
    }

    private boolean moved(Player p, Location s) {
        Location n = p.getLocation();
        return n.getWorld() != s.getWorld() || n.getBlockX() != s.getBlockX() || n.getBlockY() != s.getBlockY() || n.getBlockZ() != s.getBlockZ();
    }

    /** Stops a pending /home countdown; reasonKey (nullable) is sent to the player. */
    public void cancel(Player p, String reasonKey) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null) return;
        if (pd.task != null) pd.task.cancel();
        if (reasonKey != null) plugin.send(p, reasonKey);
    }
    public boolean isPending(Player p) { return pending.containsKey(p.getUniqueId()); }
    public void onQuit(Player p) { cancel(p, null); }
    public void shutdown() { for (Pending pd : pending.values()) if (pd.task != null) pd.task.cancel(); pending.clear(); }
}
