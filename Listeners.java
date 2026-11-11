package dev.knightsmp.mods.listeners;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.gui.Menu;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class Listeners implements Listener {
    private final KnightPlugin plugin;
    public Listeners(KnightPlugin plugin) { this.plugin = plugin; }

    // ---------- account lifecycle ----------
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        try { plugin.economy.loadBlocking(e.getUniqueId(), e.getName()); }
        catch (Exception ex) {
            plugin.getLogger().severe("Could not load account for " + e.getName() + ": " + ex.getMessage());
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Text.c("&cKnightSMP could not load your data. Please try again shortly."));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.bounty.onJoin(p);
        plugin.kits.giveStarter(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // Combat-log punishment: only for genuine disconnects/timeouts, never for server shutdown or admin kicks.
        if (plugin.combat.inCombat(p) && !plugin.isStopping()) {
            PlayerQuitEvent.QuitReason r = e.getReason();
            boolean genuine = r == PlayerQuitEvent.QuitReason.DISCONNECTED
                    || (r == PlayerQuitEvent.QuitReason.TIMED_OUT && plugin.getConfig().getBoolean("combat.punish-timeouts", true));
            if (genuine) {
                if (plugin.getConfig().getBoolean("combat.logout.broadcast", true)) plugin.broadcast("combat-logout-broadcast", "%player%", p.getName());
                if ("KILL".equalsIgnoreCase(plugin.getConfig().getString("combat.logout.punishment", "KILL"))) p.setHealth(0.0); // drops items, counts as PvP death
                for (String cmd : plugin.getConfig().getStringList("combat.logout.commands"))
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            }
        }
        plugin.combat.untag(p);
        plugin.teleport.onQuit(p);
        var acc = plugin.economy.get(p);
        if (acc != null) plugin.economy.save(acc);
    }

    // ---------- combat ----------
    private Player attacker(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player s) return s;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player atk = attacker(e.getDamager());
        if (atk == null || atk.getUniqueId().equals(victim.getUniqueId()) || e.getFinalDamage() <= 0) return;
        plugin.combat.tag(atk);
        plugin.combat.tag(victim);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getFinalDamage() > 0 && plugin.teleport.isPending(p)
                && plugin.getConfig().getBoolean("teleport.cancel-on-damage", true))
            plugin.teleport.cancelPending(p, "tp-cancel-damage");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        plugin.bounty.handleDeath(victim);
        plugin.combat.untag(victim);
        if (plugin.getConfig().getBoolean("teleport.back-on-death", true)) plugin.teleport.setBack(victim, victim.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!plugin.combat.inCombat(p)) return;
        String msg = e.getMessage().substring(1).trim();
        if (msg.isEmpty()) return;
        String label = msg.split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        Set<String> blocked = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("combat.blocked-commands")) blocked.add(s.toLowerCase(Locale.ROOT));
        boolean hit = blocked.contains(label);
        if (!hit) {
            Command c = Bukkit.getCommandMap().getCommand(label); // resolves aliases of other plugins
            if (c != null) {
                if (blocked.contains(c.getName().toLowerCase(Locale.ROOT))) hit = true;
                for (String al : c.getAliases()) if (blocked.contains(al.toLowerCase(Locale.ROOT))) hit = true;
            }
        }
        if (hit) { e.setCancelled(true); plugin.send(p, "combat-command-blocked", "%seconds%", plugin.combat.remaining(p)); }
    }

    // ---------- GUI ----------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true); // nothing may be moved in or out of a menu - prevents duplication
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getWhoClicked() instanceof Player) menu.click(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
