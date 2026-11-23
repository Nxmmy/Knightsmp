package dev.knightsmp.mods.settings;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.storage.Sql;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player on/off settings shown in /settings. To add a setting later, write one small subclass of {@link Setting} and
 * register it in {@link #SettingsManager(KnightPlugin)} - the menu, saving, loading and feedback need no changes.
 * Values are stored per player UUID in the player_settings table (key/value), so new settings never need a schema change.
 */
public final class SettingsManager {

    /** One on/off setting. "Open" = on. */
    public abstract static class Setting {
        public final String key, name;
        public final Material icon;
        public final List<String> description;
        public final boolean defaultOpen;

        protected Setting(String key, String name, Material icon, boolean defaultOpen, String... description) {
            this.key = key; this.name = name; this.icon = icon; this.defaultOpen = defaultOpen; this.description = List.of(description);
        }

        public boolean isOpen(Account a) {
            String v = a.settings.get(key);
            return v == null ? defaultOpen : v.equals("open");
        }
        /** Writes the new value (runs on the database thread). */
        public boolean persist(Connection c, Account a, boolean open) throws Exception {
            return Database.run(c, Sql.SETTING_SET, a.id.toString(), key, open ? "open" : "closed") == 1;
        }
        /** Updates the in-memory copy after the database write succeeded (server thread). */
        public void cache(Account a, boolean open) { a.settings.put(key, open ? "open" : "closed"); }
        /** Puts the setting into effect for a live player (server thread). */
        public void apply(Player p, boolean open) {}
    }

    private final KnightPlugin plugin;
    private final Map<String, Setting> settings = new LinkedHashMap<>();
    private final Set<UUID> busy = new HashSet<>();     // server thread only: one change per player at a time

    public SettingsManager(KnightPlugin plugin) {
        this.plugin = plugin;

        // --- Mob Spawn: PER PLAYER, not server-wide. Paper's Player#setAffectsSpawning controls whether THIS player counts
        // when the server decides where natural mobs spawn. Closed = you no longer attract natural spawns; everyone else is unaffected.
        register(new Setting("mob-spawn", "Mob Spawn", Material.ZOMBIE_HEAD, true,
                "Open: monsters can spawn naturally around you.",
                "Closed: you stop attracting natural mob spawns.",
                "Only affects YOU - other players are not changed.",
                "Mobs still spawn near you if another player nearby", "has Mob Spawn open. Existing mobs stay.") {
            @Override public void apply(Player p, boolean open) { p.setAffectsSpawning(open); }
        });

        // --- Payment Popup: the same preference as /payanim (stored in the account row, so existing choices are kept).
        register(new Setting("payment-popup", "Payment Popup", Material.GOLD_INGOT, true,
                "Open: show the golden popup when someone pays you.",
                "Closed: you only get the chat message.") {
            @Override public boolean isOpen(Account a) { return !a.animOff; }
            @Override public boolean persist(Connection c, Account a, boolean open) throws Exception {
                return Database.run(c, Sql.ANIM_SET, open ? 0 : 1, a.id.toString()) == 1;
            }
            @Override public void cache(Account a, boolean open) { a.animOff = !open; }
        });
    }

    public void register(Setting s) {
        if (settings.size() >= 14) { plugin.getLogger().warning("Setting '" + s.key + "' ignored: the /settings menu holds at most 14 settings."); return; }
        settings.put(s.key, s);
    }
    public List<Setting> all() { return new ArrayList<>(settings.values()); }

    /** Applies every saved setting to a player who just joined (Paper forgets per-player flags on reconnect). */
    public void applyAll(Player p) {
        Account a = plugin.economy.get(p);
        if (a == null) return;
        for (Setting s : settings.values()) {
            try { s.apply(p, s.isOpen(a)); }
            catch (Exception e) { plugin.getLogger().warning("Could not apply setting " + s.key + " for " + p.getName() + ": " + e); }
        }
    }

    /**
     * Flips one setting for ONE player. Saved first, then shown/applied only if the save succeeded.
     * A second click while the first is still being saved is ignored.
     */
    public void toggle(Player p, Setting s, Runnable after) {
        Account a = plugin.economy.get(p);
        if (a == null) { plugin.send(p, "db-error"); after.run(); return; }
        final UUID id = p.getUniqueId();
        if (!busy.add(id)) { after.run(); return; }
        final boolean open = !s.isOpen(a);
        plugin.db.call(c -> s.persist(c, a, open)).whenComplete((ok, ex) -> {
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (ex != null || !Boolean.TRUE.equals(ok)) { plugin.send(p, "settings-failed"); after.run(); return; }
                    s.cache(a, open);
                    if (p.isOnline()) s.apply(p, open);
                    plugin.send(p, "settings-changed", "%setting%", s.name, "%state%", open ? "&aOpen" : "&cClosed");
                    after.run();
                });
            } catch (Exception e) { /* plugin disabling */ }
        });
    }
}
