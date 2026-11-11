package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.bounty.BountyManager.Entry;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class BountyMenu extends PagedMenu<Entry> {
    private BountyMenu(KnightPlugin plugin, Player player, List<Entry> list) {
        super(plugin, player, "&5&lKnightSMP &8» &7Bounties", null);
        this.entries = list;
    }

    public static void show(KnightPlugin plugin, Player p) {
        plugin.bounty.leaderboard(500, 0).thenAccept(list ->
                Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) new BountyMenu(plugin, p, list).open(); }));
    }

    @Override
    protected ItemStack render(Entry e, int index) {
        int rank = index + 1;
        String color = switch (rank) { case 1 -> "&6&l"; case 2 -> "&f&l"; case 3 -> "&c&l"; default -> "&7"; };
        String tag = rank == 1 ? " &5&l★ WANTED #1" : rank <= 3 ? " &d★" : "";
        return Items.head(Bukkit.getOfflinePlayer(e.id()), color + e.name() + tag,
                List.of("&8Rank: &f#" + rank, "&8Bounty: &d" + Text.num(e.bounty())), rank <= 3);
    }

    @Override protected void onClick(Entry e, InventoryClickEvent ev) {}

    @Override
    protected void extra() {
        long mine = plugin.economy.get(player) == null ? 0 : plugin.economy.get(player).bounty;
        set(4, Items.head(player, "&d" + player.getName(), List.of("&8Your bounty: &d" + Text.num(mine),
                "&7Kill players to raise it.", "&7Die in PvP and it resets."), false), null);
    }
}
