package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.EconomyManager.Tx;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class EconomyMenu extends Menu {
    private List<Tx> recent = List.of();

    public EconomyMenu(KnightPlugin plugin, Player player) { super(plugin, player, 5, "&5&lKnightSMP &8» &7Wallet"); }

    /** Loads the last few transactions off-thread, then opens the menu. */
    @Override
    public void open() {
        plugin.economy.history(player.getUniqueId(), 5).whenComplete((list, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    recent = ex == null ? list : List.of();
                    super.open();
                }));
    }

    @Override
    protected void build() {
        frame();
        Account a = plugin.economy.get(player);
        if (a == null) { set(22, Items.make(Material.BARRIER, "&cAccount not loaded"), null); return; }
        set(13, Items.head(player, "&d&l" + player.getName(), List.of("&8Balance: " + Money.fmt(a.dollars)), false), null);
        set(20, Items.make(Material.GOLD_INGOT, "&6&lBalance", List.of("&a$" + dev.knightsmp.mods.util.Text.num(a.dollars),
                "&7Earn Dollars with &f/sell &7and bounties."), false), null);
        List<String> lore = new ArrayList<>();
        if (recent.isEmpty()) lore.add("&7No transactions yet.");
        for (Tx t : recent) lore.add((t.delta() >= 0 ? "&a+" : "&c-") + "$" + dev.knightsmp.mods.util.Text.num(Math.abs(t.delta())) + " &8" + t.type().replace('_', ' ').toLowerCase());
        set(24, Items.make(Material.BOOK, "&d&lRecent Transactions", lore, false), e -> HistoryMenu.show(plugin, player));
        set(31, Items.make(Material.EMERALD, "&a&lTransfer Money", "&7Send Dollars to another player."), e -> go(new TransferTargetMenu(plugin, player)));
        set(40, Items.make(Material.BARRIER, "&cClose"), e -> close());
    }
}
