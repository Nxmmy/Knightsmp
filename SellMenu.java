package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.market.MarketManager.Category;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class SellMenu extends Menu {
    private static final int[] SLOTS = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};

    public SellMenu(KnightPlugin plugin, Player player) { super(plugin, player, 5, "&5&lKnightSMP &8» &7Market"); }

    @Override
    protected void build() {
        frame();
        set(4, Items.make(Material.CLOCK, "&d&lNext Market Refresh", List.of("&f" + Text.duration(plugin.market.secondsToRefresh()),
                "&7Prices and stock are re-rolled every refresh."), false), null);
        int i = 0;
        for (Category c : plugin.market.categories().values()) {
            if (i >= SLOTS.length) break;
            set(SLOTS[i++], Items.make(c.icon(), "&f&l" + c.name(), List.of("&8" + c.items().size() + " items", "&dClick to sell"), false),
                    e -> go(new SellItemsMenu(plugin, player, c)));
        }
        set(40, Items.make(Material.BARRIER, "&cClose"), e -> close());
    }
}
