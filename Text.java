package dev.knightsmp.mods.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Text {
    private static final LegacyComponentSerializer L = LegacyComponentSerializer.legacyAmpersand();
    private Text() {}

    public static Component c(String s) {
        return L.deserialize(s == null ? "" : s).decoration(TextDecoration.ITALIC, false);
    }
    public static List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String s : lines) out.add(c(s));
        return out;
    }
    public static String num(long n) { return String.format(Locale.US, "%,d", n); }
    public static String duration(long sec) {
        if (sec < 0) sec = 0;
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
    public static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
