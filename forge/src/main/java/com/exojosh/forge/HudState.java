package com.exojosh.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain data snapshot of the HUD elements for Forge 1.20.1.
 */
public record HudState(
        float health,
        float maxHealth,
        float absorption,
        int armor,
        int food,
        int xpLevel,
        float xpProgress,
        int selectedSlot,
        int air,
        int maxAir,
        String gameMode,
        String heartType,
        boolean hardcore,
        List<HotbarSlot> hotbar,
        HotbarSlot offhand
) {
    public record HotbarSlot(
            String itemId,
            int count,
            int damage,
            int maxDamage,
            boolean hasGlint
    ) {}

    public static String heartTypeOf(Player player) {
        if (player.hasEffect(MobEffects.POISON)) return "POISONED";
        if (player.hasEffect(MobEffects.WITHER)) return "WITHERED";
        if (player.isFullyFrozen()) return "FROZEN";
        return "NORMAL";
    }

    public static String gameModeOf(Minecraft client) {
        if (client.gameMode == null) return null;
        return client.gameMode.getPlayerMode().name();
    }

    public static boolean isHardcore(Player player) {
        return player.level().getLevelData().isHardcore();
    }

    public static List<HotbarSlot> hotbarFromInventory(Player player) {
        List<HotbarSlot> slots = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            slots.add(slotFrom(player.getInventory().getItem(i)));
        }
        return slots;
    }

    public static HotbarSlot offhandFrom(Player player) {
        return slotFrom(player.getOffhandItem());
    }

    public static HotbarSlot slotFrom(ItemStack stack) {
        if (stack.isEmpty()) {
            return new HotbarSlot("minecraft:air", 0, 0, 0, false);
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean damageable = stack.isDamageableItem();
        int damage = damageable ? stack.getDamageValue() : 0;
        int maxDamage = damageable ? stack.getMaxDamage() : 0;
        return new HotbarSlot(id, stack.getCount(), damage, maxDamage, stack.hasFoil());
    }
}
