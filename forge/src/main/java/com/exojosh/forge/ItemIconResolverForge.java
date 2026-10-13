package com.exojosh.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;

/**
 * Resolves item icons out of Minecraft's ResourceManager for Forge 1.20.1.
 */
public class ItemIconResolverForge {

    public static Optional<String> resolveBase64Png(String itemId) {
        ResourceLocation item = new ResourceLocation(itemId);

        Optional<String> fromItemFolder = tryTexturePath(item, "item");
        if (fromItemFolder.isPresent()) return fromItemFolder;

        return tryTexturePath(item, "block");
    }

    private static Optional<String> tryTexturePath(ResourceLocation item, String folder) {
        try {
            ResourceLocation texture = new ResourceLocation(item.getNamespace(), "textures/" + folder + "/" + item.getPath() + ".png");

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
