package dev.knightsmp.mods.util;

/** Dollar formatting. All balances are whole dollars stored as long (no floating point). */
public final class Money {
    private Money() {}
    public static String fmt(long n) { return "&a$" + Text.num(n); }
    /** Like fmt, but 0 shows as Free (used for shop prices). */
    public static String price(long n) { return n <= 0 ? "&a&lFREE" : fmt(n); }
}
