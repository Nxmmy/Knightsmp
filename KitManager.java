package dev.knightsmp.mods.kits;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.Currency;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KitManager {
    public record Kit(String id, String name, Material icon, List<String> desc, long price, Currency currency, long cooldown, List<ItemStack> items) {}

    private static final String STARTER_MARK = "__starter";
    private final KnightPlugin plugin;
    private final Map<String, Kit> kits = new LinkedHashMap<>();

    public KitManager(KnightPlugin plugin) { this.plugin = plugin; }
    public Map<String, Kit> kits() { return kits; }

    public void load() {
        kits.clear();
        YamlConfiguration y = plugin.yaml("kits.yml");
        ConfigurationSection root = y.getConfigurationSection("kits");
        if (root == null) { plugin.getLogger().warning("kits.yml has no 'kits' section."); return; }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Currency cur = Currency.parse(s.getString("currency", "iron"));
            if (cur == null) cur = Currency.IRON;
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            List<ItemStack> items = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("items")) {
                ItemStack st = parse(m);
                if (st == null) plugin.getLogger().warning("kits.yml: item " + m.get("material") + " in kit '" + id + "' is not supported by this server version - skipped.");
                else items.add(st);
            }
            kits.put(id, new Kit(id, s.getString("name", id), icon == null ? Material.CHEST : icon, s.getStringList("description"),
                    Math.max(0, s.getLong("price", 0)), cur, Math.max(0, s.getLong("cooldown-seconds", 0)), items));
        }
    }

    private ItemStack parse(Map<?, ?> m) {
        Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
        if (mat == null || !mat.isItem()) return null;
        int amount = m.get("amount") instanceof Number n ? Math.max(1, Math.min(n.intValue(), mat.getMaxStackSize())) : 1;
        ItemStack st = new ItemStack(mat, amount);
        ItemMeta meta = st.getItemMeta();
        if (meta != null && m.get("name") != null) { meta.displayName(Text.c(String.valueOf(m.get("name")))); st.setItemMeta(meta); }
        if (m.get("enchants") instanceof Map<?, ?> en) {
            for (Map.Entry<?, ?> e : en.entrySet()) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(String.valueOf(e.getKey()).toLowerCase()));
                if (ench == null) { plugin.getLogger().warning("kits.yml: unknown enchantment " + e.getKey()); continue; }
                st.addUnsafeEnchantment(ench, e.getValue() instanceof Number n ? n.intValue() : 1);
            }
        }
        return st;
    }

    /** Remaining cooldown in seconds (0 = ready). */
    public long cooldownLeft(Account a, Kit k) {
        if (k.cooldown() <= 0) return 0;
        Long last = a.kitCooldowns.get(k.id());
        if (last == null) return 0;
        return Math.max(0, (last + k.cooldown() * 1000L - System.currentTimeMillis() + 999) / 1000);
    }

    public void purchase(Player p, Kit k) {
        Account a = plugin.economy.get(p);
        if (a == null) return;
        long left = cooldownLeft(a, k);
        if (left > 0) { plugin.send(p, "kit-cooldown", "%time%", Text.duration(left)); return; }
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : k.items()) items.add(it.clone());
        if (!Items.canFit(p, items)) { plugin.send(p, "kit-no-space"); return; }
        if (k.price() > 0 && (plugin.economy.spendable(p, k.currency()) < k.price()
                || !plugin.economy.charge(p, k.currency(), k.price(), "KIT", null))) {
            plugin.send(p, "kit-no-funds", "%price%", k.currency().fmt(k.price()));
            return;
        }
        Items.give(p, items);
        if (k.cooldown() > 0) stamp(a, k.id());
        plugin.send(p, "kit-bought", "%kit%", k.name(), "%price%", k.price() == 0 ? "&aFree" : k.currency().fmt(k.price()));
    }

    private void stamp(Account a, String id) {
        long now = System.currentTimeMillis();
        a.kitCooldowns.put(id, now);
        plugin.db.update("INSERT OR REPLACE INTO kit_cooldowns(uuid,kit,ts) VALUES(?,?,?)", a.id.toString(), id, now);
    }

    /** Free starter kit: given once, only to brand-new players, if enabled in config. */
    public void giveStarter(Player p) {
        if (!plugin.getConfig().getBoolean("starter-kit.enabled", false) || p.hasPlayedBefore()) return;
        Account a = plugin.economy.get(p);
        Kit k = kits.get(plugin.getConfig().getString("starter-kit.kit", "starter"));
        if (a == null || k == null || a.kitCooldowns.containsKey(STARTER_MARK)) return;
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : k.items()) items.add(it.clone());
        Items.give(p, items); // drops at feet if the inventory is somehow full
        stamp(a, STARTER_MARK);
        plugin.send(p, "kit-starter", "%kit%", k.name());
    }
}
