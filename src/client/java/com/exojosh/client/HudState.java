package com.exojosh.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain data snapshot of the HUD elements we hid. Gson turns this straight
 * into JSON -- keep it a flat, boring record so the wire format stays simple
 * for whatever renders it on the second screen.
 *
 * air/maxAir drive the breathing bubbles. Both are sent raw (air is in
 * ticks, maxAir is 300 for an unenchanted player but Respiration and status
 * effects change it) rather than pre-reduced to a bubble count, so the
 * companion app can apply vanilla's own rounding and we don't bake a HUD
 * decision into the wire format. Vanilla hides the bubbles entirely unless
 * air &lt; maxAir; the app makes that call.
 */
public record HudState(
        float health,
        float maxHealth,
        /** Extra "golden heart" health from absorption effects, on top of
         *  health/maxHealth. Zero most of the time. */
        float absorption,
        int armor,
        int food,
        int xpLevel,
        float xpProgress,
        int selectedSlot,
        int air,
        int maxAir,
        /**
         * The game mode's own name ({@code SURVIVAL}, {@code CREATIVE},
         * {@code ADVENTURE}, {@code SPECTATOR}), or null against a world that
         * hasn't reported one yet.
         *
         * Sent raw rather than reduced to "should the status bars show" for the
         * same reason air is: the HUD decision belongs to whatever is drawing
         * the HUD. Vanilla's own rule is {@code interactionManager.hasStatusBars()},
         * i.e. survival or adventure.
         */
        String gameMode,
        /**
         * Which set of heart sprites to draw, from
         * {@code InGameHud.HeartType.fromPlayerState}: NORMAL, POISONED,
         * WITHERED or FROZEN, in that precedence.
         */
        String heartType,
        /** Hardcore worlds use a different rim on every heart sprite. */
        boolean hardcore,
        List<HotbarSlot> hotbar,
        /** The off-hand stack, in the same shape as a hotbar slot. Always
         *  present -- an empty off-hand is sent as a minecraft:air slot rather
         *  than omitted, because the app draws the box either way (vanilla
         *  hides it when empty; the second screen keeps it as a tap target for
         *  swapping into). */
        HotbarSlot offhand
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

    /**
     * Which heart sprite set applies, following
     * {@code InGameHud.HeartType.fromPlayerState} — including its precedence,
     * which is poison over wither over freezing rather than any combination.
     */
    public static String heartTypeOf(PlayerEntity player) {
        if (player.hasStatusEffect(StatusEffects.POISON)) return "POISONED";
        if (player.hasStatusEffect(StatusEffects.WITHER)) return "WITHERED";
        if (player.isFrozen()) return "FROZEN";
        return "NORMAL";
    }

    /** The current game mode's name, or null before one is known. */
    public static String gameModeOf(MinecraftClient client) {
        if (client.interactionManager == null) return null;
        return client.interactionManager.getCurrentGameMode().name();
    }

    /** Whether this is a hardcore world, which changes every heart sprite. */
    public static boolean isHardcore(PlayerEntity player) {
        return player.getEntityWorld().getLevelProperties().isHardcore();
    }

    public static List<HotbarSlot> hotbarFromInventory(PlayerEntity player) {
        List<HotbarSlot> slots = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            slots.add(slotFrom(player.getInventory().getStack(i)));
        }
        return slots;
    }

    /**
     * The off-hand stack, described exactly like a hotbar slot so the app can
     * render it with the same code path -- it's the same 16x16 icon with the
     * same count/durability/glint decorations, just in a box of its own.
     */
    public static HotbarSlot offhandFrom(PlayerEntity player) {
        return slotFrom(player.getOffHandStack());
    }

    /**
     * One stack in the shape the app renders. Public because
     * {@link ContainerRelay} describes inventory slots with exactly the same
     * record — same 16x16 icon, same count/durability/glint decorations — so
     * the app draws both with one code path.
     */
    public static HotbarSlot slotFrom(ItemStack stack) {
        if (stack.isEmpty()) {
            return new HotbarSlot("minecraft:air", 0, 0, 0, false);
        }
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        boolean damageable = stack.isDamageable();
        int damage = damageable ? stack.getDamage() : 0;
        int maxDamage = damageable ? stack.getMaxDamage() : 0;
        return new HotbarSlot(id, stack.getCount(), damage, maxDamage, stack.hasGlint());
    }
}
