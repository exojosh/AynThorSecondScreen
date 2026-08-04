package com.exojosh.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Routes commands sent from the companion app to actual game actions.
 * Two command shapes:
 *
 *   1. A digit "1" through "9" -- selects that hotbar slot. Simulated the
 *      same way as everything else here: press the matching vanilla
 *      hotbarKeys[] binding for one tick rather than mutating inventory
 *      state directly, so it goes through the same code path a real
 *      keypress would (respecting whatever vanilla/other mods do on that
 *      input, rather than us reimplementing slot-selection semantics).
 *
 *   2. Anything else -- looked up in COMMANDS, a code -> KeyBinding map you
 *      define. The vanilla bindings below are just a starting set; add
 *      whatever your input grid actually needs, including other mods' own
 *      KeyBinding instances if you want to trigger those.
 *
 * Both cases press-then-schedule-release: tick() must run once per client
 * tick BEFORE dispatch() is called for that tick, so a press always lasts
 * exactly one tick regardless of which of the two paths triggered it.
 *
 * NOTE: hotbarKeys, isDamageable()-style method names, etc. are my best
 * recollection of Yarn 1.21.11 -- verify with Ctrl+Space before trusting
 * any of this compiles as-is, same caveat as everywhere else this session.
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

        Integer slot = parseHotbarSlot(code);
        if (slot != null) {
            pressForOneTick(MinecraftClient.getInstance().options.hotbarKeys[slot - 1]);
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
        binding.setPressed(true);
        PENDING_RELEASE.add(binding);
    }
}
