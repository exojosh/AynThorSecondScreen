package com.exojosh.client;

import com.exojosh.client.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Routes commands sent from the companion app to actual game actions.
 * Two command shapes:
 *
 *   1. A digit "1" through "9" -- selects that hotbar slot. Pressed through
 *      the matching vanilla hotbarKeys[] binding rather than mutating
 *      inventory state directly, so it takes the same code path a real
 *      keypress would: MinecraftClient.handleInputEvents already handles
 *      spectator mode, the creative save/load-toolbar modifiers, and
 *      whatever server sync the selected slot needs. Reimplementing that
 *      here would mean re-deriving all of it.
 *
 *   2. "BIND:&lt;id&gt;" -- presses the binding with that translation key
 *      ({@code BIND:key.inventory}), resolved through KeyBinding.byId. This is
 *      the form the configurable input grid uses: the app got the id from
 *      {@link KeyBindingCatalog}, so no table here has to know it, and other
 *      mods' bindings work with no entry anywhere.
 *
 *   3. Anything else -- looked up in COMMANDS, a fixed code -> KeyBinding map.
 *      Superseded by (2) and kept for compatibility with app builds that
 *      predate it; see the note on that map.
 *
 * <h2>Codes name actions, not keys</h2>
 * "SWAP", not "F". Naming codes after keyboard keys was a bug source and a
 * dead end: the table drifted from reality (an "R" code bound to swap-hands),
 * attack and use are mouse buttons with no letter to borrow, and a player
 * rebinding a key in-game invalidates every letter at once. The action is what
 * the two halves of this system actually agree on. Old letter codes are kept
 * as aliases so an app built before the rename keeps working.
 *
 * <h2>How a press is simulated</h2>
 * A KeyBinding exposes its state two ways, and different actions read
 * different ones:
 *
 *   - {@code isPressed()} -- is the key held right now. Used by continuous
 *     actions: attack, use, movement.
 *   - {@code wasPressed()} -- consumes a count of discrete presses. Used by
 *     nearly everything else, including hotbar selection, inventory, drop
 *     and swap-hands.
 *
 * This used to only call {@code setPressed(true)}, which feeds isPressed()
 * alone -- so every discrete action silently did nothing, hotbar taps
 * included. Both are now driven: the press counter is bumped (via
 * {@link KeyBindingAccessor}, since it's private) *and* the held flag is set
 * for exactly one tick.
 *
 * Timing: Fabric's END_CLIENT_TICK fires at the end of MinecraftClient.tick(),
 * after that tick's handleInputEvents() has already run. So a command
 * dispatched now is picked up on the *next* tick -- one tick of latency,
 * ~50ms, unnoticeable. {@link #tick()} must run once per client tick BEFORE
 * dispatch() for that tick, which puts the release after the game has had
 * its look at the held flag.
 */
public class CommandDispatcher {

    /** Presses a binding by its translation key, e.g. {@code BIND:key.drop}. */
    private static final String BINDING_PREFIX = "BIND:";

    private static final Map<String, KeyBinding> COMMANDS = new HashMap<>();
    private static final ConcurrentLinkedQueue<KeyBinding> PENDING_RELEASE = new ConcurrentLinkedQueue<>();
    private static boolean initialized = false;

    private static void init() {
        if (initialized) return;
        var options = MinecraftClient.getInstance().options;

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
        COMMANDS.put("INVENTORY", options.inventoryKey);
        COMMANDS.put("DROP", options.dropKey);
        COMMANDS.put("SWAP", options.swapHandsKey);
        COMMANDS.put("USE", options.useKey);
        COMMANDS.put("ATTACK", options.attackKey);
        COMMANDS.put("JUMP", options.jumpKey);
        COMMANDS.put("SNEAK", options.sneakKey);

        // Superseded single-letter codes, kept so a companion app built before
        // the rename still works against this mod. "R"/"G" are preserved with
        // the behaviour they actually had, not the behaviour their names imply
        // -- the point is not to break an installed app, not to bless the old
        // naming. Safe to delete once both halves are known to be updated.
        COMMANDS.put("E", options.inventoryKey);
        COMMANDS.put("R", options.swapHandsKey);
        COMMANDS.put("G", options.dropKey);
        COMMANDS.put("H", options.useKey);
        COMMANDS.put("K", options.attackKey);
        COMMANDS.put("F", options.swapHandsKey);

        initialized = true;
    }

    /**
     * Call once per client tick, BEFORE dispatch()-ing any new commands for
     * that tick, so anything pressed last tick gets released this tick.
     */
    public static void tick() {
        init();
        KeyBinding toRelease;
        while ((toRelease = PENDING_RELEASE.poll()) != null) {
            toRelease.setPressed(false);
        }
    }

    /** Routes a command to hotbar slot selection or a mapped KeyBinding. */
    public static void dispatch(String code) {
        init();

        MinecraftClient client = MinecraftClient.getInstance();

        // Vanilla only drains keybindings (handleInputEvents) while no screen
        // or overlay is up. Queuing presses anyway would let them pile up
        // unconsumed and then all fire at once the moment the player closes
        // their inventory -- ten taps of the drop key throwing ten stacks.
        // Dropping them is the honest behaviour: the game wouldn't have acted
        // on a real keypress at that moment either.
        if (client.currentScreen != null) {
            System.out.println("[ThorHud] Ignoring command '" + code + "' -- a screen is open");
            return;
        }

        Integer slot = parseHotbarSlot(code);
        if (slot != null) {
            pressForOneTick(client.options.hotbarKeys[slot - 1]);
            return;
        }

        if (code.startsWith(BINDING_PREFIX)) {
            String id = code.substring(BINDING_PREFIX.length());
            // byId covers every binding ever constructed, modded ones included,
            // so nothing has to be registered here for a mod's action to be
            // usable from the second screen.
            KeyBinding byId = KeyBinding.byId(id);
            if (byId == null) {
                // Reachable in normal use: the app persists ids, so removing
                // the mod that owned one leaves a button pointing at nothing.
                System.out.println("[ThorHud] No key binding with id: " + id);
                return;
            }
            pressForOneTick(byId);
            return;
        }

        KeyBinding binding = COMMANDS.get(code);
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

    private static void pressForOneTick(KeyBinding binding) {
        // Discrete actions (hotbar, inventory, drop, swap) read wasPressed(),
        // which drains this counter and ignores the held flag entirely.
        KeyBindingAccessor accessor = (KeyBindingAccessor) binding;
        accessor.thorhud$setTimesPressed(accessor.thorhud$getTimesPressed() + 1);

        // Continuous actions (attack, use) read isPressed() instead.
        binding.setPressed(true);
        PENDING_RELEASE.add(binding);
    }
}
