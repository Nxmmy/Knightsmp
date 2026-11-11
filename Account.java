package dev.knightsmp.mods.economy;

import dev.knightsmp.mods.teleport.TeleportManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** In-memory account. Mutated only on the server thread; persisted asynchronously. */
public final class Account {
    public final UUID id;
    public String name;
    public long iron, diamonds, bounty;
    public TeleportManager.Home home;
    public final Map<String, Long> kitCooldowns = new HashMap<>();

    public Account(UUID id, String name) { this.id = id; this.name = name; }

    public long get(Currency c) { return c == Currency.IRON ? iron : diamonds; }
    public void set(Currency c, long v) { if (c == Currency.IRON) iron = v; else diamonds = v; }
}
