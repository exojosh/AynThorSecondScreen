package com.exojosh.client;

import com.exojosh.client.mixin.NativeImageInvoker;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector4f;

/**
 * Renders an item/block into a private offscreen GPU texture using the real
 * vanilla model pipeline, then reads it back as PNG bytes.
 *
 * <h2>Why this exists</h2>
 * The old approach (ItemIconResolver) just read textures/item/&lt;name&gt;.png
 * off the resource manager. That only works for items whose icon happens to
 * BE a single texture file named after the item. It structurally cannot work
 * for:
 *   - blocks with no matching texture file at all (jungle_stairs has no
 *     textures/block/jungle_stairs.png -- its model references jungle_planks)
 *   - blocks whose texture name differs from the item name
 *     (stripped_birch_wood renders from stripped_birch_log)
 *   - blocks with different textures per face (spruce logs: log top vs. side)
 *   - anything composited, tinted, or 3D-modelled
 * Those are exactly the items that were coming back blank. And even when it
 * did resolve, the result was a flat 16x16 face, not vanilla's isometric
 * block view.
 *
 * <h2>How it works</h2>
 * Confirmed against decompiled 1.21.11 (./gradlew genClientOnlySources): this
 * is the same sequence GuiRenderer.prepareItemElements uses to fill its "UI
 * items atlas", just pointed at our own texture instead:
 *
 *   1. ItemModelManager.clearAndUpdate(...) with ItemDisplayContext.GUI bakes
 *      the item's real model into an ItemRenderState -- including vanilla's
 *      GUI display transform, which IS the isometric 30/225 rotation.
 *   2. RenderSystem.outputColorTextureOverride / outputDepthTextureOverride
 *      redirect all subsequent RenderLayer draws to our texture. This is a
 *      public hook (RenderLayer.draw honours it; WorldRenderer and GuiRenderer
 *      both use it) -- no framebuffer mixin needed. This is the piece the
 *      earlier investigation missed when it concluded GuiRenderer's hardwired
 *      main-framebuffer flush was a dead end.
 *   3. ItemRenderState.render() records draw commands into a command queue;
 *      we drain that queue through ItemRenderer.renderItem() into our own
 *      VertexConsumerProvider.Immediate, exactly as ItemCommandRenderer does.
 *   4. CommandEncoder.copyTextureToBuffer() pulls the pixels back, same
 *      pattern as vanilla's ScreenshotRecorder. That readback is fenced and
 *      therefore asynchronous, hence the callback.
 *
 * Nothing is ever drawn to the real window framebuffer, so there is no
 * on-screen flash.
 *
 * <h2>Threading / timing</h2>
 * Must be called on the render thread. The client tick runs on the render
 * thread and no render pass is open then, which is what copyTextureToBuffer
 * requires, so calling this from END_CLIENT_TICK is fine. The completion
 * callback also fires on the render thread, once the GPU fence signals
 * (RenderSystem.executePendingTasks drives that each frame).
 */
public final class ItemIconRenderer {

    /**
     * Vanilla renders GUI items at 16 * guiScale. We're rendering for a
     * second screen where hotbar slots are much larger than 16px, so go
     * straight to 64 -- still a tiny PNG, but no upscaling mush on the app.
     */
    public static final int ICON_SIZE = 64;

    /** Vanilla's full-brightness packed lightmap value for GUI items. */
    private static final int FULL_BRIGHT = 15728880;

    /**
     * COPY_SRC (readback) | COPY_DST (26.x requires it for the clear) |
     * TEXTURE_BINDING | RENDER_ATTACHMENT. Vanilla's GuiItemAtlas uses the same
     * set minus COPY_SRC, since it never reads its atlas back.
     */
    private static final int COLOR_USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

    /** Same as vanilla's GuiItemAtlas depth texture (RENDER_ATTACHMENT | COPY_DST). */
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;

    /** Matches Screenshot's readback buffer usage (MAP_READ | COPY_DST). */
    private static final int READBACK_BUFFER_USAGE = 9;

    private static final Vector4f TRANSPARENT = new Vector4f(0.0F);

    // Reused across icons -- these are GPU allocations, we don't want one per
    // request. Safe to reuse even with async readback in flight: the GPU orders
    // copyTextureToBuffer against the draws that preceded it.
    private static GpuTexture colorTexture;
    private static GpuTextureView colorView;
    private static GpuTexture depthTexture;
    private static GpuTextureView depthView;
    private static final Projection projection = new Projection();
    private static ProjectionMatrixBuffer projectionBuffer;
    private static final SubmitNodeStorage submitNodes = new SubmitNodeStorage();

    private ItemIconRenderer() {
    }

    /**
     * Renders {@code stack} and hands the caller base64-encoded PNG bytes.
     *
     * @param onComplete invoked on the render thread with the base64 PNG, or
     *                   with {@code null} if the item has no renderable model
     *                   or the render/readback failed. Always invoked exactly
     *                   once, so callers can rely on it to answer a pending
     *                   request either way rather than silently dropping it.
     */
    public static void renderBase64Png(ItemStack stack, Consumer<String> onComplete) {
        Minecraft client = Minecraft.getInstance();

        // The glint is deliberately dropped (see CLAUDE.md): the app animates
        // it itself from the snapshot's hasGlint flag. In 26.x the glint is
        // drawn by the shared feature dispatcher, so rather than filtering it
        // out of the draw we render a copy that has none.
        ItemStack plain = stack.copy();
        plain.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);

        ItemStackRenderState renderState = new ItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(
                renderState, plain, ItemDisplayContext.GUI, client.level, client.player, 0);

        if (renderState.isEmpty()) {
            onComplete.accept(null);
            return;
        }

        try {
            ensureResources();
            drawToOffscreenTexture(renderState);
            readBackAsync(onComplete);
        } catch (RuntimeException e) {
            System.out.println("[ThorHud] Icon render failed for " + stack + ": " + e);
            e.printStackTrace();
            onComplete.accept(null);
        }
    }

    private static void ensureResources() {
        if (colorTexture != null) return;

        GpuDevice device = RenderSystem.getDevice();
        colorTexture = device.createTexture("ThorHud icon", COLOR_USAGE, GpuFormat.RGBA8_UNORM, ICON_SIZE, ICON_SIZE, 1, 1);
        colorView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture("ThorHud icon depth", DEPTH_USAGE, GpuFormat.D32_FLOAT, ICON_SIZE, ICON_SIZE, 1, 1);
        depthView = device.createTextureView(depthTexture);
        projectionBuffer = new ProjectionMatrixBuffer("thorhud-icon");
    }

    /**
     * The same sequence vanilla's GuiItemAtlas.drawToSlot uses in 26.3, pointed
     * at our own 64x64 texture: submit the item into a node storage, let the
     * game's FeatureRenderDispatcher turn that into draws, and run them in a
     * render pass whose attachments are our textures. Nothing touches the
     * window framebuffer.
     */
    private static void drawToOffscreenTexture(ItemStackRenderState renderState) {
        Minecraft client = Minecraft.getInstance();
        GpuDevice device = RenderSystem.getDevice();

        // Transparent background + cleared depth (26.x uses reversed depth,
        // so "far" is 0.0, matching GuiItemAtlas).
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, TRANSPARENT, depthTexture, 0.0);

        RenderSystem.backupProjectionMatrix();
        try {
            projection.setupOrtho(-1000.0F, 1000.0F, ICON_SIZE, ICON_SIZE, true);
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

            // Flat-shade sprite-style items, 3D-light actual block models --
            // this is what makes a block read as isometric.
            client.gameRenderer.lighting().setupFor(
                    renderState.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);

            // Centre the item and scale the model's [-0.5, 0.5] unit cube up
            // to fill the texture. Y is negated because the ortho projection
            // is Y-down.
            PoseStack matrices = new PoseStack();
            matrices.translate(ICON_SIZE / 2.0F, ICON_SIZE / 2.0F, 0.0F);
            matrices.scale(ICON_SIZE, -ICON_SIZE, ICON_SIZE);
            renderState.submit(matrices, submitNodes, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);

            FeatureRenderDispatcher dispatcher = client.gameRenderer.featureRenderDispatcher();
            try (
                    FeatureRenderDispatcher.PreparedFrame frame = dispatcher.prepareFrame(submitNodes);
                    RenderPass pass = device.createCommandEncoder().createRenderPass(
                            () -> "ThorHud item icon", colorView, Optional.empty(), depthView, OptionalDouble.empty())
            ) {
                RenderSystem.bindDefaultUniforms(pass);
                FeatureRenderDispatcher.renderAllFeatures(pass, frame);
            }
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static void readBackAsync(Consumer<String> onComplete) {
        int pixelSize = colorTexture.getFormat().blockSize();
        long size = (long) ICON_SIZE * ICON_SIZE * pixelSize;

        GpuDevice device = RenderSystem.getDevice();
        GpuBuffer readback = device.createBuffer(() -> "ThorHud icon readback", READBACK_BUFFER_USAGE, size);

        device.createCommandEncoder().copyTextureToBuffer(colorTexture, readback, 0L, () -> {
            String base64 = null;
            try (GpuBufferSlice.MappedView mapped = readback.map(true, false)) {
                base64 = encodeBase64Png(mapped, pixelSize);
            } catch (Exception e) {
                System.out.println("[ThorHud] Icon readback failed: " + e);
                e.printStackTrace();
            } finally {
                readback.close();
                onComplete.accept(base64);
            }
        }, 0);
    }

    private static String encodeBase64Png(GpuBufferSlice.MappedView mapped, int pixelSize) throws Exception {
        // NativeImage's setColor takes little-endian RGBA (ABGR when read as
        // an int), which is exactly the layout an RGBA8 texture reads back
        // as -- so the ints go straight across. Row order is flipped because
        // GL reads bottom-up. Alpha is preserved (unlike ScreenshotRecorder,
        // which forces it opaque) so the icon stays cut-out on the app side.
        try (NativeImage image = new NativeImage(ICON_SIZE, ICON_SIZE, false)) {
            for (int y = 0; y < ICON_SIZE; y++) {
                for (int x = 0; x < ICON_SIZE; x++) {
                    int abgr = mapped.data().getInt((x + y * ICON_SIZE) * pixelSize);
                    image.setPixelABGR(x, ICON_SIZE - y - 1, abgr);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (WritableByteChannel channel = Channels.newChannel(out)) {
                if (!((NativeImageInvoker) (Object) image).thorhud$write(channel)) {
                    return null;
                }
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }
}
