package com.exojosh.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Resolves an item icon for the companion app.
 *
 * Minecraft 26.2 moved GUI item rendering to the extracted-state/feature
 * renderer. The old pre-26.2 off-screen implementation depended on the removed
 * command-queue and framebuffer APIs, so retaining it would prevent the mod
 * from loading at all. The companion protocol already has a resource-manager
 * icon fallback; use that stable path here until the new feature renderer can
 * be integrated without a version-specific framebuffer mixin.
 *
 * The callback remains asynchronous in the API contract used by the client
 * tick, even though this compatibility implementation completes immediately.
 */
public final class ItemIconRenderer {

    /** Size used by the companion app when it scales a source icon. */
    public static final int ICON_SIZE = 64;

    private ItemIconRenderer() {
    }

    /**
     * Resolves the item's resource-pack texture and invokes {@code onComplete}
     * exactly once with PNG bytes encoded as base64, or {@code null} when no
     * simple item/block texture exists.
     */
    public static void renderBase64Png(ItemStack stack, Consumer<String> onComplete) {
        if (stack == null || stack.isEmpty()) {
            onComplete.accept(null);
            return;
        }

        try {
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            onComplete.accept(ItemIconResolver.resolveBase64Png(itemId).orElse(null));
        } catch (RuntimeException e) {
            System.out.println("[ThorHud] Icon resolve failed for " + stack + ": " + e.getMessage());
            onComplete.accept(null);
        }
    }
}
