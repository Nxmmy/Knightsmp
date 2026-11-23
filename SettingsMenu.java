package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.settings.SettingsManager.Setting;
import dev.knightsmp.mods.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /settings - one icon per setting with a green/red state tile under it. A plain chest menu, left-click only (works on Bedrock). */
public final class SettingsMenu extends Menu {
    private static int rowsFor(KnightPlugin plugin) {
        int groups = Math.max(1, Math.min(2, (plugin.settings.all().size() + 6) / 7));
        return 2 * groups + 2;
    }

    public SettingsMenu(KnightPlugin plugin, Player player) { super(plugin, player, rowsFor(plugin), "&5&lKnightSMP &8» &7Settings"); }

    @Override
    protected void build() {
        frame();
        Account a = plugin.economy.get(player);
        if (a == null) { set(inv.getSize() / 2, Items.make(Material.BARRIER, "&cData not loaded"), null); return; }
        List<Setting> all = plugin.settings.all();
        for (int i = 0; i < all.size() && i < 14; i++) {
            Setting s = all.get(i);
            boolean open = s.isOpen(a);
            int row = 1 + (i / 7) * 2, col = 1 + i % 7;
            int slot = row * 9 + col;
            List<String> lore = new ArrayList<>();
            for (String d : s.description) lore.add("&7" + d);
            lore.add(" ");
            lore.add("&8Status: " + (open ? "&a&lOPEN" : "&c&lCLOSED"));
            lore.add("&dClick to switch");
            set(slot, Items.make(s.icon, "&f&l" + s.name, lore, open), e -> plugin.settings.toggle(player, s, this::rebuild));
            set(slot + 9, Items.make(open ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    open ? "&a&lOpen" : "&c&lClosed", List.of("&7" + s.name, "&dClick to switch"), false),
                    e -> plugin.settings.toggle(player, s, this::rebuild));
        }
        set(inv.getSize() - 5, Items.make(Material.BARRIER, "&cClose"), e -> close());
    }
}
