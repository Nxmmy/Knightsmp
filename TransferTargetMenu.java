package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class TransferTargetMenu extends PagedMenu<Player> {
    public TransferTargetMenu(KnightPlugin plugin, Player player) {
        super(plugin, player, "&5&lKnightSMP &8» &7Choose Player", () -> new EconomyMenu(plugin, player));
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.getUniqueId().equals(player.getUniqueId())) entries.add(p);
    }
    @Override protected ItemStack render(Player p, int i) { return Items.head(p, "&d" + p.getName(), List.of("&7Click to send money"), false); }
    @Override protected void onClick(Player p, InventoryClickEvent e) { go(new TransferMenu(plugin, player, p)); }
}
