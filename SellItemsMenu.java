package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.market.MarketManager.Category;
import dev.knightsmp.mods.market.MarketManager.Item;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Left click sells the selected amount (cycle it with the button - no right-click needed, so Bedrock works). */
public final class SellItemsMenu extends PagedMenu<Item> {
    private static final int[] MODES = {1, 8, 64, -1};
    private final Category category;
    private int mode = 0;

    public SellItemsMenu(KnightPlugin plugin, Player player, Category category) {
        super(plugin, player, "&5&lMarket &8» &7" + category.name(), () -> new SellMenu(plugin, player));
        this.category = category;
        this.entries = category.items();
    }

    @Override
    protected ItemStack render(Item it, int i) {
        long have = Items.count(player.getInventory(), it.material);
        List<String> lore = new ArrayList<>();
        lore.add("&8Demand: " + (it.up ? "&a&lUP" : "&c&lDOWN"));
        lore.add("&8Price: " + it.currency.fmt(it.price) + (it.bundle > 1 ? " &7per " + it.bundle : " &7each"));
        lore.add("&8Stock: " + (it.stock > 0 ? "&f" + Text.num(it.stock) + " &7bundles" : "&cSOLD OUT"));
        lore.add("&8You have: &f" + Text.num(have));
        lore.add("&dClick to sell");
        ItemStack st = Items.make(it.material, "&f" + Text.pretty(it.material), lore, it.up);
        st.setAmount(Math.min(it.bundle, it.material.getMaxStackSize()));
        return st;
    }

    @Override
    protected void onClick(Item it, InventoryClickEvent e) {
        plugin.market.sell(player, it, MODES[mode]);
        rebuild();
    }

    @Override
    protected void extra() {
        String label = MODES[mode] < 0 ? "ALL" : MODES[mode] + " bundle" + (MODES[mode] > 1 ? "s" : "");
        set(46, Items.make(Material.HOPPER, "&eSell amount: &f" + label, "&7Click to cycle: 1 / 8 / 64 / ALL"), e -> { mode = (mode + 1) % MODES.length; rebuild(); });
        set(47, Items.make(Material.CLOCK, "&dRefresh in &f" + Text.duration(plugin.market.secondsToRefresh())), null);
    }
}
