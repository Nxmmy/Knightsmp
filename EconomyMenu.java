package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.Currency;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class EconomyMenu extends Menu {
    public EconomyMenu(KnightPlugin plugin, Player player) { super(plugin, player, 6, "&5&lKnightSMP &8» &7Wallet"); }

    @Override
    protected void build() {
        frame();
        Account a = plugin.economy.get(player);
        if (a == null) { set(22, Items.make(Material.BARRIER, "&cAccount not loaded"), null); return; }
        long wd = plugin.getConfig().getLong("economy.gui-withdraw-amount", 64);
        set(4, Items.head(player, "&d&l" + player.getName(), List.of("&fIron: &7" + Text.num(a.iron), "&bDiamonds: &7" + Text.num(a.diamonds)), false), null);
        set(20, Items.make(Material.IRON_INGOT, "&f&lIron", List.of("&8Account: &f" + Text.num(a.iron),
                "&8In inventory: &f" + Text.num(plugin.economy.inventoryCount(player, Currency.IRON))), false), null);
        set(24, Items.make(Material.DIAMOND, "&b&lDiamonds", List.of("&8Account: &b" + Text.num(a.diamonds),
                "&8In inventory: &b" + Text.num(plugin.economy.inventoryCount(player, Currency.DIAMOND))), false), null);
        set(29, Items.make(Material.HOPPER, "&aDeposit all Iron", "&7Moves iron ingots from your", "&7inventory into your account."), e -> { plugin.economy.deposit(player, Currency.IRON, -1); rebuild(); });
        set(30, Items.make(Material.DISPENSER, "&eWithdraw " + wd + " Iron", "&7Turns account iron into ingots."), e -> { plugin.economy.withdraw(player, Currency.IRON, wd); rebuild(); });
        set(32, Items.make(Material.HOPPER, "&aDeposit all Diamonds", "&7Moves diamonds from your", "&7inventory into your account."), e -> { plugin.economy.deposit(player, Currency.DIAMOND, -1); rebuild(); });
        set(33, Items.make(Material.DISPENSER, "&eWithdraw " + wd + " Diamonds", "&7Turns account diamonds into items."), e -> { plugin.economy.withdraw(player, Currency.DIAMOND, wd); rebuild(); });
        set(38, Items.make(Material.BOOK, "&d&lTransaction History", "&7Your last transactions"), e -> HistoryMenu.show(plugin, player));
        set(40, Items.make(Material.EMERALD, "&a&lTransfer Money", "&7Send iron or diamonds", "&7to another player."), e -> go(new TransferTargetMenu(plugin, player)));
        set(49, Items.make(Material.BARRIER, "&cClose"), e -> close());
    }
}
