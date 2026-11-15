package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.shop.ShopManager.Category;
import dev.knightsmp.mods.util.Items;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Category picker (paged, so any number of categories fits). */
public final class ShopMainMenu extends PagedMenu<Category> {
    public ShopMainMenu(KnightPlugin plugin, Player player) {
        super(plugin, player, "&5&lKnightSMP &8» &7Shop", null);
        this.entries = new ArrayList<>(plugin.shop.categories().values());
    }

    @Override
    protected ItemStack render(Category c, int index) {
        List<String> lore = new ArrayList<>(c.desc());
        lore.add("&8" + c.items().size() + " items");
        lore.add("&dClick to browse");
        return Items.make(c.icon(), "&f&l" + c.name(), lore, false);
    }

    @Override protected void onClick(Category c, InventoryClickEvent e) { go(new ShopItemsMenu(plugin, player, c)); }
}
