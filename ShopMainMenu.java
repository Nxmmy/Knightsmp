package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.shop.ShopManager.Category;
import dev.knightsmp.mods.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class ShopMainMenu extends Menu {
    private static final int[] SLOTS = {10, 12, 14, 16, 28, 30, 32, 34};

    public ShopMainMenu(KnightPlugin plugin, Player player) { super(plugin, player, 5, "&5&lKnightSMP &8» &7Shop"); }

    @Override
    protected void build() {
        frame();
        int i = 0;
        for (Category c : plugin.shop.categories().values()) {
            if (i >= SLOTS.length) break;
            List<String> lore = new ArrayList<>(c.desc());
            lore.add("&8" + c.items().size() + " items");
            lore.add("&dClick to browse");
            set(SLOTS[i++], Items.make(c.icon(), "&f&l" + c.name(), lore, false), e -> go(new ShopItemsMenu(plugin, player, c)));
        }
        set(40, Items.make(Material.BARRIER, "&cClose"), e -> close());
    }
}
