package dev.knightsmp.mods.gui;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.util.Items;
import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The payment popup: the receiver's own head in the centre of a golden ring, rays spreading outwards, the amount and the
 * sender underneath. It is a plain chest menu, so it renders identically for Java and Bedrock (Geyser) players.
 * Nothing in it can be taken; clicks are cancelled by the shared menu listener.
 */
public final class PaymentMenu extends Menu {
    private static final int CENTER = 22;                                     // row 2, column 4
    private static final int[] RING1 = {12, 13, 14, 21, 23, 30, 31, 32};      // the golden circle
    private static final int[] RING2 = {3, 4, 5, 11, 15, 20, 24, 29, 33, 39, 40, 41};
    private static final int[][] RAYS = {                                     // slots lit at ray length 1, 2, 3
            {19, 25, 2, 6, 38, 42},
            {18, 26, 1, 7, 46, 52},
            {0, 8, 45, 53},
    };

    private final String sender;
    private final long amount;
    private final String rarityName, rarityColor;

    public PaymentMenu(KnightPlugin plugin, Player receiver, String sender, long amount, String rarityName, String rarityColor) {
        super(plugin, receiver, 6, "&8» " + rarityColor + "&l" + rarityName + " &8| &a+$" + Text.num(amount));
        this.sender = sender; this.amount = amount; this.rarityName = rarityName; this.rarityColor = rarityColor;
    }

    @Override
    protected void build() {
        frame();
        set(CENTER, Items.head(player, "&e&l" + player.getName(), List.of("&7Payment received", "&fFrom: &e" + sender, "&aAmount: +$" + Text.num(amount)), true), null);
        set(48, Items.make(Material.PAPER, "&fFrom: &e" + sender), null);
        set(49, Items.make(Material.GOLD_INGOT, "&6&l+$" + Text.num(amount), List.of("&7added to your balance"), true), null);
        set(50, Items.make(Material.NETHER_STAR, rarityColor + "&l" + rarityName, List.of("&7Payment rarity"), true), null);
        animate(0);
    }

    /** Draws animation frame f: the ring pulses and the rays grow outward, then shimmer. */
    public void animate(int f) {
        boolean even = f % 2 == 0;
        for (int s : RING1) inv.setItem(s, Items.make(Material.GOLD_BLOCK, "&6 ", List.of(), true));
        Material glow = even ? Material.ORANGE_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
        for (int s : RING2) inv.setItem(s, Items.make(glow, "&e "));
        int length = Math.min(RAYS.length, f / 3);                            // a new ray tier every 3 frames
        for (int tier = 0; tier < RAYS.length; tier++) {
            Material m = tier < length ? ((f + tier) % 2 == 0 ? Material.YELLOW_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE) : Material.GRAY_STAINED_GLASS_PANE;
            for (int s : RAYS[tier]) inv.setItem(s, Items.make(m, " "));
        }
    }
}
