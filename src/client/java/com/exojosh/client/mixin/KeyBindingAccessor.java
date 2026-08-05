package com.exojosh.client.mixin;

import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code KeyBinding.timesPressed}, the private counter behind
 * {@link KeyBinding#wasPressed()}.
 *
 * <h2>Why this is needed</h2>
 * A KeyBinding tracks two independent things:
 *
 *   - {@code pressed}: is the key held down right now, read by isPressed().
 *     Public setter, {@link KeyBinding#setPressed(boolean)}.
 *   - {@code timesPressed}: how many discrete presses happened since anyone
 *     last looked, consumed by wasPressed(). Private, and only incremented by
 *     the static {@code onKeyPressed(InputUtil.Key)} hook that real keyboard
 *     input goes through.
 *
 * Nearly every discrete action in vanilla reads wasPressed(), not isPressed()
 * -- hotbar slot selection, opening the inventory, dropping, swapping hands.
 * So calling setPressed(true) alone (which is what this mod used to do)
 * simulates nothing at all for those: the flag gets set, nothing reads it,
 * and the action never fires.
 *
 * The static onKeyPressed() route would work, but it needs the binding's
 * bound key (also private, no getter) and fires *every* binding sharing that
 * key -- plus it does nothing at all when the key is unbound, since all
 * unbound bindings collide on UNKNOWN_KEY. Bumping the counter on one
 * specific binding is both narrower and works regardless of what the player
 * has bound, which matters here because these presses originate from a
 * second screen, not a keyboard.
 */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {

    @Accessor("timesPressed")
    int thorhud$getTimesPressed();

    @Accessor("timesPressed")
    void thorhud$setTimesPressed(int timesPressed);
}
