package dev.knightsmp.mods.shop;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.EconomyManager.Change;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Money;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The /shop catalogue. Built from four config files:
 *  shop.yml     - categories and hand-priced items (ores, combat, food, tools, misc)
 *  blocks.yml   - rules that price EVERY obtainable block/item the running server knows (read from the Material registry)
 *  enchants.yml - prices for enchantment books; one book per enchantment per level is generated from the server registry
 *  potions.yml  - prices for potions; every potion type x (potion, splash, lingering) is generated from the server registry
 */
public final class ShopManager {
    /** One purchasable entry. 'contents' is what ONE unit of purchase delivers; 'price' is the cost of that one unit. */
    public record ShopItem(String label, ItemStack display, List<ItemStack> contents, long price, String desc) {
        /** The stacks delivered for qty units (split into legal stack sizes). */
        public List<ItemStack> delivery(int qty) {
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack s : contents) {
                long total = (long) s.getAmount() * qty;
                int max = Math.max(1, s.getMaxStackSize());
                while (total > 0) {
                    int n = (int) Math.min(max, total);
                    ItemStack c = s.clone();
                    c.setAmount(n);
                    out.add(c);
                    total -= n;
                }
            }
            return out;
        }
    }
    public record Category(String id, String name, Material icon, List<String> desc, List<ShopItem> items) {}

    private record Rule(Pattern match, String category, double unit, int amount, boolean anyItem) {}

    private final KnightPlugin plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public ShopManager(KnightPlugin plugin) { this.plugin = plugin; }
    public Map<String, Category> categories() { return categories; }

    // ---------------------------------------------------------------- loading
    public void load() {
        categories.clear();
        YamlConfiguration y = plugin.yaml("shop.yml");
        ConfigurationSection root = y.getConfigurationSection("categories");
        if (root == null) { plugin.getLogger().warning("shop.yml has no 'categories' section."); return; }
        Map<String, List<ShopItem>> items = new LinkedHashMap<>();
        Set<Material> explicit = new HashSet<>();
        Map<String, ConfigurationSection> defs = new LinkedHashMap<>();
        Set<String> dedupe = new HashSet<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            defs.put(id, s);
            if (s.getBoolean("exclude-sold", false)) dedupe.add(id);   // items already in /sell are never repeated in this category
            List<ShopItem> list = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("items")) {
                ShopItem it = parseExplicit(m, id, explicit);
                if (it != null) list.add(it);
            }
            items.put(id, list);
        }
        Map<String, Integer> curated = new LinkedHashMap<>();
        for (Map.Entry<String, List<ShopItem>> e : items.entrySet()) curated.put(e.getKey(), e.getValue().size());
        applyBlockRules(items, explicit, curated, dedupe, soldMaterials());
        for (Map.Entry<String, ConfigurationSection> e : defs.entrySet()) {
            String gen = e.getValue().getString("generate", "");
            if (gen.equalsIgnoreCase("enchantments")) items.get(e.getKey()).addAll(enchantBooks());
            else if (gen.equalsIgnoreCase("potions")) items.get(e.getKey()).addAll(potions());
        }
        int total = 0;
        for (Map.Entry<String, ConfigurationSection> e : defs.entrySet()) {
            ConfigurationSection s = e.getValue();
            List<ShopItem> list = items.get(e.getKey());
            if (list.isEmpty()) continue;
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            categories.put(e.getKey(), new Category(e.getKey(), s.getString("name", e.getKey()), icon == null ? Material.CHEST : icon, s.getStringList("description"), list));
            total += list.size();
        }
        plugin.getLogger().info("Shop loaded: " + categories.size() + " categories, " + total + " items.");
    }

    private ShopItem parseExplicit(Map<?, ?> m, String category, Set<Material> explicit) {
        long price = m.get("price") instanceof Number n ? n.longValue() : -1;
        if (price < 0) { plugin.getLogger().warning("shop.yml: entry without a valid price skipped: " + m); return null; }
        boolean optional = Boolean.TRUE.equals(m.get("optional"));
        String desc = m.get("desc") == null ? null : String.valueOf(m.get("desc"));
        if (m.get("set") instanceof List<?> set) {               // several different items sold as one purchase (armor sets)
            List<ItemStack> contents = new ArrayList<>();
            for (Object o : set) {
                Material mat = Material.matchMaterial(String.valueOf(o));
                if (mat == null || !mat.isItem()) { if (!optional) plugin.getLogger().warning("shop.yml: unknown item " + o + " in set - whole set skipped."); return null; }
                contents.add(new ItemStack(mat));
            }
            Material icon = Material.matchMaterial(String.valueOf(m.get("icon")));
            if (contents.isEmpty()) return null;
            ItemStack display = new ItemStack(icon != null ? icon : contents.get(0).getType());
            ItemMeta meta = display.getItemMeta();
            String name = String.valueOf(m.get("name"));
            if (meta != null) { meta.displayName(Text.c("&f" + name)); display.setItemMeta(meta); }
            return new ShopItem(name, display, contents, price, desc);
        }
        Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
        if (mat == null || !mat.isItem()) {
            if (!optional) plugin.getLogger().warning("shop.yml: skipping unknown item " + m.get("material") + " in category " + category);
            return null;
        }
        int amount = m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        explicit.add(mat);
        return new ShopItem(Text.pretty(mat), new ItemStack(mat, Math.min(amount, Math.max(1, mat.getMaxStackSize()))), Items.stacks(mat, amount), price, desc);
    }

    // ---------------------------------------------------------------- blocks.yml rules
    private void applyBlockRules(Map<String, List<ShopItem>> items, Set<Material> explicit, Map<String, Integer> curated, Set<String> dedupe, Set<Material> sold) {
        YamlConfiguration y = plugin.yaml("blocks.yml");
        List<Pattern> exclude = new ArrayList<>();
        for (String r : y.getStringList("exclude")) exclude.add(Pattern.compile(r));
        List<Rule> rules = new ArrayList<>();
        for (Map<?, ?> m : y.getMapList("rules")) {
            try {
                rules.add(new Rule(Pattern.compile(String.valueOf(m.get("match"))), String.valueOf(m.get("category")),
                        m.get("unit") instanceof Number n ? n.doubleValue() : 1.0,
                        m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 16,
                        Boolean.TRUE.equals(m.get("item"))));
            } catch (Exception e) { plugin.getLogger().warning("blocks.yml: bad rule " + m + ": " + e.getMessage()); }
        }
        ConfigurationSection fb = y.getConfigurationSection("fallback");
        String fbCat = fb == null ? "" : fb.getString("category", "");
        double fbUnit = fb == null ? 1.5 : fb.getDouble("unit", 1.5);
        int fbAmount = fb == null ? 8 : Math.max(1, fb.getInt("amount", 8));

        int added = 0, fallback = 0, skipped = 0, soldSkipped = 0, unlisted = 0;
        List<String> fallbackNames = new ArrayList<>();
        for (Material mat : Material.values()) {
            if (mat.isLegacy() || !mat.isItem()) continue;
            String name = mat.name();
            if (explicit.contains(mat)) continue;
            if (matchesAny(exclude, name)) { skipped++; continue; }
            Rule hit = null;
            for (Rule r : rules) {
                if (!r.match().matcher(name).matches()) continue;
                if (!mat.isBlock() && !r.anyItem()) continue;   // plain items are only shop-listed by rules that say item: true
                hit = r; break;
            }
            String cat; double unit; int amount;
            if (hit != null) { cat = hit.category(); unit = hit.unit(); amount = hit.amount(); }
            else if (mat.isBlock()) {
                if (fbCat.isBlank()) { unlisted++; continue; }     // no catch-all category: unmatched blocks are not sold
                cat = fbCat; unit = fbUnit; amount = fbAmount; fallback++; fallbackNames.add(name);
            }
            else continue;
            if (dedupe.contains(cat) && sold.contains(mat)) { soldSkipped++; continue; }   // already in /sell: not offered here
            List<ShopItem> list = items.get(cat);
            if (list == null) { plugin.getLogger().warning("blocks.yml: category '" + cat + "' is not defined in shop.yml (" + name + ")"); continue; }
            long price = unit <= 0 ? 0 : Math.max(1, Math.round(unit * amount));
            list.add(new ShopItem(Text.pretty(mat), new ItemStack(mat, Math.min(amount, Math.max(1, mat.getMaxStackSize()))), Items.stacks(mat, amount), price, null));
            added++;
        }
        for (Map.Entry<String, List<ShopItem>> e : items.entrySet()) {
            List<ShopItem> l = e.getValue();
            int from = Math.min(curated.getOrDefault(e.getKey(), 0), l.size());
            l.subList(from, l.size()).sort((a, b) -> a.label().compareTo(b.label()));   // curated items keep their listed order
        }
        plugin.getLogger().info("blocks.yml: " + added + " items priced by rules (" + skipped + " unobtainable excluded, " + soldSkipped + " left out because /sell already buys them, " + unlisted + " blocks have no shop rule and are not sold, " + fallback + " used the fallback price).");
        if (!fallbackNames.isEmpty()) plugin.getLogger().info("Fallback-priced (add a rule in blocks.yml to tune): " + String.join(", ", fallbackNames.subList(0, Math.min(40, fallbackNames.size()))) + (fallbackNames.size() > 40 ? " ..." : ""));
    }

    /** The real /sell registry, read from market.yml, so the shop never repeats an item that /sell already handles. */
    private Set<Material> soldMaterials() {
        Set<Material> out = new HashSet<>();
        ConfigurationSection cats = plugin.yaml("market.yml").getConfigurationSection("categories");
        if (cats == null) return out;
        for (String id : cats.getKeys(false)) {
            ConfigurationSection c = cats.getConfigurationSection(id);
            if (c == null) continue;
            for (Map<?, ?> m : c.getMapList("items")) {
                Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
                if (mat != null) out.add(mat);
            }
        }
        return out;
    }

    private static boolean matchesAny(List<Pattern> ps, String s) {
        for (Pattern p : ps) if (p.matcher(s).matches()) return true;
        return false;
    }

    // ---------------------------------------------------------------- enchantment books
    private List<ShopItem> enchantBooks() {
        YamlConfiguration y = plugin.yaml("enchants.yml");
        ConfigurationSection prices = y.getConfigurationSection("prices");
        double perLevel = y.getDouble("per-level-factor", 0.9);
        long defaultBase = y.getLong("default-base", 120);
        List<Enchantment> all = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) all.add(e);
        all.sort((a, b) -> a.getKey().getKey().compareTo(b.getKey().getKey()));
        List<ShopItem> out = new ArrayList<>();
        for (Enchantment en : all) {
            String key = en.getKey().getKey();
            long base = prices != null && prices.contains(key) ? prices.getLong(key) : -1;
            if (base < 1) {
                base = defaultBase;
                plugin.getLogger().warning("enchants.yml: no price for enchantment '" + key + "' (new in this version?) - using default-base " + defaultBase);
            }
            for (int lvl = 1; lvl <= en.getMaxLevel(); lvl++) {
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                if (!(book.getItemMeta() instanceof EnchantmentStorageMeta meta) || !meta.addStoredEnchant(en, lvl, false)) continue;
                book.setItemMeta(meta);
                long price = Math.max(1, Math.round(base * (1 + perLevel * (lvl - 1))));
                String label = pretty(key) + (en.getMaxLevel() > 1 ? " " + roman(lvl) : "") + " Book";
                out.add(new ShopItem(label, book, List.of(book), price, null));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- potions
    private List<ShopItem> potions() {
        YamlConfiguration y = plugin.yaml("potions.yml");
        ConfigurationSection prices = y.getConfigurationSection("prices");
        double longMult = y.getDouble("long-multiplier", 1.5), strongMult = y.getDouble("strong-multiplier", 1.8);
        double splashMult = y.getDouble("splash-multiplier", 1.3), lingerMult = y.getDouble("lingering-multiplier", 3.0);
        long defaultBase = y.getLong("default-base", 60);
        Set<String> excluded = new HashSet<>();
        for (String s : y.getStringList("exclude")) excluded.add(s.toUpperCase(Locale.ROOT));
        List<ShopItem> out = new ArrayList<>();
        Material[] forms = {Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION};
        String[] formName = {"", "Splash ", "Lingering "};
        double[] formMult = {1.0, splashMult, lingerMult};
        for (PotionType type : PotionType.values()) {
            String name = type.name();
            if (excluded.contains(name)) continue;
            double variant = 1.0;
            String base = name;
            if (name.startsWith("LONG_")) { variant = longMult; base = name.substring(5); }
            else if (name.startsWith("STRONG_")) { variant = strongMult; base = name.substring(7); }
            long basePrice = prices != null && prices.contains(base) ? prices.getLong(base) : -1;
            if (basePrice < 1) {
                basePrice = defaultBase;
                plugin.getLogger().warning("potions.yml: no price for potion '" + base + "' (new in this version?) - using default-base " + defaultBase);
            }
            for (int i = 0; i < forms.length; i++) {
                ItemStack st = new ItemStack(forms[i]);
                if (!(st.getItemMeta() instanceof PotionMeta pm)) continue;
                pm.setBasePotionType(type);
                st.setItemMeta(pm);
                long price = Math.max(1, Math.round(basePrice * variant * formMult[i]));
                out.add(new ShopItem(formName[i] + pretty(name) + " Potion", st, List.of(st), price, null));
            }
        }
        return out;
    }

    private static String pretty(String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : key.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    // ---------------------------------------------------------------- buying
    public int maxQuantity() { return plugin.getConfig().getInt("shop.max-quantity", 64); }

    /** Free items are limited to one per click; everything else to the configured maximum that still fits an inventory. */
    public int maxQuantity(ShopItem it) {
        if (it.price() <= 0) return 1;
        for (int q = Math.max(1, maxQuantity()); q > 1; q--) if (it.delivery(q).size() <= 36) return q;
        return 1;
    }

    /**
     * Charge first (atomic, conditional on funds), then deliver; if delivery turns out to be impossible
     * (inventory filled meanwhile, player left) the charge is refunded. A player is never charged without getting the item.
     * The price charged is always exactly price x quantity of the entry that was clicked.
     */
    public void purchase(Player p, ShopItem it, int qty, Runnable after) {
        Account a = plugin.economy.get(p);
        if (a == null) { after.run(); return; }
        if (qty < 1 || qty > maxQuantity(it)) { plugin.send(p, "invalid-amount"); after.run(); return; }
        final long total;
        try { total = Math.multiplyExact(it.price(), (long) qty); }
        catch (ArithmeticException e) { plugin.send(p, "invalid-amount"); after.run(); return; }
        final List<ItemStack> stacks = it.delivery(qty);
        if (!Items.canFit(p, stacks)) { plugin.send(p, "shop-no-space"); after.run(); return; }
        if (a.dollars < total) { plugin.send(p, "shop-no-funds", "%price%", Money.fmt(total), "%balance%", Money.fmt(a.dollars)); after.run(); return; }
        if (!plugin.economy.tryLock(a.id)) { plugin.send(p, "tx-busy"); after.run(); return; }

        final String what = (qty > 1 ? qty + "x " : "") + it.label();
        if (total == 0) {                                        // free item (armor trims): nothing to charge
            Items.give(p, stacks);
            plugin.economy.unlock(a.id);
            plugin.send(p, "shop-bought", "%item%", what, "%price%", Money.price(0));
            after.run();
            return;
        }
        plugin.economy.commit("SHOP", List.of(new Change(a.id, -total, null, "SHOP")), null, null, ok -> {
            if (!ok) { plugin.economy.unlock(a.id); plugin.send(p, "shop-no-funds", "%price%", Money.fmt(total), "%balance%", Money.fmt(a.dollars)); after.run(); return; }
            if (p.isOnline() && Items.canFit(p, stacks)) {
                Items.give(p, stacks);
                plugin.economy.unlock(a.id);
                plugin.send(p, "shop-bought", "%item%", what, "%price%", Money.fmt(total));
                after.run();
            } else {
                plugin.getLogger().warning("Shop delivery failed for " + p.getName() + " (" + what + "); refunding " + total);
                plugin.economy.commit("SHOP_REFUND", List.of(new Change(a.id, total, null, "SHOP_REFUND")), null, null, ok2 -> {
                    if (!ok2) plugin.getLogger().severe("REFUND FAILED for " + p.getName() + ": owed $" + total + " - fix manually with /moneyadmin give.");
                    plugin.economy.unlock(a.id);
                    plugin.send(p, "shop-refunded");
                    after.run();
                });
            }
        });
    }
}
