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
 *   2. Anything else -- looked up in COMMANDS, a code -> KeyBinding map you
 *      define. The vanilla bindings below are just a starting set; add
 *      whatever your input grid actually needs, including other mods' own
 *      KeyBinding instances if you want to trigger those.
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

    private static final Map<String, KeyBinding> COMMANDS = new HashMap<>();
    private static final ConcurrentLinkedQueue<KeyBinding> PENDING_RELEASE = new ConcurrentLinkedQueue<>();
    private static boolean initialized = false;

    private static void init() {
        if (initialized) return;
        var options = MinecraftClient.getInstance().options;

        // Example vanilla bindings -- swap/add whatever the grid buttons
        // should actually send. Command codes are whatever short string the
        // companion app sends over the socket (e.g. "E").
        COMMANDS.put("E", options.inventoryKey);
        COMMANDS.put("R", options.swapHandsKey);
        COMMANDS.put("G", options.dropKey);
        COMMANDS.put("H", options.useKey);
        COMMANDS.put("K", options.attackKey);

        // Sent by the off-hand slot on the second screen. Named for the key
        // vanilla actually binds swap-hands to, unlike the "R"/"G" pair above,
        // which are mislabelled (see the input-codes bugfix in TODO.md) -- when
        // that gets straightened out, this entry is the one to keep.
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
