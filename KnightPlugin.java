package dev.knightsmp.mods;

import dev.knightsmp.mods.bounty.BountyManager;
import dev.knightsmp.mods.combat.CombatManager;
import dev.knightsmp.mods.commands.Commands;
import dev.knightsmp.mods.economy.EconomyManager;
import dev.knightsmp.mods.kits.KitManager;
import dev.knightsmp.mods.listeners.Listeners;
import dev.knightsmp.mods.market.MarketManager;
import dev.knightsmp.mods.shop.ShopManager;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.teleport.TeleportManager;
import dev.knightsmp.mods.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

public final class KnightPlugin extends JavaPlugin {
    public Database db;
    public EconomyManager economy;
    public BountyManager bounty;
    public CombatManager combat;
    public TeleportManager teleport;
    public ShopManager shop;
    public KitManager kits;
    public MarketManager market;
    private volatile boolean shuttingDown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String f : new String[]{"shop.yml", "kits.yml", "market.yml"})
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);

        db = new Database(this);
        try { db.open(); }
        catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open SQLite database - disabling plugin.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = new EconomyManager(this);
        combat = new CombatManager(this);
        teleport = new TeleportManager(this);
        bounty = new BountyManager(this);
        shop = new ShopManager(this);
        kits = new KitManager(this);
        market = new MarketManager(this);

        teleport.loadSpawn();
        shop.load();
        kits.load();
        market.load();

        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        Commands cmds = new Commands(this);
        for (String name : getDescription().getCommands().keySet()) {
            var c = getCommand(name);
            if (c != null) { c.setExecutor(cmds); c.setTabCompleter(cmds); }
        }
        combat.start();
        market.start();

        // /reload or late enable: load accounts of players already online
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { economy.loadBlocking(p.getUniqueId(), p.getName()); bounty.onJoin(p); }
            catch (Exception e) { p.kick(Text.c("&cKnightSMP could not load your data. Please rejoin.")); }
        }
        bounty.refreshRole();
        getLogger().info("Mods For KnightSMP enabled.");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (combat != null) combat.stop();
        if (teleport != null) teleport.shutdown();
        if (bounty != null) bounty.shutdown();
        if (economy != null) economy.saveAll();
        if (db != null) db.close();
    }

    public boolean isStopping() { return shuttingDown || Bukkit.isStopping(); }

    public void reloadAll() {
        reloadConfig();
        shop.load();
        kits.load();
        market.reloadDefinitions();
        bounty.refreshRole();
    }

    public YamlConfiguration yaml(String file) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), file));
    }

    // ---------- messages ----------
    public Component msg(String key, Object... kv) {
        String t = getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) t = t.replace(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        return Text.c(t);
    }
    public String raw(String key, Object... kv) {
        String t = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) t = t.replace(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        return t;
    }
    public void send(CommandSender to, String key, Object... kv) { to.sendMessage(msg(key, kv)); }
    public void broadcast(String key, Object... kv) { Bukkit.broadcast(msg(key, kv)); }
}
