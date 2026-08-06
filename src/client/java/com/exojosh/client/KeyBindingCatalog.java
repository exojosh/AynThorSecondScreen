package com.exojosh.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every key binding the game knows about, as data the companion app can put in
 * front of the player.
 *
 * <h2>Why this exists</h2>
 * The app's input grid used to send a fixed set of codes that
 * {@link CommandDispatcher} matched against a hardcoded table. Two
 * hand-maintained lists in two repos describing the same thing, kept in step by
 * remembering to edit both -- which is exactly how the grid ended up with an
 * "R" button that did an F key's job, and an "L" button wired to nothing at
 * all. Sending the real bindings makes the mod the single source of truth: the
 * app renders what it is told and sends back an id it was given, so there is
 * nothing left to drift.
 *
 * <h2>Where the list comes from</h2>
 * {@code GameOptions.allKeys}, which is public and already includes other mods'
 * bindings -- Fabric's key-binding API appends registered bindings into that
 * same array. The obvious alternative, an {@code @Accessor} on KeyBinding's
 * private static {@code KEYS_BY_ID} map, would need a mixin to reach the same
 * set, so it isn't used.
 *
 * <h2>What each field is for</h2>
 * {@link Entry#id()} is the binding's translation key ({@code key.inventory}),
 * and it is the **wire identity**: the app stores it and sends it back as
 * {@code BIND:<id>}, which {@link CommandDispatcher} resolves through
 * {@code KeyBinding.byId}. Everything else is for display only, and is resolved
 * here rather than in the app because only this side has the language files:
 * {@code label} is the action's own translated name, {@code boundKey} is
 * whatever key the player currently has it on, and {@code category} groups the
 * picker the way the game's own controls screen groups it.
 *
 * <h2>Freshness</h2>
 * This is a snapshot, not a live view. A player rebinding a key mid-session
 * leaves the app showing the old key name until it re-requests the list
 * ({@code BINDINGS}), which it does whenever the picker is opened. The
 * binding *ids* never go stale, so the buttons keep working regardless -- only
 * the printed key name can lag.
 */
public final class KeyBindingCatalog {

    private KeyBindingCatalog() {
    }

    /**
     * One binding. {@code id} is the contract; the rest is display text.
     *
     * {@code unbound} is sent so the app can mark a button whose key the player
     * has cleared in game. It stays usable -- the second screen presses the
     * binding directly rather than synthesising its key, so an unbound action
     * still fires -- but it's worth showing, because that button is now the
     * *only* way to trigger it.
     */
    public record Entry(
            String id,
            String label,
            String category,
            String boundKey,
            boolean unbound
    ) {
    }

    /** The current bindings, sorted by category then label, as the picker shows them. */
    public static List<Entry> snapshot() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null) return List.of();

        List<Entry> entries = new ArrayList<>();
        for (KeyBinding binding : client.options.allKeys) {
            if (binding == null) continue;
            entries.add(new Entry(
                    binding.getId(),
                    // Vanilla's own controls screen renders the binding name
                    // this way; the id doubles as its translation key.
                    Text.translatable(binding.getId()).getString(),
                    binding.getCategory().getLabel().getString(),
                    binding.getBoundKeyLocalizedText().getString(),
                    binding.isUnbound()
            ));
        }

        entries.sort(Comparator.comparing(Entry::category).thenComparing(Entry::label));
        return entries;
    }
}
