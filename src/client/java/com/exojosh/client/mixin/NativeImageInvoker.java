package com.exojosh.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/**
 * NativeImage can encode itself to PNG (via STB), but only exposes that
 * through writeTo(Path) -- the underlying write(WritableByteChannel) is
 * private. We want PNG bytes in memory to base64 onto the socket, not a
 * temp file round-trip, so this invoker opens up the channel variant.
 *
 * NativeImage is final; cast through Object at the call site:
 *   ((NativeImageInvoker) (Object) image).thorhud$write(channel)
 */
@Mixin(NativeImage.class)
public interface NativeImageInvoker {

    @Invoker("writeToChannel")
    boolean thorhud$write(WritableByteChannel channel) throws IOException;
}
