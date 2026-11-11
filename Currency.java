package dev.knightsmp.mods.economy;

import dev.knightsmp.mods.util.Text;
import org.bukkit.Material;

public enum Currency {
    IRON(Material.IRON_INGOT, "Iron", "&f"),
    DIAMOND(Material.DIAMOND, "Diamonds", "&b");

    public final Material material;
    public final String display;
    public final String color;

    Currency(Material material, String display, String color) {
        this.material = material; this.display = display; this.color = color;
    }
    public String fmt(long n) { return color + Text.num(n) + " " + display; }

    public static Currency parse(String s) {
        if (s == null) return null;
        switch (s.toLowerCase()) {
            case "iron": case "ingot": case "ingots": case "iron_ingot": return IRON;
            case "diamond": case "diamonds": return DIAMOND;
            default: return null;
        }
    }
}
