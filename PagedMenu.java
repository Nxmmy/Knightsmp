package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 6-row paged menu: 28 entries per page, previous/next buttons, optional back button. */
public abstract class PagedMenu<T> extends Menu {
    protected static final int[] CONTENT = new int[28];
    static {
        int n = 0;
        for (int r = 1; r <= 4; r++) for (int c = 1; c <= 7; c++) CONTENT[n++] = r * 9 + c;
    }
    protected List<T> entries = new ArrayList<>();
    protected int page;
    private final Supplier<Menu> back;

    protected PagedMenu(KnightPlugin plugin, Player player, String title, Supplier<Menu> back) {
        super(plugin, player, 6, title);
        this.back = back;
    }

    protected abstract ItemStack render(T t, int index);
    protected abstract void onClick(T t, InventoryClickEvent e);
    protected void extra() {}

    @Override
    protected void build() {
        frame();
        int per = CONTENT.length;
        int pages = Math.max(1, (entries.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= entries.size()) break;
            T t = entries.get(idx);
            set(CONTENT[i], render(t, idx), e -> onClick(t, e));
        }
        if (back != null) set(45, Items.make(Material.DARK_OAK_DOOR, "&c← Back"), e -> go(back.get()));
        if (page > 0) set(48, Items.make(Material.ARROW, "&d◄ Previous Page", "&7Page " + page + "/" + pages), e -> { page--; rebuild(); });
        set(49, Items.make(Material.PAPER, "&7Page &f" + (page + 1) + "&7/&f" + pages), null);
        if (page < pages - 1) set(50, Items.make(Material.ARROW, "&dNext Page ►", "&7Page " + (page + 2) + "/" + pages), e -> { page++; rebuild(); });
        set(53, Items.make(Material.BARRIER, "&cClose"), e -> close());
        extra();
    }
}
