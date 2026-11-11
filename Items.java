package dev.knightsmp.mods.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class Items {
    private Items() {}

    public static ItemStack make(Material m, String name, String... lore) {
        return make(m, name, Arrays.asList(lore), false);
    }
    public static ItemStack make(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.displayName(Text.c(name));
        if (lore != null && !lore.isEmpty()) meta.lore(Text.lore(lore));
        if (glow) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }
    public static ItemStack head(OfflinePlayer p, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (!(it.getItemMeta() instanceof SkullMeta sm)) return it;
        sm.setOwningPlayer(p);
        sm.displayName(Text.c(name));
        if (lore != null && !lore.isEmpty()) sm.lore(Text.lore(lore));
        if (glow) sm.setEnchantmentGlintOverride(true);
        it.setItemMeta(sm);
        return it;
    }

    /** Plain = bare material, no custom meta, so kit/enchanted/renamed items are never treated as currency or sold. */
    public static boolean isPlain(ItemStack s, Material m) {
        return s != null && s.getType() == m && !s.hasItemMeta();
    }
    public static long count(Inventory inv, Material m) {
        long n = 0;
        for (ItemStack s : inv.getStorageContents()) if (isPlain(s, m)) n += s.getAmount();
        return n;
    }
    public static boolean remove(Inventory inv, Material m, long amount) {
        if (amount < 0 || count(inv, m) < amount) return false;
        long left = amount;
        ItemStack[] c = inv.getStorageContents();
        for (int i = 0; i < c.length && left > 0; i++) {
            ItemStack s = c[i];
            if (!isPlain(s, m)) continue;
            int take = (int) Math.min(left, s.getAmount());
            if (take == s.getAmount()) inv.setItem(i, null);
            else { s.setAmount(s.getAmount() - take); inv.setItem(i, s); }
            left -= take;
        }
        return left == 0;
    }
    public static List<ItemStack> stacks(Material m, long amount) {
        List<ItemStack> l = new ArrayList<>();
        int max = Math.max(1, m.getMaxStackSize());
        while (amount > 0) {
            int n = (int) Math.min(max, amount);
            l.add(new ItemStack(m, n));
            amount -= n;
        }
        return l;
    }
    /** Simulates delivery on a copy of the player's storage so nothing is ever half-delivered. */
    public static boolean canFit(Player p, List<ItemStack> items) {
        Inventory tmp = Bukkit.createInventory(null, 36);
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length && i < 36; i++) if (c[i] != null) tmp.setItem(i, c[i].clone());
        for (ItemStack it : items) if (!tmp.addItem(it.clone()).isEmpty()) return false;
        return true;
    }
    public static void give(Player p, List<ItemStack> items) {
        for (ItemStack it : items) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it.clone());
            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
        }
    }
    /** Gives as much as fits, returns the amount that did NOT fit. */
    public static long giveUpTo(Player p, Material m, long amount) {
        long leftover = 0;
        for (ItemStack st : stacks(m, amount))
            for (ItemStack l : p.getInventory().addItem(st).values()) leftover += l.getAmount();
        return leftover;
    }
}
