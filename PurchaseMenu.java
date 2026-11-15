package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.shop.ShopManager.Category;
import dev.knightsmp.mods.shop.ShopManager.ShopItem;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Money;
import dev.knightsmp.mods.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Purchase confirmation screen with quantity selector. The price shown here is the price that is charged. */
public final class PurchaseMenu extends Menu {
    private final Category category;
    private final ShopItem item;
    private int qty = 1;

    public PurchaseMenu(KnightPlugin plugin, Player player, Category category, ShopItem item) {
        super(plugin, player, 4, "&5&lShop &8» &7Confirm Purchase");
        this.category = category; this.item = item;
    }

    private void adjust(int d) { qty = Math.max(1, Math.min(plugin.shop.maxQuantity(item), qty + d)); rebuild(); }

    @Override
    protected void build() {
        frame();
        long total = item.price() * qty;
        ItemStack preview = item.display().clone();
        preview.setAmount(Math.max(1, Math.min(item.display().getAmount() * qty, Math.max(1, preview.getMaxStackSize()))));
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Text.c("&8&m                    "));
            lore.add(Text.c("&8Quantity: &f" + qty + (item.contents().size() > 1 ? " set(s)" : "")));
            lore.add(Text.c("&8Total price: " + Money.price(total)));
            lore.add(Text.c("&8Your balance: " + Money.fmt(plugin.economy.balance(player))));
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        set(13, preview, null);
        if (plugin.shop.maxQuantity(item) > 1) {
            set(10, Items.make(Material.RED_STAINED_GLASS_PANE, "&c-8"), e -> adjust(-8));
            set(11, Items.make(Material.RED_STAINED_GLASS_PANE, "&c-1"), e -> adjust(-1));
            set(15, Items.make(Material.LIME_STAINED_GLASS_PANE, "&a+1"), e -> adjust(1));
            set(16, Items.make(Material.LIME_STAINED_GLASS_PANE, "&a+8"), e -> adjust(8));
        }
        set(20, Items.make(Material.LIME_CONCRETE, "&a&lBUY", "&7Pay " + Money.price(total)), e -> plugin.shop.purchase(player, item, qty, this::rebuild));
        set(24, Items.make(Material.RED_CONCRETE, "&c&lCANCEL", "&7Back to " + category.name()), e -> go(new ShopItemsMenu(plugin, player, category)));
    }
}
