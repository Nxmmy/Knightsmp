package dev.knightsmp.mods.shop;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Currency;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShopManager {
    public record ShopItem(Material material, int amount, long price, Currency currency, String desc) {}
    public record Category(String id, String name, Material icon, List<String> desc, List<ShopItem> items) {}

    private final KnightPlugin plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public ShopManager(KnightPlugin plugin) { this.plugin = plugin; }

    public Map<String, Category> categories() { return categories; }

    public void load() {
        categories.clear();
        YamlConfiguration y = plugin.yaml("shop.yml");
        ConfigurationSection root = y.getConfigurationSection("categories");
        if (root == null) { plugin.getLogger().warning("shop.yml has no 'categories' section."); return; }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            List<ShopItem> items = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("items")) {
                Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
                Currency cur = Currency.parse(String.valueOf(m.get("currency") == null ? "iron" : m.get("currency")));
                if (mat == null || cur == null || !mat.isItem()) {
                    plugin.getLogger().warning("shop.yml: skipping invalid item " + m + " in category " + id);
                    continue;
                }
                int amount = m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                long price = m.get("price") instanceof Number n ? Math.max(1, n.longValue()) : 1;
                items.add(new ShopItem(mat, amount, price, cur, m.get("desc") == null ? null : String.valueOf(m.get("desc"))));
            }
            categories.put(id, new Category(id, s.getString("name", id), icon == null ? Material.CHEST : icon, s.getStringList("description"), items));
        }
    }

    public int maxQuantity() { return plugin.getConfig().getInt("shop.max-quantity", 64); }

    /** Validates funds and space, charges, then delivers - all in one server tick. */
    public void purchase(Player p, ShopItem it, int qty) {
        if (qty < 1 || qty > maxQuantity()) { plugin.send(p, "invalid-amount"); return; }
        long total = it.price() * qty;
        List<ItemStack> stacks = Items.stacks(it.material(), (long) it.amount() * qty);
        if (!Items.canFit(p, stacks)) { plugin.send(p, "shop-no-space"); return; }
        if (plugin.economy.spendable(p, it.currency()) < total
                || !plugin.economy.charge(p, it.currency(), total, "SHOP", null)) {
            plugin.send(p, "shop-no-funds", "%price%", it.currency().fmt(total));
            return;
        }
        Items.give(p, stacks);
        plugin.send(p, "shop-bought", "%item%", (long) it.amount() * qty + "x " + Text.pretty(it.material()), "%price%", it.currency().fmt(total));
    }
}
