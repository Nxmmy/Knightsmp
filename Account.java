package dev.knightsmp.mods.economy;

import dev.knightsmp.mods.home.HomeManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** In-memory mirror of a player's row. The database is authoritative; changes are applied here only after they commit. */
public final class Account {
    public final UUID id;
    public String name;
    public long dollars, bounty, kills, deaths, streak, bestStreak, playtime;   // playtime in seconds
    public boolean animOff;
    public long sessionStart;   // ms; when the current online session was last counted into playtime (not saved)
    public HomeManager.Home home;
    public final Map<String, Long> kitCooldowns = new HashMap<>();

    public Account(UUID id, String name) { this.id = id; this.name = name; }
}
