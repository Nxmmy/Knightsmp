package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.kits.KitManager.Kit;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class KitMenu extends Menu {
    public KitMenu(KnightPlugin plugin, Player player) { super(plugin, player, 5, "&5&lKnightSMP &8» &7Kit Shop"); }

    private ItemStack icon(Kit k) {
        List<String> lore = new ArrayList<>(k.desc());
        lore.add(" ");
        lore.add("&8Price: " + (k.price() == 0 ? "&aFree" : k.currency().fmt(k.price())));
        Account a = plugin.economy.get(player);
        long cd = a == null ? 0 : plugin.kits.cooldownLeft(a, k);
        if (k.cooldown() > 0) lore.add("&8Cooldown: &7" + Text.duration(k.cooldown()) + (cd > 0 ? " &c(ready in " + Text.duration(cd) + ")" : " &a(ready)"));
        lore.add(" ");
        lore.add("&8Contents:");
        int shown = 0;
        for (ItemStack it : k.items()) {
            if (shown++ >= 10) { lore.add("&7  ...and " + (k.items().size() - 10) + " more"); break; }
            lore.add("&7  " + it.getAmount() + "x " + Text.pretty(it.getType()) + (it.getEnchantments().isEmpty() ? "" : " &d✦"));
        }
        return Items.make(k.icon(), "&d&l" + k.name(), lore, k.price() >= 100);
    }

    @Override
    protected void build() {
        frame();
        int i = 0;
        for (Kit k : plugin.kits.kits().values()) {
            if (k.id().startsWith("__")) continue;
            if (i >= 14) break;
            int slot = 10 + i + (i / 7) * 2;
            i++;
            set(slot, icon(k), e -> {
                ConfirmMenu cm = new ConfirmMenu(plugin, player, "&5&lConfirm Kit", icon(k), () -> {
                    plugin.kits.purchase(player, k);
                    go(new KitMenu(plugin, player));
                }, () -> go(new KitMenu(plugin, player)));
                go(cm);
            });
        }
        set(40, Items.make(Material.BARRIER, "&cClose"), e -> close());
    }
}
