package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.shop.ShopManager.Category;
import dev.knightsmp.mods.shop.ShopManager.ShopItem;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Purchase confirmation screen with quantity selector. */
public final class PurchaseMenu extends Menu {
    private final Category category;
    private final ShopItem item;
    private int qty = 1;

    public PurchaseMenu(KnightPlugin plugin, Player player, Category category, ShopItem item) {
        super(plugin, player, 4, "&5&lShop &8» &7Confirm Purchase");
        this.category = category; this.item = item;
    }

    private void adjust(int d) { qty = Math.max(1, Math.min(plugin.shop.maxQuantity(), qty + d)); rebuild(); }

    @Override
    protected void build() {
        frame();
        long total = item.price() * qty;
        long units = (long) item.amount() * qty;
        ItemStack preview = Items.make(item.material(), "&f" + Text.pretty(item.material()) + " &7x" + units,
                List.of("&8Total price: " + item.currency().fmt(total),
                        "&8You have: " + item.currency().fmt(plugin.economy.spendable(player, item.currency()))), false);
        preview.setAmount((int) Math.min(units, item.material().getMaxStackSize()));
        set(13, preview, null);
        set(10, Items.make(Material.RED_STAINED_GLASS_PANE, "&c-8"), e -> adjust(-8));
        set(11, Items.make(Material.RED_STAINED_GLASS_PANE, "&c-1"), e -> adjust(-1));
        set(15, Items.make(Material.LIME_STAINED_GLASS_PANE, "&a+1"), e -> adjust(1));
        set(16, Items.make(Material.LIME_STAINED_GLASS_PANE, "&a+8"), e -> adjust(8));
        set(20, Items.make(Material.LIME_CONCRETE, "&a&lBUY", "&7Pay " + item.currency().fmt(total)), e -> {
            plugin.shop.purchase(player, item, qty);
            rebuild();
        });
        set(24, Items.make(Material.RED_CONCRETE, "&c&lCANCEL", "&7Back to " + category.name()), e -> go(new ShopItemsMenu(plugin, player, category)));
    }
}
