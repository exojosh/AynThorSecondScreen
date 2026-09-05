package com.exojosh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;

/**
 * Reads an item's texture PNG straight from Minecraft's own resource
 * manager, rather than us re-parsing resource pack zips/folders ourselves.
 * The resource manager already resolves "check active packs top-down, fall
 * back to vanilla" internally, so this gets that behavior for free and
 * always matches whatever pack the player currently has active.
 *
 * Tries textures/item/<path>.png first, then falls back to
 * textures/block/<path>.png -- most simple blocks (dirt, stone, planks,
 * etc.) don't have a separate item-folder texture at all; their item icon
 * just points at the block texture directly. Without this fallback, every
 * plain block in the hotbar comes back with no icon.
 *
 * First-pass limitation: this reads the raw base texture file, not a fully
 * composited rendered icon. Items with tinted/layered rendering (dyed
 * leather armor, potions, spawn eggs) or blocks whose item icon isn't a
 * simple single texture (e.g. some 3D-modeled blocks) won't be fully
 * accurate -- getting the exact rendered pixels would mean rendering
 * through the real GUI render pipeline and reading back a framebuffer,
 * meaningfully more code because Minecraft 26.2 moved GUI item rendering to
 * extracted render state and feature dispatch. Fine starting point for the
 * common cases; revisit if specific items still show blank or wrong.
 */
public class ItemIconResolver {

    public static Optional<String> resolveBase64Png(String itemId) {
        Identifier item = Identifier.parse(itemId);

        Optional<String> fromItemFolder = tryTexturePath(item, "item");
        if (fromItemFolder.isPresent()) return fromItemFolder;

        return tryTexturePath(item, "block");
    }

    private static Optional<String> tryTexturePath(Identifier item, String folder) {
        try {
            Identifier texture = Identifier.fromNamespaceAndPath(item.getNamespace(), "textures/" + folder + "/" + item.getPath() + ".png");

            var resourceOpt = Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resourceOpt.isEmpty()) {
                return Optional.empty();
            }

            try (InputStream in = resourceOpt.get().open()) {
                byte[] bytes = in.readAllBytes();
                return Optional.of(Base64.getEncoder().encodeToString(bytes));
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[ThorHud] Failed to resolve icon (" + folder + ") for "
                    + item + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
