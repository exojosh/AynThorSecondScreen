package com.exojosh.client.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code KeyMapping.clickCount}, the private counter behind
 * {@link KeyMapping#consumeClick()}.
 *
 * <h2>Why this is needed</h2>
 * A KeyMapping tracks two independent things:
 *
 *   - {@code isDown}: whether the key is held down right now, controlled by
 *     {@link KeyMapping#setDown(boolean)}.
 *   - {@code clickCount}: how many discrete presses happened since anyone
 *     last looked, consumed by {@link KeyMapping#consumeClick()}. It is
 *     private and normally incremented by real keyboard input.
 *
 * Nearly every discrete action in vanilla consumes clickCount, not isDown
 * -- hotbar slot selection, opening the inventory, dropping, swapping hands.
 * So calling setDown(true) alone (which is what this mod used to do)
 * simulates nothing at all for those: the flag gets set, nothing reads it,
 * and the action never fires.
 *
 * The real keyboard-input route would work, but it needs the binding's
 * bound key and fires *every* binding sharing that
 * key -- plus it does nothing at all when the key is unbound, since all
 * unbound bindings collide on UNKNOWN_KEY. Bumping the counter on one
 * specific binding is both narrower and works regardless of what the player
 * has bound, which matters here because these presses originate from a
 * second screen, not a keyboard.
 */
@Mixin(KeyMapping.class)
public interface KeyBindingAccessor {

    @Accessor("clickCount")
    int thorhud$getTimesPressed();

    @Accessor("clickCount")
    void thorhud$setTimesPressed(int clickCount);
}
