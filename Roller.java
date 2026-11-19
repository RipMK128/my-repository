package dev.smp.eclipsepowers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Slot-machine style power roll. A strip of power icons scrolls through the middle row and slows
 * down until it lands on the rolled power. The two rows around it show other powers drifting by.
 */
public final class Roller {

    private static final int STEPS = 30;
    private static final int LEN = STEPS + 9; // 9 visible icons
    private static final int CENTER = 4;

    private final EclipsePowers plugin;
    private final PowerManager manager;
    private final Map<UUID, Roll> rolls = new HashMap<>();

    public Roller(EclipsePowers plugin, PowerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean isRolling(Player p) {
        return rolls.containsKey(p.getUniqueId());
    }

    /** Starts a roll. forced = a fixed result, or null for a random one. */
    public void start(Player p, Power forced) {
        if (isRolling(p)) {
            reopen(p);
            return;
        }
        Power target = forced != null ? forced : manager.pickRandom(p);
        Roll roll = new Roll(p.getUniqueId(), target);
        rolls.put(p.getUniqueId(), roll);

        roll.render(0);
        p.openInventory(roll.inventory);
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
        Fx.later(plugin, 14, () -> advance(roll));
    }

    /** Shows the roll window again (for example after the player closed it). */
    public void reopen(Player p) {
        Roll roll = rolls.get(p.getUniqueId());
        if (roll != null) p.openInventory(roll.inventory);
    }

    /** The player left mid-roll: give the rolled power right away so nothing is lost. */
    public void finishNow(Player p) {
        Roll roll = rolls.remove(p.getUniqueId());
        if (roll != null && !roll.assigned) {
            roll.assigned = true;
            manager.setPower(p, roll.target);
        }
    }

    private void advance(Roll roll) {
        if (rolls.get(roll.id) != roll) return;
        Player p = Bukkit.getPlayer(roll.id);
        if (p == null || !p.isOnline()) return;

        roll.step++;
        roll.render(roll.step);
        double f = roll.step / (double) STEPS;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, (float) (0.7 + 0.9 * f));
        Power centered = roll.seq[roll.step + CENTER];
        Fx.dustBurst(p.getLocation().add(0, 1, 0), centered.rgb(), 1.4f, 12, 0.7);

        if (roll.step >= STEPS) {
            reveal(roll, p);
            return;
        }
        long delay = Math.round(1 + 12 * f * f); // fast at first, slowing to a crawl
        Fx.later(plugin, delay, () -> advance(roll));
    }

    private void reveal(Roll roll, Player p) {
        roll.assigned = true;
        manager.setPower(p, roll.target);
        roll.renderReveal();
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);

        // let the result sit on screen for a couple of seconds, then close and celebrate in the world
        Fx.later(plugin, 50, () -> {
            rolls.remove(roll.id);
            Player pl = Bukkit.getPlayer(roll.id);
            if (pl == null || !pl.isOnline()) return;
            if (pl.getOpenInventory().getTopInventory() == roll.inventory) pl.closeInventory();
            manager.awaken(pl, roll.target);
        });
    }

    private final class Roll {
        final UUID id;
        final Power target;
        final Power[] seq = new Power[LEN];
        final Menus.Holder holder = new Menus.Holder(Menus.Type.ROLL);
        final Inventory inventory;
        int step = 0;
        boolean assigned = false;

        Roll(UUID id, Power target) {
            this.id = id;
            this.target = target;
            this.inventory = Bukkit.createInventory(holder, 45,
                    Component.text("Rolling your power...", NamedTextColor.GOLD, TextDecoration.BOLD));
            holder.bind(inventory);
            buildSequence();
            buildBorders();
        }

        private void buildSequence() {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            Power[] all = Power.values();
            for (int i = 0; i < LEN; i++) {
                Power pick;
                do {
                    pick = all[rnd.nextInt(all.length)];
                } while (i > 0 && pick == seq[i - 1]);
                seq[i] = pick;
            }
            // the strip stops with the target in the middle slot
            int stop = STEPS + CENTER;
            seq[stop] = target;
            for (int near : new int[]{stop - 1, stop + 1}) {
                while (seq[near] == target) seq[near] = all[rnd.nextInt(all.length)];
            }
        }

        private void buildBorders() {
            ItemStack black = Menus.item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), null, false);
            for (int i = 0; i < 9; i++) {
                inventory.setItem(i, black);
                inventory.setItem(36 + i, black);
            }
            inventory.setItem(4, Menus.item(Material.HOPPER, Component.text("Your power", NamedTextColor.GOLD),
                    List.of(Component.text("The roll lands here", NamedTextColor.GRAY)), false));
            inventory.setItem(40, Menus.item(Material.END_ROD, Component.text("Your power", NamedTextColor.GOLD),
                    List.of(Component.text("The roll lands here", NamedTextColor.GRAY)), false));
        }

        /** Shows the strip as it looks after `s` steps. Rows above and below show other powers drifting by. */
        void render(int s) {
            for (int j = 0; j < 9; j++) {
                inventory.setItem(18 + j, Menus.powerIcon(seq[s + j], j == CENTER));
                inventory.setItem(9 + j, Menus.pane(seq[(s + j + 17) % LEN]));
                inventory.setItem(27 + j, Menus.pane(seq[(s + j + 7) % LEN]));
            }
        }

        /** The landing frame: everything turns the color of the rolled power. */
        void renderReveal() {
            ItemStack pane = Menus.pane(target);
            for (int i = 0; i < 9; i++) {
                inventory.setItem(i, pane);
                inventory.setItem(9 + i, pane);
                inventory.setItem(18 + i, pane);
                inventory.setItem(27 + i, pane);
                inventory.setItem(36 + i, pane);
            }
            inventory.setItem(22, Menus.powerIcon(target, true));
            inventory.setItem(4, Menus.item(Material.NETHER_STAR,
                    Component.text("You rolled " + target.displayName() + "!", target.color(), TextDecoration.BOLD),
                    null, true));
        }
    }
}
