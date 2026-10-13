package com.exojosh.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses or shows vanilla HUD elements based on companion app toggles via Forge's RenderGuiOverlayEvent.Pre.
 */
public final class GameHudVisibilityForge {

    public enum Element {
        ARMOR("armor", VanillaGuiOverlay.ARMOR_LEVEL.id()),
        BUBBLES("bubbles", VanillaGuiOverlay.AIR_LEVEL.id()),
        HEARTS("hearts", VanillaGuiOverlay.PLAYER_HEALTH.id()),
        HUNGER("hunger", VanillaGuiOverlay.FOOD_LEVEL.id()),
        XP_BAR("xp_bar", VanillaGuiOverlay.EXPERIENCE_BAR.id()),
        HOTBAR("hotbar", VanillaGuiOverlay.HOTBAR.id());

        private final String key;
        private final ResourceLocation[] vanillaIds;

        Element(String key, ResourceLocation... vanillaIds) {
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

    private static final Set<Element> SHOWN_IN_GAME = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() == null) return;
        ResourceLocation overlayId = event.getOverlay().id();

        for (Element element : Element.values()) {
            for (ResourceLocation id : element.vanillaIds) {
                if (id.equals(overlayId)) {
                    if (!SHOWN_IN_GAME.contains(element)) {
                        event.setCanceled(true);
                    }
                    return;
                }
            }
        }
    }

    public static void setShownInGame(String payload) {
        Set<Element> next = EnumSet.noneOf(Element.class);

        for (String rawKey : payload.split(",")) {
            String key = rawKey.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;

            Element element = Element.byKey(key);
            if (element == null) {
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
