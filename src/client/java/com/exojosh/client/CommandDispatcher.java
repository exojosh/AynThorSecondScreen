package com.exojosh.client;

import com.exojosh.client.mixin.KeyBindingAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Routes commands sent from the companion app to actual game actions.
 *
 * Digit commands select a hotbar slot through the corresponding
 * {@link KeyMapping}; {@code BIND:<id>} resolves a current binding by its
 * translation key; and the named legacy codes are retained for compatibility
 * with older companion-app builds. Using the game's binding objects keeps
 * rebinding and mod-provided actions on the same path as normal input.
 *
 * A simulated press updates both pieces of {@link KeyMapping} state: the
 * private {@code clickCount} consumed by discrete actions and the held-state
 * flag used by continuous actions. The mixin accessor exposes only that
 * counter. The held state is released on the next client tick.
 */
public class CommandDispatcher {

    /** Presses a binding by its translation key, e.g. {@code BIND:key.drop}. */
    private static final String BINDING_PREFIX = "BIND:";

    private static final Map<String, KeyMapping> COMMANDS = new HashMap<>();
    private static final ConcurrentLinkedQueue<KeyMapping> PENDING_RELEASE = new ConcurrentLinkedQueue<>();
    private static boolean initialized = false;

    private static void init() {
        if (initialized) return;
        var options = Minecraft.getInstance().options;

        // Superseded by BIND:<id>, which needs no table at all. Kept because
        // an app build predating the change still sends these, and because the
        // one thing they give that ids don't is a stable name for an action
        // whose binding id could move between game versions.
        //
        // Codes name the *action*, not a keyboard key.
        //
        // They used to name keys, and got it wrong: "R" was bound to swap-hands
        // (vanilla's F) and "G" to drop (vanilla's Q), so the app's grid showed
        // an R button that behaved like F. Naming keys can't work anyway --
        // attack and use are mouse buttons with no letter to borrow, and any
        // player who rebinds a key in-game desynchronises the whole table.
        // The action is the thing both sides actually agree on.
        //
        // Codes are matched literally and are never a single digit, so they
        // can't collide with the "1".."9" hotbar codes parsed ahead of them.
        COMMANDS.put("INVENTORY", options.keyInventory);
        COMMANDS.put("DROP", options.keyDrop);
        COMMANDS.put("SWAP", options.keySwapOffhand);
        COMMANDS.put("USE", options.keyUse);
        COMMANDS.put("ATTACK", options.keyAttack);
        COMMANDS.put("JUMP", options.keyJump);
        COMMANDS.put("SNEAK", options.keyShift);

        // Superseded single-letter codes, kept so a companion app built before
        // the rename still works against this mod. "R"/"G" are preserved with
        // the behaviour they actually had, not the behaviour their names imply
        // -- the point is not to break an installed app, not to bless the old
        // naming. Safe to delete once both halves are known to be updated.
        COMMANDS.put("E", options.keyInventory);
        COMMANDS.put("R", options.keySwapOffhand);
        COMMANDS.put("G", options.keyDrop);
        COMMANDS.put("H", options.keyUse);
        COMMANDS.put("K", options.keyAttack);
        COMMANDS.put("F", options.keySwapOffhand);

        initialized = true;
    }

    /**
     * Call once per client tick, BEFORE dispatch()-ing any new commands for
     * that tick, so anything pressed last tick gets released this tick.
     */
    public static void tick() {
        init();
        KeyMapping toRelease;
        while ((toRelease = PENDING_RELEASE.poll()) != null) {
            toRelease.setDown(false);
        }
    }

    /** Routes a command to hotbar slot selection or a mapped KeyMapping. */
    public static void dispatch(String code) {
        init();

        Minecraft client = Minecraft.getInstance();

        // Vanilla only drains keybindings (handleInputEvents) while no screen
        // or overlay is up. Queuing presses anyway would let them pile up
        // unconsumed and then all fire at once the moment the player closes
        // their inventory -- ten taps of the drop key throwing ten stacks.
        // Dropping them is the honest behaviour: the game wouldn't have acted
        // on a real keypress at that moment either.
        if (client.gui.screen() != null) {
            System.out.println("[ThorHud] Ignoring command '" + code + "' -- a screen is open");
            return;
        }

        Integer slot = parseHotbarSlot(code);
        if (slot != null) {
            pressForOneTick(client.options.keyHotbarSlots[slot - 1]);
            return;
        }

        if (code.startsWith(BINDING_PREFIX)) {
            String id = code.substring(BINDING_PREFIX.length());
            // byId covers every binding ever constructed, modded ones included,
            // so nothing has to be registered here for a mod's action to be
            // usable from the second screen.
            KeyMapping byId = KeyMapping.get(id);
            if (byId == null) {
                // Reachable in normal use: the app persists ids, so removing
                // the mod that owned one leaves a button pointing at nothing.
                System.out.println("[ThorHud] No key binding with id: " + id);
                return;
            }
            pressForOneTick(byId);
            return;
        }

        KeyMapping binding = COMMANDS.get(code);
        if (binding == null) {
            System.out.println("[ThorHud] Unknown command code: " + code);
            return;
        }
        pressForOneTick(binding);
    }

    private static Integer parseHotbarSlot(String code) {
        if (code.length() != 1) return null;
        char c = code.charAt(0);
        if (c < '1' || c > '9') return null;
        return c - '0';
    }

    private static void pressForOneTick(KeyMapping binding) {
        // Discrete actions (hotbar, inventory, drop, swap) consume clickCount,
        // which drains this counter and ignores the held flag entirely.
        KeyBindingAccessor accessor = (KeyBindingAccessor) binding;
        accessor.thorhud$setTimesPressed(accessor.thorhud$getTimesPressed() + 1);

        // Continuous actions (attack, use) read the held state instead.
        binding.setDown(true);
        PENDING_RELEASE.add(binding);
    }
}
