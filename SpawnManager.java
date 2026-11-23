package dev.knightsmp.mods.home;

import dev.knightsmp.mods.KnightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * /tps - the server's single general teleport. Sends the player to the fixed coordinates in config.yml (default 791, 73, 609)
 * in the configured world (default: the main world). Same safety rules as /home: not in combat, stand-still countdown,
 * cancelled by movement or damage. If the destination is blocked or in lava the player is NOT moved and is told why.
 */
public final class SpawnManager {
    private final KnightPlugin plugin;

    public SpawnManager(KnightPlugin plugin) { this.plugin = plugin; }

    private World world() {
        String name = plugin.getConfig().getString("tps.world", "");
        if (name != null && !name.isBlank()) return Bukkit.getWorld(name);
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    public void tps(Player p) {
        if (!p.hasPermission("knightsmp.tps")) { plugin.send(p, "no-permission"); return; }
        World w = world();
        if (w == null) { plugin.send(p, "tps-world"); return; }
        boolean blockInCombat = plugin.getConfig().getBoolean("tps.block-in-combat", true);
        if (blockInCombat && plugin.combat.inCombat(p)) { plugin.send(p, "tps-combat"); return; }
        if (plugin.countdown.isPending(p)) { plugin.send(p, "tps-busy"); return; }
        int delay = p.hasPermission("knightsmp.bypass.delay") ? 0 : Math.max(0, plugin.getConfig().getInt("tps.delay-seconds", 5));
        plugin.countdown.start(p, "tps", delay, plugin.getConfig().getBoolean("tps.cancel-on-move", true), blockInCombat, () -> arrive(p));
    }

    private void arrive(Player p) {
        if (!p.isOnline()) return;
        World w = world();                                           // looked up again: the world may have been unloaded meanwhile
        if (w == null) { plugin.send(p, "tps-world"); return; }
        final double x = plugin.getConfig().getDouble("tps.x", 791), y = plugin.getConfig().getDouble("tps.y", 73), z = plugin.getConfig().getDouble("tps.z", 609);
        final Location target = new Location(w, Math.floor(x) + 0.5, y, Math.floor(z) + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch());
        if (!w.getWorldBorder().isInside(target) || y < w.getMinHeight() || y > w.getMaxHeight()) { plugin.send(p, "tps-unsafe"); return; }
        w.getChunkAtAsync(target).whenComplete((chunk, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null || !p.isOnline()) { if (p.isOnline()) plugin.send(p, "tps-failed"); return; }
            if (!isSafe(target)) {
                plugin.getLogger().warning("/tps destination " + w.getName() + " " + x + ", " + y + ", " + z + " is blocked or in lava - " + p.getName() + " was not moved.");
                plugin.send(p, "tps-unsafe");
                return;
            }
            p.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok ->
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.send(p, Boolean.TRUE.equals(ok) ? "tps-done" : "tps-failed")));
        }));
    }

    /** Feet and head must be free, and the player must not land in lava or fall into the void. */
    private boolean isSafe(Location l) {
        Block feet = l.getBlock(), head = feet.getRelative(0, 1, 0), below = feet.getRelative(0, -1, 0);
        if (!feet.isPassable() || !head.isPassable()) return false;
        if (feet.getType() == Material.LAVA || head.getType() == Material.LAVA || below.getType() == Material.LAVA) return false;
        return l.getY() > l.getWorld().getMinHeight() + 1;
    }
}
