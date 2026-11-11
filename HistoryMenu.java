package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Currency;
import dev.knightsmp.mods.economy.EconomyManager.Tx;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HistoryMenu extends PagedMenu<Tx> {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private HistoryMenu(KnightPlugin plugin, Player player, List<Tx> list) {
        super(plugin, player, "&5&lKnightSMP &8» &7History", () -> new EconomyMenu(plugin, player));
        this.entries = list;
    }

    public static void show(KnightPlugin plugin, Player p) {
        plugin.economy.history(p.getUniqueId(), 112).thenAccept(list ->
                Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) new HistoryMenu(plugin, p, list).open(); }));
    }

    @Override
    protected ItemStack render(Tx t, int i) {
        Currency c = Currency.valueOf(t.currency());
        boolean plus = t.delta() >= 0;
        List<String> lore = new ArrayList<>();
        lore.add("&8Amount: " + (plus ? "&a+" : "&c-") + c.fmt(Math.abs(t.delta())));
        if (t.other() != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(t.other()));
            lore.add("&8Player: &f" + (op.getName() == null ? "Unknown" : op.getName()));
        }
        lore.add("&8" + FMT.format(Instant.ofEpochMilli(t.ts())));
        return Items.make(plus ? Material.LIME_DYE : Material.RED_DYE, "&f" + t.type().replace('_', ' '), lore, false);
    }
    @Override protected void onClick(Tx t, InventoryClickEvent e) {}
}
