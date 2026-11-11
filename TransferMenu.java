package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.economy.Currency;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class TransferMenu extends Menu {
    private final UUID targetId;
    private final String targetName;
    private Currency currency = Currency.IRON;
    private long amount = 1;

    public TransferMenu(KnightPlugin plugin, Player player, Player target) {
        super(plugin, player, 5, "&5&lKnightSMP &8» &7Transfer");
        this.targetId = target.getUniqueId(); this.targetName = target.getName();
    }

    private void adjust(long d) {
        amount = Math.max(1, Math.min(plugin.economy.max(), amount + d));
        rebuild();
    }

    @Override
    protected void build() {
        frame();
        Player target = org.bukkit.Bukkit.getPlayer(targetId);
        Account a = plugin.economy.get(player);
        set(4, Items.head(target != null ? target : org.bukkit.Bukkit.getOfflinePlayer(targetId), "&d" + targetName, List.of("&7Recipient"), false), null);
        set(13, Items.make(currency.material, currency.color + "&l" + Text.num(amount) + " " + currency.display,
                List.of("&8Your spendable: " + currency.fmt(plugin.economy.spendable(player, currency)),
                        a == null ? "" : "&8Account balance: &7" + Text.num(a.get(currency))), false), null);
        long[] d = {-100, -10, -1, 1, 10, 100};
        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int i = 0; i < d.length; i++) {
            long v = d[i];
            set(slots[i], Items.make(v < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE, (v < 0 ? "&c" : "&a+") + v), e -> adjust(v));
        }
        set(22, Items.make(Material.NAME_TAG, "&7Amount: &f" + Text.num(amount)), null);
        set(30, Items.make(currency == Currency.IRON ? Material.IRON_INGOT : Material.DIAMOND, "&eCurrency: " + currency.color + currency.display, "&7Click to switch"),
                e -> { currency = currency == Currency.IRON ? Currency.DIAMOND : Currency.IRON; rebuild(); });
        set(32, Items.make(Material.EMERALD_BLOCK, "&a&lSend " + currency.fmt(amount), "&7to &f" + targetName, "&7You will be asked to confirm."), e -> {
            ConfirmMenu cm = new ConfirmMenu(plugin, player, "&5&lConfirm Payment",
                    Items.make(Material.PAPER, "&fSend " + currency.fmt(amount), "&7To: &d" + targetName), () -> {
                Player t = org.bukkit.Bukkit.getPlayer(targetId);
                Account ta = plugin.economy.cached(targetId);
                if (t == null || ta == null) plugin.send(player, "player-not-found");
                else plugin.economy.pay(player, ta, currency, amount);
                go(new EconomyMenu(plugin, player));
            }, () -> go(this));
            go(cm);
        });
        set(40, Items.make(Material.DARK_OAK_DOOR, "&c← Back"), e -> go(new TransferTargetMenu(plugin, player)));
    }
}
