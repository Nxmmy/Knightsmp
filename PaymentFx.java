package dev.knightsmp.mods.fx;

import dev.knightsmp.mods.KnightPlugin;
import dev.knightsmp.mods.economy.Account;
import dev.knightsmp.mods.gui.PaymentMenu;
import dev.knightsmp.mods.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The receiver's payment animation. Built only from things Java and Bedrock (through Geyser) both show:
 * a chest-menu popup (head + golden ring + rays), a shimmering rarity title, an action-bar ray line, particles and sounds.
 * If the popup cannot be shown (player in combat, another menu open, animation disabled) the title/particles still play.
 * Several payments are queued and played one after another; a burst beyond the queue limit is merged into one summary.
 */
public final class PaymentFx {
    private record Rarity(String name, long min, String color) {}
    private static final class Payment {
        String sender; long amount; int count = 1;
        Payment(String sender, long amount) { this.sender = sender; this.amount = amount; }
    }

    private final KnightPlugin plugin;
    private final Map<UUID, Deque<Payment>> queues = new HashMap<>();   // server thread only
    private final Set<UUID> playing = new HashSet<>();
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, PaymentMenu> menus = new HashMap<>();

    public PaymentFx(KnightPlugin plugin) { this.plugin = plugin; }

    private boolean on(String key, boolean def) { return plugin.getConfig().getBoolean("payment-animation." + key, def); }

    private List<Rarity> rarities() {
        List<Rarity> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("payment-animation.rarities")) {
            long min = m.get("min") instanceof Number n ? n.longValue() : 1;
            out.add(new Rarity(String.valueOf(m.get("name")), min, m.get("color") == null ? "&f" : String.valueOf(m.get("color"))));
        }
        if (out.isEmpty()) out.add(new Rarity("PAYMENT", 0, "&6"));
        out.sort((a, b) -> Long.compare(a.min(), b.min()));
        return out;
    }
    private Rarity rarityFor(long amount) {
        Rarity best = null;
        for (Rarity r : rarities()) if (amount >= r.min()) best = r;
        return best != null ? best : rarities().get(0);
    }

    /** Called after a payment has been committed. Never throws into the payment code. */
    public void play(Player receiver, String sender, long amount) {
        if (!on("enabled", true) || amount <= 0) return;
        Account a = plugin.economy.get(receiver);
        if (a != null && a.animOff) return;
        UUID id = receiver.getUniqueId();
        Deque<Payment> q = queues.computeIfAbsent(id, k -> new ArrayDeque<>());
        int max = Math.max(1, plugin.getConfig().getInt("payment-animation.max-queue", 5));
        if (q.size() >= max) {                                    // burst: merge into the last waiting entry instead of piling up
            Payment last = q.peekLast();
            last.amount += amount; last.count++; last.sender = last.count + " players";
        } else q.addLast(new Payment(sender, amount));
        if (!playing.contains(id)) next(receiver);
    }

    private void next(Player p) {
        UUID id = p.getUniqueId();
        Deque<Payment> q = queues.get(id);
        Payment pay = q == null ? null : q.pollFirst();
        if (pay == null || !p.isOnline()) { playing.remove(id); queues.remove(id); return; }
        playing.add(id);
        run(p, pay, () -> Bukkit.getScheduler().runTaskLater(plugin, () -> next(p), 10L));
    }

    private void run(Player p, Payment pay, Runnable done) {
        UUID id = p.getUniqueId();
        Rarity rarity = rarityFor(pay.amount);
        final int step = 2;
        final int total = Math.max(20, plugin.getConfig().getInt("payment-animation.duration-ticks", 70));

        PaymentMenu menu = null;
        boolean wantGui = on("gui", true) && !plugin.combat.inCombat(p)
                && p.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING;   // nothing else open
        if (wantGui) {
            menu = new PaymentMenu(plugin, p, pay.sender, pay.amount, rarity.name(), rarity.color());
            menu.open();
            menus.put(id, menu);
        }
        final PaymentMenu popup = menu;
        start(p, pay, rarity);
        final int[] frame = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, (BukkitTask task) -> {
            tasks.put(id, task);
            int f = frame[0]++;
            boolean guiOpen = popup != null && p.getOpenInventory().getTopInventory().getHolder() == popup;
            if (f * step >= total || !p.isOnline()) {
                task.cancel();
                tasks.remove(id);
                menus.remove(id);
                if (guiOpen) p.closeInventory();
                if (p.isOnline()) p.clearTitle();
                done.run();
                return;
            }
            frameFx(p, pay, rarity, f);
            if (guiOpen) popup.animate(f);
        }, step, step);
    }

    private void start(Player p, Payment pay, Rarity r) {
        if (on("sounds", true)) {
            boolean top = r.name().equalsIgnoreCase("LEGENDARY") || r.name().equalsIgnoreCase("EPIC");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, top ? 1.2f : 1.0f);
            if (r.name().equalsIgnoreCase("LEGENDARY")) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f);
            else p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.1f);
        }
        if (on("particles", true)) {
            Location c = p.getLocation().add(0, 1.0, 0);
            p.spawnParticle(Particle.TOTEM_OF_UNDYING, c, 30, 0.5, 0.6, 0.5, 0.2);
            if (r.name().equalsIgnoreCase("LEGENDARY")) p.spawnParticle(Particle.FIREWORK, c, 25, 0.4, 0.5, 0.4, 0.1);
        }
    }

    /** One animation frame: shimmering title, expanding ray line in the action bar, orbiting golden particles. */
    private void frameFx(Player p, Payment pay, Rarity r, int f) {
        if (f % 2 == 0) {
            String banner = "* " + r.name() + " *";
            String[] pal = r.name().equalsIgnoreCase("LEGENDARY") ? new String[]{"6", "e", "f", "e"} : new String[]{r.color().replace("&", ""), "f"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < banner.length(); i++) sb.append('&').append(pal[(i + f / 2) % pal.length]).append("&l").append(banner.charAt(i));
            Component title = Text.c(sb.toString());
            Component sub = Text.c("&a+$" + Text.num(pay.amount) + " &7from &f" + pay.sender);
            p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ofMillis(200))));
        }
        int rays = Math.min(12, 1 + f / 2);
        String line = "&e" + "=".repeat(rays) + " &6&l* &a&l+$" + Text.num(pay.amount) + " &6&l* &e" + "=".repeat(rays);
        p.sendActionBar(Text.c(line));
        if (on("particles", true)) {
            double a = f * 0.55;
            Location base = p.getLocation().add(0, 1.0 + 0.35 * Math.sin(f * 0.3), 0);
            for (int i = 0; i < 3; i++) {
                double ang = a + i * (2 * Math.PI / 3);
                Location l = base.clone().add(Math.cos(ang) * 1.2, 0, Math.sin(ang) * 1.2);
                p.spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0);
                p.spawnParticle(Particle.DUST, l, 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(Color.fromRGB(255, 200, 30), 1.3f));
            }
        }
    }

    public void onQuit(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask t = tasks.remove(id);
        if (t != null) t.cancel();
        menus.remove(id);
        queues.remove(id);
        playing.remove(id);
    }

    public void shutdown() {
        for (BukkitTask t : tasks.values()) t.cancel();
        for (UUID id : new ArrayList<>(menus.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) p.closeInventory(); }
        tasks.clear(); menus.clear(); queues.clear(); playing.clear();
    }
}
