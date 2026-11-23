package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.admin.ResetManager;
import dev.knightsmp.mods.admin.ResetManager.Target;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** /reset target picker: "Everyone" first, then every known player (paged). Operators only; checked on open and on every click. */
public final class ResetMenu extends PagedMenu<Target> {
    private ResetMenu(KnightPlugin plugin, Player admin, List<Target> list) {
        super(plugin, admin, "&4&lReset &8» &7Choose target", null);
        this.entries = list;
    }

    public static void show(KnightPlugin plugin, Player admin) {
        if (!ResetManager.isOperator(admin)) { plugin.send(admin, "no-permission"); return; }
        plugin.reset.targets().whenComplete((list, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!admin.isOnline() || !ResetManager.isOperator(admin)) return;
            if (ex != null) { plugin.send(admin, "db-error"); return; }
            new ResetMenu(plugin, admin, list).open();
        }));
    }

    @Override
    protected ItemStack render(Target t, int index) {
        if (t.everyone()) {
            return Items.make(Material.TNT, "&c&lEveryone", List.of("&7Resets money and bounty of", "&7EVERY player, online or offline.", " ", "&dClick to choose"), true);
        }
        return Items.head(Bukkit.getOfflinePlayer(t.id()), "&f" + t.name(), List.of("&8Money: " + Money.fmt(t.dollars()), "&8Bounty: " + Money.fmt(t.bounty()), " ", "&dClick to choose"), false);
    }

    @Override
    protected void onClick(Target t, InventoryClickEvent e) {
        if (!ResetManager.isOperator(player)) { close(); return; }
        long to = plugin.reset.moneyTo();
        ItemStack info = t.everyone()
                ? Items.make(Material.TNT, "&4&lRESET EVERYONE", List.of("&7Money of every player -> " + Money.fmt(to), "&7Bounty of every player -> &a$0", " ", "&cThis cannot be undone."), true)
                : Items.head(Bukkit.getOfflinePlayer(t.id()), "&4&lRESET " + t.name(), List.of("&7Money -> " + Money.fmt(to), "&7Bounty -> &a$0", "&7Nobody else is affected.", " ", "&cThis cannot be undone."), true);
        go(new ConfirmMenu(plugin, player, "&4&lConfirm reset: " + (t.everyone() ? "Everyone" : t.name()), info,
                () -> plugin.reset.reset(player, t, () -> close()),          // Confirm
                () -> go(new ResetMenu(plugin, player, entries))));          // Cancel: back to the list, nothing changed
    }
}
