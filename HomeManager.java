package dev.knightsmp.mods.home;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.storage.Sql;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

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

    private final KnightPlugin plugin;

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
        boolean blockInCombat = plugin.getConfig().getBoolean("home.block-in-combat", true);
        if (blockInCombat && plugin.combat.inCombat(p)) { plugin.send(p, "home-combat"); return; }
        if (plugin.countdown.isPending(p)) { plugin.send(p, "home-busy"); return; }
        int delay = p.hasPermission("knightsmp.bypass.delay") ? 0 : Math.max(0, plugin.getConfig().getInt("home.delay-seconds", 5));
        plugin.countdown.start(p, "home", delay, plugin.getConfig().getBoolean("home.cancel-on-move", true), blockInCombat, () -> go(p, dest));
    }

    private void go(Player p, Location dest) {
        if (!p.isOnline()) return;
        p.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok ->
                Bukkit.getScheduler().runTask(plugin, () -> plugin.send(p, Boolean.TRUE.equals(ok) ? "home-done" : "home-failed")));
    }
}
