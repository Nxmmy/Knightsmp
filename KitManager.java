package dev.knightsmp.mods.kits;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.EconomyManager.Change;
import dev.knightsmp.mods.storage.Database;
import dev.knightsmp.mods.storage.Sql;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Money;
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
    public record Kit(String id, String name, Material icon, List<String> desc, long price, long cooldown, List<ItemStack> items) {}

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
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            List<ItemStack> items = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("items")) {
                ItemStack st = parse(m);
                if (st == null) plugin.getLogger().warning("kits.yml: item " + m.get("material") + " in kit '" + id + "' is not supported by this server version - skipped.");
                else items.add(st);
            }
            kits.put(id, new Kit(id, s.getString("name", id), icon == null ? Material.CHEST : icon, s.getStringList("description"),
                    Math.max(0, s.getLong("price", 0)), Math.max(0, s.getLong("cooldown-seconds", 0)), items));
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

    private List<ItemStack> copy(Kit k) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : k.items()) items.add(it.clone());
        return items;
    }

    /** Charge + cooldown stamp in one transaction, then deliver; refund (and un-stamp) if delivery is impossible. */
    public void purchase(Player p, Kit k, Runnable after) {
        Account a = plugin.economy.get(p);
        if (a == null) { after.run(); return; }
        long left = cooldownLeft(a, k);
        if (left > 0) { plugin.send(p, "kit-cooldown", "%time%", Text.duration(left)); after.run(); return; }
        if (!Items.canFit(p, copy(k))) { plugin.send(p, "kit-no-space"); after.run(); return; }
        if (a.dollars < k.price()) { plugin.send(p, "kit-no-funds", "%price%", Money.fmt(k.price()), "%balance%", Money.fmt(a.dollars)); after.run(); return; }
        if (!plugin.economy.tryLock(a.id)) { plugin.send(p, "tx-busy"); after.run(); return; }

        final long now = System.currentTimeMillis();
        final String uid = a.id.toString();
        final boolean stamp = k.cooldown() > 0;
        List<Change> charge = k.price() > 0 ? List.of(new Change(a.id, -k.price(), null, "KIT")) : List.of();
        plugin.economy.commit("KIT", charge, c -> {
            if (stamp) Database.run(c, Sql.KIT_STAMP, uid, k.id(), now);
            return true;
        }, () -> { if (stamp) a.kitCooldowns.put(k.id(), now); }, ok -> {
            if (!ok) { plugin.economy.unlock(a.id); plugin.send(p, "kit-no-funds", "%price%", Money.fmt(k.price()), "%balance%", Money.fmt(a.dollars)); after.run(); return; }
            List<ItemStack> items = copy(k);
            if (p.isOnline() && Items.canFit(p, items)) {
                Items.give(p, items);
                plugin.economy.unlock(a.id);
                plugin.send(p, "kit-bought", "%kit%", k.name(), "%price%", Money.fmt(k.price()));
                after.run();
            } else {
                plugin.getLogger().warning("Kit delivery failed for " + p.getName() + " (" + k.id() + "); refunding " + k.price());
                List<Change> refund = k.price() > 0 ? List.of(new Change(a.id, k.price(), null, "KIT_REFUND")) : List.of();
                plugin.economy.commit("KIT_REFUND", refund, c -> { if (stamp) Database.run(c, Sql.KIT_UNSTAMP, uid, k.id()); return true; },
                        () -> { if (stamp) a.kitCooldowns.remove(k.id()); }, ok2 -> {
                    if (!ok2) plugin.getLogger().severe("REFUND FAILED for " + p.getName() + ": owed $" + k.price() + " - fix manually with /moneyadmin give.");
                    plugin.economy.unlock(a.id);
                    plugin.send(p, "kit-refunded");
                    after.run();
                });
            }
        });
    }

    /** Free starter kit: given once, only to brand-new players, if enabled in config. */
    public void giveStarter(Player p) {
        if (!plugin.getConfig().getBoolean("starter-kit.enabled", false) || p.hasPlayedBefore()) return;
        Account a = plugin.economy.get(p);
        Kit k = kits.get(plugin.getConfig().getString("starter-kit.kit", "starter"));
        if (a == null || k == null || a.kitCooldowns.containsKey(STARTER_MARK)) return;
        long now = System.currentTimeMillis();
        a.kitCooldowns.put(STARTER_MARK, now);
        plugin.db.update(Sql.KIT_STAMP, a.id.toString(), STARTER_MARK, now);
        Items.give(p, copy(k)); // drops at feet if the inventory is somehow full
        plugin.send(p, "kit-starter", "%kit%", k.name());
    }
}
