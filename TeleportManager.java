package dev.knightsmp.mods.teleport;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** KnightTP: /tpa /tpaccept /tpdeny /home /sethome /delhome /back /spawn /setspawn /rtp */
public final class TeleportManager {
    public record Home(String world, double x, double y, double z, float yaw, float pitch) {
        public static Home of(Location l) { return new Home(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()); }
        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }
    private record Request(UUID from, long expires) {}
    private static final class Pending { BukkitTask task; Location start; int left; }

    private static final Set<Material> UNSAFE = EnumSet.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.FIRE, Material.SOUL_FIRE, Material.POINTED_DRIPSTONE, Material.SWEET_BERRY_BUSH,
            Material.LAVA, Material.WATER, Material.POWDER_SNOW);

    private final KnightPlugin plugin;
    private final Map<UUID, Request> requests = new HashMap<>(); // key = target
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Location> back = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();
    private Location spawn;

    public TeleportManager(KnightPlugin plugin) { this.plugin = plugin; }

    // ---------- spawn persistence ----------
    public void loadSpawn() {
        try {
            String v = plugin.db.call(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k='spawn'"); ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (v != null) {
                String[] p = v.split("\\|");
                World w = Bukkit.getWorld(p[0]);
                if (w != null) spawn = new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
            }
        } catch (Exception e) { plugin.getLogger().warning("Could not load spawn: " + e.getMessage()); }
    }
    public void setSpawn(Player p) {
        Location l = p.getLocation().clone();
        spawn = l;
        l.getWorld().setSpawnLocation(l);
        plugin.db.update("INSERT OR REPLACE INTO meta(k,v) VALUES('spawn',?)",
                l.getWorld().getName() + "|" + l.getX() + "|" + l.getY() + "|" + l.getZ() + "|" + l.getYaw() + "|" + l.getPitch());
        plugin.send(p, "spawn-set");
    }

    // ---------- shared checks ----------
    private int delay(String kind) { return plugin.getConfig().getInt("teleport." + kind + ".delay", 3); }
    private int cooldownSec(String kind) { return plugin.getConfig().getInt("teleport." + kind + ".cooldown", 0); }

    private boolean check(Player p, String kind) {
        if (plugin.combat.inCombat(p)) { plugin.send(p, "tp-combat"); return false; }
        if (pending.containsKey(p.getUniqueId())) { plugin.send(p, "tp-busy"); return false; }
        if (!p.hasPermission("knightsmp.bypass.cooldown")) {
            Long until = cooldowns.get(p.getUniqueId() + ":" + kind);
            long now = System.currentTimeMillis();
            if (until != null && until > now) { plugin.send(p, "tp-cooldown", "%seconds%", (until - now + 999) / 1000); return false; }
        }
        return true;
    }
    private void setCooldown(Player p, String kind) {
        int cd = cooldownSec(kind);
        if (cd > 0 && !p.hasPermission("knightsmp.bypass.cooldown")) cooldowns.put(p.getUniqueId() + ":" + kind, System.currentTimeMillis() + cd * 1000L);
    }
    private boolean moved(Player p, Location s) {
        Location n = p.getLocation();
        return n.getWorld() != s.getWorld() || n.getBlockX() != s.getBlockX() || n.getBlockY() != s.getBlockY() || n.getBlockZ() != s.getBlockZ();
    }

    // ---------- core: countdown + teleport ----------
    private void start(Player p, String kind, Supplier<Location> dest) {
        int d = p.hasPermission("knightsmp.bypass.delay") ? 0 : delay(kind);
        if (d <= 0) { doTeleport(p, kind, dest); return; }
        Pending pd = new Pending();
        pd.start = p.getLocation().clone();
        pd.left = d;
        pending.put(p.getUniqueId(), pd);
        plugin.send(p, "tp-countdown", "%seconds%", d);
        boolean cancelOnMove = plugin.getConfig().getBoolean("teleport.cancel-on-move", true);
        pd.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { pd.task.cancel(); pending.remove(p.getUniqueId()); return; }
            if (plugin.combat.inCombat(p)) { cancelPending(p, "tp-cancel-combat"); return; }
            if (cancelOnMove && moved(p, pd.start)) { cancelPending(p, "tp-cancel-move"); return; }
            pd.left--;
            if (pd.left <= 0) { pd.task.cancel(); pending.remove(p.getUniqueId()); doTeleport(p, kind, dest); }
            else p.sendActionBar(Text.c(plugin.raw("tp-countdown-bar", "%seconds%", pd.left)));
        }, 20L, 20L);
    }

    private void doTeleport(Player p, String kind, Supplier<Location> destSup) {
        if (plugin.combat.inCombat(p)) { plugin.send(p, "tp-cancel-combat"); return; }
        Location dest = destSup.get();
        if (dest == null || dest.getWorld() == null) { plugin.send(p, "tp-failed"); return; }
        Location from = p.getLocation().clone();
        p.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (Boolean.TRUE.equals(ok)) {
                        back.put(p.getUniqueId(), from);
                        if (!kind.equals("tpa")) setCooldown(p, kind);
                        plugin.send(p, "tp-done");
                    } else plugin.send(p, "tp-failed");
                }));
    }

    public void cancelPending(Player p, String reasonKey) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd != null) { if (pd.task != null) pd.task.cancel(); if (reasonKey != null) plugin.send(p, reasonKey); }
    }
    public boolean isPending(Player p) { return pending.containsKey(p.getUniqueId()); }

    /** Cancels countdowns and requests involving a player (used when combat starts or the player leaves). */
    public void cancelFor(Player p, boolean notify) {
        cancelPending(p, notify ? "tp-cancel-combat" : null);
        requests.remove(p.getUniqueId());
        for (Iterator<Request> it = requests.values().iterator(); it.hasNext(); ) if (it.next().from().equals(p.getUniqueId())) it.remove();
    }
    public void shutdown() { for (Pending pd : pending.values()) if (pd.task != null) pd.task.cancel(); pending.clear(); requests.clear(); }
    public void setBack(Player p, Location l) { back.put(p.getUniqueId(), l.clone()); }

    // ---------- /tpa /tpaccept /tpdeny ----------
    public void tpa(Player from, Player to) {
        if (from.getUniqueId().equals(to.getUniqueId())) { plugin.send(from, "tpa-self"); return; }
        if (!check(from, "tpa")) return;
        if (plugin.combat.inCombat(to) && plugin.getConfig().getBoolean("teleport.block-requests-to-combat-players", false)) { plugin.send(from, "tp-combat"); return; }
        long exp = plugin.getConfig().getLong("teleport.tpa.request-expire-seconds", 60);
        requests.put(to.getUniqueId(), new Request(from.getUniqueId(), System.currentTimeMillis() + exp * 1000));
        setCooldown(from, "tpa");
        plugin.send(from, "tpa-sent", "%player%", to.getName());
        Component accept = Text.c(plugin.raw("tpa-accept-btn")).clickEvent(ClickEvent.runCommand("/tpaccept"))
                .hoverEvent(HoverEvent.showText(Text.c("&aAccept")));
        Component deny = Text.c(plugin.raw("tpa-deny-btn")).clickEvent(ClickEvent.runCommand("/tpdeny"))
                .hoverEvent(HoverEvent.showText(Text.c("&cDeny")));
        to.sendMessage(plugin.msg("tpa-received", "%player%", from.getName(), "%seconds%", exp).append(Component.space()).append(accept).append(Component.space()).append(deny));
    }

    private Request take(Player target) {
        Request r = requests.remove(target.getUniqueId());
        if (r == null) { plugin.send(target, "tpa-none"); return null; }
        if (r.expires() < System.currentTimeMillis()) { plugin.send(target, "tpa-expired"); return null; }
        return r;
    }
    public void accept(Player target) {
        Request r = take(target);
        if (r == null) return;
        Player from = Bukkit.getPlayer(r.from());
        if (from == null) { plugin.send(target, "tpa-offline"); return; }
        if (plugin.combat.inCombat(from)) { plugin.send(target, "tpa-requester-combat", "%player%", from.getName()); plugin.send(from, "tp-combat"); return; }
        if (pending.containsKey(from.getUniqueId())) { plugin.send(target, "tp-busy"); return; }
        plugin.send(target, "tpa-accepted", "%player%", from.getName());
        UUID tid = target.getUniqueId();
        start(from, "tpa", () -> { Player t = Bukkit.getPlayer(tid); return t == null ? null : t.getLocation(); });
    }
    public void deny(Player target) {
        Request r = take(target);
        if (r == null) return;
        plugin.send(target, "tpa-denied");
        Player from = Bukkit.getPlayer(r.from());
        if (from != null) plugin.send(from, "tpa-denied-sender", "%player%", target.getName());
    }

    // ---------- homes ----------
    public void home(Player p) {
        Account a = plugin.economy.get(p);
        if (a == null || a.home == null) { plugin.send(p, "home-none"); return; }
        Location l = a.home.toLocation();
        if (l == null) { plugin.send(p, "home-world"); return; }
        if (!check(p, "home")) return;
        start(p, "home", () -> l);
    }
    public void setHome(Player p) {
        Account a = plugin.economy.get(p);
        if (a == null) return;
        Location l = p.getLocation();
        a.home = Home.of(l);
        plugin.db.update("INSERT OR REPLACE INTO homes(uuid,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?)",
                a.id.toString(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), (double) l.getYaw(), (double) l.getPitch());
        plugin.send(p, "home-set");
    }
    public void delHome(Player p) {
        Account a = plugin.economy.get(p);
        if (a == null || a.home == null) { plugin.send(p, "home-none"); return; }
        a.home = null;
        plugin.db.update("DELETE FROM homes WHERE uuid=?", p.getUniqueId().toString());
        plugin.send(p, "home-deleted");
    }

    // ---------- back / spawn ----------
    public void back(Player p) {
        Location b = back.get(p.getUniqueId());
        if (b == null || b.getWorld() == null) { plugin.send(p, "back-none"); return; }
        if (!check(p, "back")) return;
        start(p, "back", () -> b);
    }
    public void spawn(Player p) {
        if (!check(p, "spawn")) return;
        start(p, "spawn", () -> spawn != null ? spawn : Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    // ---------- /rtp ----------
    public void rtp(Player p) {
        if (!check(p, "rtp")) return;
        if (searching.contains(p.getUniqueId())) { plugin.send(p, "tp-busy"); return; }
        World w = null;
        String name = plugin.getConfig().getString("teleport.rtp.world", "");
        if (name != null && !name.isEmpty()) w = Bukkit.getWorld(name);
        if (w == null) w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) w = Bukkit.getWorlds().get(0);
        if (w.getEnvironment() != World.Environment.NORMAL) { plugin.send(p, "rtp-failed"); return; }
        searching.add(p.getUniqueId());
        plugin.send(p, "rtp-searching");
        attempt(p, w, plugin.getConfig().getInt("teleport.rtp.attempts", 12));
    }

    private void attempt(Player p, World w, int tries) {
        if (!p.isOnline()) { searching.remove(p.getUniqueId()); return; }
        if (tries <= 0) { searching.remove(p.getUniqueId()); plugin.send(p, "rtp-failed"); return; }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double min = plugin.getConfig().getDouble("teleport.rtp.min-radius", 500);
        double max = plugin.getConfig().getDouble("teleport.rtp.max-radius", 5000);
        double borderHalf = w.getWorldBorder().getSize() / 2.0 - 16;
        max = Math.min(max, borderHalf);
        if (min >= max) min = 0;
        double cx = plugin.getConfig().getDouble("teleport.rtp.center-x", 0), cz = plugin.getConfig().getDouble("teleport.rtp.center-z", 0);
        double ang = r.nextDouble() * Math.PI * 2, dist = min + r.nextDouble() * (max - min);
        final int x = (int) Math.round(cx + Math.cos(ang) * dist), z = (int) Math.round(cz + Math.sin(ang) * dist);
        w.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null || !p.isOnline()) { attempt(p, w, tries - 1); return; }
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block ground = w.getBlockAt(x, y, z);
            Location dest = new Location(w, x + 0.5, y + 1, z + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch());
            if (!safe(ground) || !w.getWorldBorder().isInside(dest)) { attempt(p, w, tries - 1); return; }
            searching.remove(p.getUniqueId());
            if (plugin.combat.inCombat(p)) { plugin.send(p, "tp-combat"); return; }
            start(p, "rtp", () -> dest);
        }));
    }

    private boolean safe(Block ground) {
        Material m = ground.getType();
        if (!m.isSolid() || UNSAFE.contains(m) || ground.isLiquid()) return false;
        Block a = ground.getRelative(0, 1, 0), b = ground.getRelative(0, 2, 0);
        return a.isPassable() && b.isPassable() && !a.isLiquid() && !b.isLiquid();
    }

    public void onQuit(Player p) { cancelFor(p, false); searching.remove(p.getUniqueId()); }
}
