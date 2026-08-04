package com.exojosh.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

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
 * First-pass limitation: this reads the raw base item texture file, not a
 * fully-composited rendered icon. Items with tinted/layered rendering
 * (leather armor's dye color, potions, spawn eggs, etc.) won't reflect
 * those overlays -- getting the exact rendered pixels would mean rendering
 * through ItemRenderer into an offscreen framebuffer and reading it back,
 * meaningfully more code. Fine starting point for the common single-layer
 * case; revisit if the flat-texture look bothers you for tinted items.
 *
 * NOTE: Identifier.of(), getResourceManager(), Resource.getInputStream() are
 * my best recollection of the Yarn 1.21.11 API shape -- verify with
 * Ctrl+Space, same caveat as everywhere else this session.
 */
public class ItemIconResolver {

    public static Optional<String> resolveBase64Png(String itemId) {
        try {
            Identifier item = Identifier.of(itemId);
            Identifier texture = Identifier.of(item.getNamespace(), "textures/item/" + item.getPath() + ".png");

            var resourceOpt = MinecraftClient.getInstance().getResourceManager().getResource(texture);
            if (resourceOpt.isEmpty()) {
                return Optional.empty(); // no item texture at this path -- e.g. some block-item edge cases
            }

            try (InputStream in = resourceOpt.get().getInputStream()) {
                byte[] bytes = in.readAllBytes();
                return Optional.of(Base64.getEncoder().encodeToString(bytes));
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[ThorHud] Failed to resolve icon for " + itemId + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
