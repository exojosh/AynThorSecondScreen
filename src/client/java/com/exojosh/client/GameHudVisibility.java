package com.exojosh.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.util.Identifier;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, per element, whether the game draws its own HUD on the main screen.
 *
 * <h2>Why this exists</h2>
 * The companion app lets the player switch individual HUD elements off on the
 * second screen. Switching one off shouldn't simply lose it -- it should go
 * back to where it came from. So "hidden on the bottom screen" is sent here and
 * becomes "drawn by the game on the top screen", and the two displays between
 * them always show the full HUD exactly once.
 *
 * <h2>Why replaceElement, not removeElement</h2>
 * This used to be a list of {@code HudElementRegistry.removeElement(...)} calls
 * at startup, which is a one-way door: the vanilla element's renderer belongs
 * to vanilla and we hold no reference to it, so having dropped it there's
 * nothing left to put back. {@code replaceElement} hands us the original and
 * takes our replacement, so each element becomes a wrapper that decides per
 * frame whether to delegate. With nothing shown, that is behaviourally
 * identical to the old removals -- the wrapper just draws nothing.
 *
 * <h2>Threading</h2>
 * The wrappers run on the render thread; {@link #setShownInGame(String)} runs
 * from the client tick while draining socket commands. Same thread in practice,
 * but the set is concurrent rather than relying on that.
 */
public final class GameHudVisibility {

    /**
     * A HUD element the player can move between screens.
     *
     * {@link #key} is the wire name and must match {@code HudElement.key} in the
     * companion app's {@code settings/HudSettings.kt}. The app owns the setting;
     * this side only ever receives it.
     *
     * Note the app also has an "offhand" element with no entry here: vanilla
     * draws the off-hand box as part of the hotbar, so there's no separate
     * vanilla element to switch back on. Unknown keys are ignored for exactly
     * this reason.
     */
    public enum Element {
        ARMOR("armor", VanillaHudElements.ARMOR_BAR),
        BUBBLES("bubbles", VanillaHudElements.AIR_BAR),
        HEARTS("hearts", VanillaHudElements.HEALTH_BAR),
        HUNGER("hunger", VanillaHudElements.FOOD_BAR),
        // Vanilla splits this in two: the bar itself lives on the info bar, the
        // number above it is its own element. They're one thing to the player.
        XP_BAR("xp_bar", VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR),
        HOTBAR("hotbar", VanillaHudElements.HOTBAR);

        private final String key;
        private final Identifier[] vanillaIds;

        Element(String key, Identifier... vanillaIds) {
            this.key = key;
            this.vanillaIds = vanillaIds;
        }

        static Element byKey(String key) {
            for (Element element : values()) {
                if (element.key.equals(key)) return element;
            }
            return null;
        }
    }

    /** Elements the game should draw itself. Empty by default: on a fresh
     *  install the app shows everything, so the game shows nothing. */
    private static final Set<Element> SHOWN_IN_GAME = ConcurrentHashMap.newKeySet();

    private GameHudVisibility() {
    }

    /** Wraps every switchable vanilla element. Call once, from client init. */
    public static void install() {
        for (Element element : Element.values()) {
            for (Identifier id : element.vanillaIds) {
                HudElementRegistry.replaceElement(id, original -> (context, tickCounter) -> {
                    if (SHOWN_IN_GAME.contains(element)) {
                        original.render(context, tickCounter);
                    }
                });
            }
        }
    }

    /**
     * Applies a full visibility message: a comma-separated list of the keys the
     * game should draw, i.e. the ones the player switched *off* on the second
     * screen. An empty payload means "draw nothing", which is the default.
     *
     * Full state rather than per-element deltas on purpose. The app re-sends
     * this on every change and on every reconnect, so a dropped or reordered
     * message can't leave the two screens disagreeing about who owns an
     * element -- which is the failure that would show up as a HUD element
     * drawn twice, or not at all.
     */
    public static void setShownInGame(String payload) {
        Set<Element> next = EnumSet.noneOf(Element.class);

        for (String rawKey : payload.split(",")) {
            String key = rawKey.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;

            Element element = Element.byKey(key);
            if (element == null) {
                // "offhand" lands here by design; anything else is a genuine
                // mismatch between the two halves and worth seeing in the log.
                System.out.println("[ThorHud] No vanilla HUD element for '" + key + "'");
                continue;
            }
            next.add(element);
        }

        if (SHOWN_IN_GAME.equals(next)) return;

        SHOWN_IN_GAME.clear();
        SHOWN_IN_GAME.addAll(next);
        System.out.println("[ThorHud] Game HUD now draws: "
                + (next.isEmpty() ? "(nothing)" : Arrays.toString(next.toArray())));
    }
}
