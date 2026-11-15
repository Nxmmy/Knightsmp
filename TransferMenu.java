package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Money;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class TransferMenu extends Menu {
    private final UUID targetId;
    private final String targetName;
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
        Player target = Bukkit.getPlayer(targetId);
        Account a = plugin.economy.get(player);
        set(4, Items.head(target != null ? target : Bukkit.getOfflinePlayer(targetId), "&d" + targetName, List.of("&7Recipient"), false), null);
        set(13, Items.make(Material.GOLD_INGOT, "&6&l" + Money.fmt(amount), List.of("&8Your balance: " + Money.fmt(a == null ? 0 : a.dollars)), false), null);
        long[] d = {-1000, -100, -10, 10, 100, 1000};
        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int i = 0; i < d.length; i++) {
            long v = d[i];
            set(slots[i], Items.make(v < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE, (v < 0 ? "&c" : "&a+") + v), e -> adjust(v));
        }
        set(22, Items.make(Material.NAME_TAG, "&7Amount: &a$" + Text.num(amount)), null);
        set(30, Items.make(Material.RED_STAINED_GLASS_PANE, "&c-1"), e -> adjust(-1));
        set(32, Items.make(Material.LIME_STAINED_GLASS_PANE, "&a+1"), e -> adjust(1));
        set(31, Items.make(Material.EMERALD_BLOCK, "&a&lSend " + Money.fmt(amount), "&7to &f" + targetName, "&7You will be asked to confirm."), e -> {
            final long sendAmount = amount;
            go(new ConfirmMenu(plugin, player, "&5&lConfirm Payment",
                    Items.make(Material.PAPER, "&fSend " + Money.fmt(sendAmount), "&7To: &d" + targetName), () -> {
                Account ta = plugin.economy.cached(targetId);
                if (ta == null) { plugin.send(player, "player-not-found"); go(new EconomyMenu(plugin, player)); }
                else plugin.economy.pay(player, ta, sendAmount, () -> go(new EconomyMenu(plugin, player)));
            }, () -> go(this)));
        });
        set(40, Items.make(Material.DARK_OAK_DOOR, "&c← Back"), e -> go(new TransferTargetMenu(plugin, player)));
    }
}
