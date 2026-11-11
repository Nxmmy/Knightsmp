package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Base KnightSMP dark-theme chest menu. Plain chest inventories only, so they work for Java and Bedrock (Geyser) alike. */
public abstract class Menu implements InventoryHolder {
    protected final KnightPlugin plugin;
    protected final Player player;
    protected final Inventory inv;
    protected final int rows;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Menu(KnightPlugin plugin, Player player, int rows, String title) {
        this.plugin = plugin; this.player = player; this.rows = rows;
        this.inv = Bukkit.createInventory(this, rows * 9, Text.c(title));
    }

    protected abstract void build();

    @Override public Inventory getInventory() { return inv; }

    public void open() { rebuild(); player.openInventory(inv); }
    public void rebuild() { actions.clear(); inv.clear(); build(); }

    /** Dark gray background with alternating black / deep-purple border. */
    protected void frame() {
        ItemStack bg = Items.make(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack dark = Items.make(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack purple = Items.make(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < rows * 9; i++) {
            int r = i / 9, c = i % 9;
            boolean border = r == 0 || r == rows - 1 || c == 0 || c == 8;
            inv.setItem(i, border ? ((r + c) % 2 == 0 ? dark : purple) : bg);
        }
    }

    protected void set(int slot, ItemStack it, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, it);
        if (action != null) actions.put(slot, action);
    }

    public void click(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> a = actions.get(e.getRawSlot());
        if (a != null) a.accept(e);
    }

    /** Opens another menu next tick (safe inside click handlers, and friendlier to Geyser). */
    protected void go(Menu m) { Bukkit.getScheduler().runTask(plugin, m::open); }
    protected void close() { Bukkit.getScheduler().runTask(plugin, player::closeInventory); }
}
