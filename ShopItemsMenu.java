package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.shop.ShopManager.Category;
import dev.knightsmp.mods.shop.ShopManager.ShopItem;
import dev.knightsmp.mods.util.Money;
import dev.knightsmp.mods.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;

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
        ItemStack st = it.display().clone();
        ItemMeta meta = st.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Text.c("&8&m                    "));
            if (it.desc() != null) lore.add(Text.c("&7" + it.desc()));
            if (it.contents().size() > 1) lore.add(Text.c("&8Includes " + it.contents().size() + " items"));
            else if (it.contents().get(0).getAmount() > 1) lore.add(Text.c("&8Quantity: &f" + it.contents().get(0).getAmount()));
            lore.add(Text.c("&8Price: " + Money.price(it.price())));
            lore.add(Text.c("&8Your balance: " + Money.fmt(plugin.economy.balance(player))));
            lore.add(Text.c("&dClick to buy"));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            st.setItemMeta(meta);
        }
        return st;
    }

    @Override protected void onClick(ShopItem it, InventoryClickEvent e) { go(new PurchaseMenu(plugin, player, category, it)); }
}
