package com.exojosh.client;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain data snapshot of the HUD elements we hid. Gson turns this straight
 * into JSON -- keep it a flat, boring record so the wire format stays simple
 * for whatever renders it on the second screen.
 */
public record HudState(
        float health,
        float maxHealth,
        int armor,
        int food,
        int xpLevel,
        float xpProgress,
        int selectedSlot,
        List<HotbarSlot> hotbar
) {
    /**
     * damage/maxDamage are both 0 for non-damageable items (blocks, most
     * misc items) -- the companion app should treat that as "no durability
     * bar to draw," not "fully worn." hasGlint mirrors vanilla's enchantment
     * shimmer, true for enchanted items AND a few unenchanted-but-glinting
     * ones like written books.
     *
     * NOTE: isDamageable()/getDamage()/getMaxDamage()/hasGlint() are my best
     * recollection of the Yarn 1.21.11 method names -- verify with
     * Ctrl+Space on an ItemStack instance before trusting these compile,
     * same as every other vanilla API guess this session.
     */
    public record HotbarSlot(
            String itemId,
            int count,
            int damage,
            int maxDamage,
            boolean hasGlint
    ) {}

    public static List<HotbarSlot> hotbarFromInventory(PlayerEntity player) {
        List<HotbarSlot> slots = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                slots.add(new HotbarSlot("minecraft:air", 0, 0, 0, false));
            } else {
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                boolean damageable = stack.isDamageable();
                int damage = damageable ? stack.getDamage() : 0;
                int maxDamage = damageable ? stack.getMaxDamage() : 0;
                boolean hasGlint = stack.hasGlint();
                slots.add(new HotbarSlot(id, stack.getCount(), damage, maxDamage, hasGlint));
            }
        }
        return slots;
    }
}
