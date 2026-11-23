package dev.knightsmp.mods.admin;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.gui.BountyMenu;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.storage.Sql;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * /reset - resets MONEY and BOUNTY (nothing else) for everyone or for one player. Operators only: the check uses the
 * server's real OP status and is repeated on every click and again at the moment of confirmation.
 * The whole reset is one database transaction (history rows + update + audit log), so it either happens completely or not at all.
 */
public final class ResetManager {
    /** A row of the reset list: id == null means "Everyone". */
    public record Target(UUID id, String name, long dollars, long bounty) {
        public boolean everyone() { return id == null; }
    }

    private final KnightPlugin plugin;
    private boolean resetting;           // server thread only: blocks a second reset while one is running

    public ResetManager(KnightPlugin plugin) { this.plugin = plugin; }

    public static boolean isOperator(Player p) { return p != null && p.isOp(); }

    /** The list for the menu: Everyone first, then every known player (online or offline) from the database. */
    public CompletableFuture<List<Target>> targets() {
        return plugin.db.call(c -> {
            List<Target> out = new ArrayList<>();
            out.add(new Target(null, "Everyone", 0, 0));
            try (PreparedStatement ps = Database.bind(c, Sql.RESET_LIST, 5000); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Target(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4)));
            }
            return out;
        });
    }

    public long moneyTo() { return Math.max(0, plugin.getConfig().getLong("reset.money-to", 0)); }

    /** Performs the reset. {@code done} is called on the server thread when it has finished (success or not). */
    public void reset(Player admin, Target target, Runnable done) {
        if (!isOperator(admin)) { plugin.send(admin, "no-permission"); done.run(); return; }       // re-checked at the moment of the action
        if (resetting) { plugin.send(admin, "reset-busy"); done.run(); return; }
        resetting = true;
        final long to = moneyTo();
        final long ts = System.currentTimeMillis();
        final boolean all = target.everyone();
        final String tid = all ? null : target.id().toString();
        final AtomicInteger rows = new AtomicInteger();
        final String adminId = admin.getUniqueId().toString(), adminName = admin.getName();
        final String targetLabel = all ? "EVERYONE" : target.name() + " (" + tid + ")";

        plugin.economy.commit("ADMIN_RESET", List.of(), c -> {
            if (all) {
                Database.run(c, Sql.RESET_TX_ALL, ts, to, to);
                rows.set(Database.run(c, Sql.RESET_ALL, to));
            } else {
                Database.run(c, Sql.RESET_TX_ONE, ts, to, tid, to);
                rows.set(Database.run(c, Sql.RESET_ONE, to, tid));
                if (rows.get() != 1) return false;                       // unknown account: nothing changes
            }
            Database.run(c, Sql.ADMIN_LOG_INSERT, ts, adminId, adminName, "RESET_MONEY_BOUNTY", targetLabel, "money->" + to + ", bounty->0, accounts=" + rows.get());
            return true;
        }, () -> {
            // keep the in-memory copies identical to the database
            if (all) plugin.economy.forEachCached(a -> { a.dollars = to; a.bounty = 0; plugin.hud.touch(a.id); });
            else { Account a = plugin.economy.cached(target.id()); if (a != null) { a.dollars = to; a.bounty = 0; plugin.hud.touch(a.id); } }
        }, ok -> {
            resetting = false;
            if (!ok) {
                plugin.getLogger().severe("[RESET] FAILED: " + adminName + " tried to reset " + targetLabel + " - nothing was changed.");
                plugin.send(admin, "reset-failed");
                done.run();
                return;
            }
            plugin.getLogger().info("[RESET] " + adminName + " (" + adminId + ") reset money and bounty of " + targetLabel + " (" + rows.get() + " accounts).");
            plugin.send(admin, "reset-done", "%target%", all ? "everyone" : target.name(), "%count%", rows.get());
            for (Player online : Bukkit.getOnlinePlayers())
                if (all || online.getUniqueId().equals(target.id())) plugin.send(online, "reset-notice");
            plugin.bounty.refreshRole();
            BountyMenu.refreshAll(plugin);
            done.run();
        });
    }
}
