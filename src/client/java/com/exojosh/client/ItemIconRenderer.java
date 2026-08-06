package com.exojosh.client;

import com.exojosh.client.mixin.NativeImageInvoker;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.ProjectionMatrix2;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.joml.Matrix4fStack;

import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Base64;
import java.util.function.Consumer;

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

    /** COPY_SRC (readback) | TEXTURE_BINDING | RENDER_ATTACHMENT. */
    private static final int COLOR_USAGE =
            GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

    /** Depth only ever gets rendered into, never read back or sampled. */
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT;

    /** Matches ScreenshotRecorder's readback buffer usage (MAP_READ | COPY_DST). */
    private static final int READBACK_BUFFER_USAGE = 9;

    // Reused across icons -- these are GPU allocations, we don't want one per
    // request. Safe to reuse even with async readback in flight: GL orders
    // copyTextureToBuffer against the draws that preceded it, so a later
    // icon's draws can't corrupt an earlier icon's already-issued read.
    private static GpuTexture colorTexture;
    private static GpuTextureView colorView;
    private static GpuTexture depthTexture;
    private static GpuTextureView depthView;
    private static ProjectionMatrix2 projection;
    private static BufferAllocator bufferAllocator;
    private static VertexConsumerProvider.Immediate vertexConsumers;

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
        MinecraftClient client = MinecraftClient.getInstance();

        ItemRenderState renderState = new ItemRenderState();
        client.getItemModelManager().clearAndUpdate(
                renderState, stack, ItemDisplayContext.GUI, client.world, client.player, 0);

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
        colorTexture = device.createTexture("ThorHud icon", COLOR_USAGE, TextureFormat.RGBA8, ICON_SIZE, ICON_SIZE, 1, 1);
        colorView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture("ThorHud icon depth", DEPTH_USAGE, TextureFormat.DEPTH32, ICON_SIZE, ICON_SIZE, 1, 1);
        depthView = device.createTextureView(depthTexture);

        // Same near/far as GuiRenderer's "items" projection.
        projection = new ProjectionMatrix2("thorhud-icon", -1000.0F, 1000.0F, true);

        // Same size GuiRenderer gives its own item pass; the allocator would
        // grow on demand anyway, this just avoids reallocating on first use.
        bufferAllocator = new BufferAllocator(786432);
        vertexConsumers = VertexConsumerProvider.immediate(bufferAllocator);
    }

    private static void drawToOffscreenTexture(ItemRenderState renderState) {
        MinecraftClient client = MinecraftClient.getInstance();
        GpuDevice device = RenderSystem.getDevice();

        // Transparent background + cleared depth, otherwise we'd composite
        // onto whatever the previous icon left behind.
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);

        RenderSystem.backupProjectionMatrix();
        GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();

        try {
            RenderSystem.outputColorTextureOverride = colorView;
            RenderSystem.outputDepthTextureOverride = depthView;
            RenderSystem.setProjectionMatrix(projection.set(ICON_SIZE, ICON_SIZE), ProjectionType.ORTHOGRAPHIC);
            modelView.identity();

            // Flat-shade sprite-style items, 3D-light actual block models --
            // this is what makes a block read as isometric rather than as a
            // uniformly-lit silhouette.
            client.gameRenderer.getDiffuseLighting().setShaderLights(
                    renderState.isSideLit() ? DiffuseLighting.Type.ITEMS_3D : DiffuseLighting.Type.ITEMS_FLAT);

            // Centre the item and scale the model's [-0.5, 0.5] unit cube up
            // to fill the texture. Y is negated because the ortho projection
            // is Y-down. Mirrors GuiRenderer.prepareItemInitially.
            MatrixStack matrices = new MatrixStack();
            matrices.translate(ICON_SIZE / 2.0F, ICON_SIZE / 2.0F, 0.0F);
            matrices.scale(ICON_SIZE, -ICON_SIZE, ICON_SIZE);

            OrderedRenderCommandQueueImpl queue = new OrderedRenderCommandQueueImpl();
            renderState.render(matrices, queue, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, 0);
            flushItemCommands(queue);
        } finally {
            modelView.popMatrix();
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
            RenderSystem.restoreProjectionMatrix();
        }
    }

    /**
     * ItemRenderState.render() only queues commands; this turns them into
     * actual geometry. Equivalent to vanilla's ItemCommandRenderer minus the
     * entity-outline pass, which GUI items never use.
     *
     * <h2>The glint is deliberately dropped here</h2>
     * {@code command.glintType()} is replaced with {@link ItemRenderState.Glint#NONE},
     * so an enchanted item renders as the plain item and the companion app draws
     * the glint over it.
     *
     * Passing the real glint type through was an actual bug, not a missing
     * nicety: an enchanted golden apple came back as a flat square of the glint
     * texture with no apple in it. {@code ItemRenderer.renderItem} answers a
     * glint by unioning the item's own layer with {@code RenderLayers.glint()},
     * so the same quads are drawn twice -- and that second layer is an *additive*
     * pipeline whose UVs come from a texture matrix vanilla rebuilds per frame
     * from the wall clock ({@code TextureTransform.GLINT_TEXTURING}). Offscreen,
     * outside vanilla's frame setup, that pass covers the item instead of
     * shimmering across it.
     *
     * Even if it composited correctly it would be the wrong answer here: the
     * result is a still PNG, so vanilla's scrolling shimmer would be frozen.
     * Drawing it app-side gets the animation back and masks it to the item's own
     * alpha, which is what makes a sword glint along the blade rather than
     * lighting up the whole square. The app needs only the {@code hasGlint} flag
     * the snapshot already carries, plus the glint texture, which
     * {@link HudAssetCatalog} now serves.
     */
    private static void flushItemCommands(OrderedRenderCommandQueueImpl queue) {
        MatrixStack matrices = new MatrixStack();

        for (BatchingRenderCommandQueue batch : queue.getBatchingQueues().values()) {
            for (OrderedRenderCommandQueueImpl.ItemCommand command : batch.getItemCommands()) {
                matrices.push();
                matrices.peek().copy(command.positionMatrix());
                ItemRenderer.renderItem(
                        command.displayContext(),
                        matrices,
                        vertexConsumers,
                        command.lightCoords(),
                        command.overlayCoords(),
                        command.tintLayers(),
                        command.quads(),
                        command.renderLayer(),
                        ItemRenderState.Glint.NONE
                );
                matrices.pop();
            }
        }

        // Draws while the output override is still pointed at our texture.
        vertexConsumers.draw();
    }

    private static void readBackAsync(Consumer<String> onComplete) {
        int pixelSize = colorTexture.getFormat().pixelSize();
        long size = (long) ICON_SIZE * ICON_SIZE * pixelSize;

        GpuDevice device = RenderSystem.getDevice();
        GpuBuffer readback = device.createBuffer(() -> "ThorHud icon readback", READBACK_BUFFER_USAGE, size);
        CommandEncoder encoder = device.createCommandEncoder();

        encoder.copyTextureToBuffer(colorTexture, readback, 0L, () -> {
            String base64 = null;
            try (GpuBuffer.MappedView mapped = encoder.mapBuffer(readback, true, false)) {
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

    private static String encodeBase64Png(GpuBuffer.MappedView mapped, int pixelSize) throws Exception {
        // NativeImage's setColor takes little-endian RGBA (ABGR when read as
        // an int), which is exactly the layout an RGBA8 texture reads back
        // as -- so the ints go straight across. Row order is flipped because
        // GL reads bottom-up. Alpha is preserved (unlike ScreenshotRecorder,
        // which forces it opaque) so the icon stays cut-out on the app side.
        try (NativeImage image = new NativeImage(ICON_SIZE, ICON_SIZE, false)) {
            for (int y = 0; y < ICON_SIZE; y++) {
                for (int x = 0; x < ICON_SIZE; x++) {
                    int abgr = mapped.data().getInt((x + y * ICON_SIZE) * pixelSize);
                    image.setColor(x, ICON_SIZE - y - 1, abgr);
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
