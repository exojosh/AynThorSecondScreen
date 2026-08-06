package com.exojosh.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The set of textures the companion app needs to draw the HUD, resolved out
 * of Minecraft's own resource manager and shipped over the socket.
 *
 * <h2>Why the mod serves these</h2>
 * The app used to require the user to hand-copy extracted game textures into
 * its assets folder. That was tedious, meant a fresh install rendered
 * placeholder shapes until someone did it, and put non-redistributable Mojang
 * assets in the app's repo. Serving them from here fixes all three: the
 * resource manager already resolves "search active packs top-down, fall back
 * to vanilla" internally, so the app gets whatever the player actually sees,
 * including resource pack overrides, with no pack-parsing code of its own.
 *
 * <h2>Keys vs. paths</h2>
 * The wire format uses short stable keys ("heart_full"), not resource paths.
 * Mojang moves these files around between versions -- the 1.21.2 sprite split
 * moved and renamed most of them, and the naming is inconsistent even now
 * (hearts are nested under heart/, but armor and food are flat with an
 * underscore). Keeping the mapping here means a version bump is a one-file
 * change on this side and the app never needs to know.
 *
 * Paths confirmed against the sprite identifiers in InGameHud/ExperienceBar
 * for 1.21.11: a GUI sprite "hud/heart/full" lives at
 * textures/gui/sprites/hud/heart/full.png.
 */
public final class HudAssetCatalog {

    private static final Map<String, String> ASSETS = new LinkedHashMap<>();

    static {
        // Hearts are nested under heart/ ...
        ASSETS.put("heart_full", "textures/gui/sprites/hud/heart/full.png");
        ASSETS.put("heart_half", "textures/gui/sprites/hud/heart/half.png");
        ASSETS.put("heart_container", "textures/gui/sprites/hud/heart/container.png");
        // Absorption ("golden") hearts, drawn in slots past the normal ones.
        ASSETS.put("heart_absorbing_full", "textures/gui/sprites/hud/heart/absorbing_full.png");
        ASSETS.put("heart_absorbing_half", "textures/gui/sprites/hud/heart/absorbing_half.png");
        // ... but armor and food are flat with an underscore. Not a typo.
        ASSETS.put("armor_full", "textures/gui/sprites/hud/armor_full.png");
        ASSETS.put("armor_half", "textures/gui/sprites/hud/armor_half.png");
        ASSETS.put("armor_empty", "textures/gui/sprites/hud/armor_empty.png");
        ASSETS.put("food_full", "textures/gui/sprites/hud/food_full.png");
        ASSETS.put("food_half", "textures/gui/sprites/hud/food_half.png");
        ASSETS.put("food_empty", "textures/gui/sprites/hud/food_empty.png");
        ASSETS.put("air", "textures/gui/sprites/hud/air.png");
        ASSETS.put("air_bursting", "textures/gui/sprites/hud/air_bursting.png");
        ASSETS.put("xp_bar_background", "textures/gui/sprites/hud/experience_bar_background.png");
        ASSETS.put("xp_bar_progress", "textures/gui/sprites/hud/experience_bar_progress.png");
        ASSETS.put("hotbar", "textures/gui/sprites/hud/hotbar.png");
        ASSETS.put("hotbar_selection", "textures/gui/sprites/hud/hotbar_selection.png");
        // The off-hand box. Vanilla ships a left and a right variant and picks
        // by the player's main arm; only the right one is served, because the
        // app puts the off-hand slot under the player's thumb at the right edge
        // of the screen rather than beside the hotbar (see HotbarRow).
        ASSETS.put("hotbar_offhand_right", "textures/gui/sprites/hud/hotbar_offhand_right.png");
        // The enchantment shimmer. Served rather than composited into the icon
        // because an icon is a still PNG and vanilla's glint scrolls -- see the
        // note on ItemIconRenderer.flushItemCommands. 128x128, and vanilla
        // samples it with linear filtering (its .mcmeta sets blur), unlike every
        // other texture here.
        ASSETS.put("enchanted_glint_item", "textures/misc/enchanted_glint_item.png");
        // Not a HUD sprite: the bitmap font sheet the app draws text from,
        // the block texture it tiles as a background, and the paper sheet the
        // live map is drawn on -- the last two live outside textures/gui/ but
        // resolve through the same resource manager, resource packs included.
        ASSETS.put("font_ascii", "textures/font/ascii.png");
        ASSETS.put("background", "textures/block/dirt.png");
        ASSETS.put("map_background", "textures/map/map_background.png");
    }

    private HudAssetCatalog() {
    }

    /** Every key this catalog knows about, in a stable order. */
    public static Iterable<String> keys() {
        return ASSETS.keySet();
    }

    /**
     * Reads one asset's PNG bytes as base64, or empty if the active resource
     * pack stack doesn't provide it (a pack can legitimately drop a sprite,
     * and Mojang may rename one out from under this table on a version bump).
     */
    public static Optional<String> resolveBase64Png(String key) {
        String path = ASSETS.get(key);
        if (path == null) return Optional.empty();

        try {
            Identifier texture = Identifier.ofVanilla(path);
            var resourceOpt = MinecraftClient.getInstance().getResourceManager().getResource(texture);
            if (resourceOpt.isEmpty()) {
                System.out.println("[ThorHud] No resource for HUD asset " + key + " (" + path + ")");
                return Optional.empty();
            }

            try (InputStream in = resourceOpt.get().getInputStream()) {
                return Optional.of(Base64.getEncoder().encodeToString(in.readAllBytes()));
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[ThorHud] Failed to read HUD asset " + key + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
