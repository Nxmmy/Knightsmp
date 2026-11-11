package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Generic confirmation screen. Confirm can only fire once, so rapid double clicks cannot double-spend. */
public final class ConfirmMenu extends Menu {
    private final ItemStack info;
    private final Runnable onConfirm, onCancel;
    private boolean used;

    public ConfirmMenu(KnightPlugin plugin, Player player, String title, ItemStack info, Runnable onConfirm, Runnable onCancel) {
        super(plugin, player, 3, title);
        this.info = info; this.onConfirm = onConfirm; this.onCancel = onCancel;
    }

    @Override
    protected void build() {
        frame();
        set(13, info, null);
        set(11, Items.make(Material.LIME_CONCRETE, "&a&lCONFIRM", "&7Click to confirm"), e -> {
            if (used) return;
            used = true;
            onConfirm.run();
        });
        set(15, Items.make(Material.RED_CONCRETE, "&c&lCANCEL", "&7Click to go back"), e -> {
            if (used) return;
            used = true;
            onCancel.run();
        });
    }
}
