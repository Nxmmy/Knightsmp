package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.shop.ShopManager.Category;
import dev.knightsmp.mods.shop.ShopManager.ShopItem;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class ShopItemsMenu extends PagedMenu<ShopItem> {
    private final Category category;

    public ShopItemsMenu(KnightPlugin plugin, Player player, Category category) {
        super(plugin, player, "&5&lShop &8» &7" + category.name(), () -> new ShopMainMenu(plugin, player));
        this.category = category;
        this.entries = category.items();
    }

    @Override
    protected ItemStack render(ShopItem it, int i) {
        List<String> lore = new ArrayList<>();
        lore.add(it.desc() != null ? "&7" + it.desc() : "&7" + category.name());
        lore.add("&8Amount: &f" + it.amount());
        lore.add("&8Price: " + it.currency().fmt(it.price()));
        lore.add("&dClick to buy");
        ItemStack st = Items.make(it.material(), "&f" + Text.pretty(it.material()) + (it.amount() > 1 ? " &7x" + it.amount() : ""), lore, false);
        st.setAmount(Math.min(it.amount(), it.material().getMaxStackSize()));
        return st;
    }

    @Override protected void onClick(ShopItem it, InventoryClickEvent e) { go(new PurchaseMenu(plugin, player, category, it)); }
}
