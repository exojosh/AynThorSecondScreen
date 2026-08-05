package com.exojosh.client;

import com.exojosh.client.mixin.NativeImageInvoker;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Base64;
import java.util.Optional;

/**
 * Renders a top-down map tile around the player as a PNG.
 *
 * <h2>Why it looks like vanilla</h2>
 * The colouring is vanilla's own, lifted from {@code FilledMapItem.updateColors}
 * (1.21.11 decompiled source, not recollection): take the surface block of each
 * column, ask it for its {@link MapColor}, then pick one of four brightness
 * levels from how much the column's height changed against its neighbour. That
 * height-delta shading is the entire reason vanilla maps read as terrain rather
 * than as flat colour blobs, and it's cheap -- it falls out of numbers we're
 * already computing.
 *
 * <h2>Why it's cheap enough to run live</h2>
 * The expensive-looking part -- finding the surface -- isn't a downward scan
 * from the sky. {@code sampleHeightmap(WORLD_SURFACE, ...)} is a value the
 * chunk already maintains, so it's one lookup per column and the scan only
 * steps down past map-invisible blocks (glass, air pockets) from there.
 *
 * <h2>Deliberate deviations from vanilla, and why</h2>
 * <ul>
 *   <li><b>Ceiling dimensions (the Nether) scan down from the player</b>
 *       instead of from the heightmap. {@code WORLD_SURFACE} hits the bedrock
 *       roof everywhere down there, which is exactly why vanilla gives up and
 *       fills Nether maps with a meaningless dirt/stone noise pattern. A live
 *       minimap that's useless in the Nether isn't worth carrying, so this
 *       scans from just above the player's head and shows the layer they're
 *       actually standing in.</li>
 *   <li><b>The checkerboard dither uses world coordinates</b>, not tile pixel
 *       coordinates. Vanilla can use pixel coordinates because a filled map is
 *       pinned to the world; this tile re-centres on the player every update,
 *       so pixel-indexed dithering would crawl and shimmer as they walk.</li>
 *   <li><b>One block per pixel</b> (vanilla's scale 0). No sub-sampling loop,
 *       so vanilla's {@code i} is 1 throughout and its {@code /(i*i)} averaging
 *       collapses away.</li>
 * </ul>
 */
public final class MapRenderer {

    /** Tile edge in pixels, and (at 1 block per pixel) in blocks. */
    public static final int SIZE = 128;

    private static final int HALF = SIZE / 2;

    /** Vanilla's scale-0 block-per-pixel factor. Kept named for the shading maths below. */
    private static final int BLOCKS_PER_PIXEL = 1;

    private MapRenderer() {
    }

    /**
     * One rendered tile plus everything the companion app needs to place it.
     *
     * The world origin is sent explicitly rather than being derived from the
     * player position, so the app doesn't have to reimplement this method's
     * rounding to know which block the top-left pixel is.
     */
    public record Tile(
            String base64Png,
            int originX,
            int originZ,
            double playerX,
            double playerZ,
            float yaw,
            int size
    ) {
    }

    /**
     * Renders the tile centred on the player, or empty if there's no world/player
     * yet or the PNG encode failed.
     *
     * <p>Must run on the client thread: it reads world and chunk state.
     */
    public static Optional<Tile> render(MinecraftClient client) {
        World world = client.world;
        PlayerEntity player = client.player;
        if (world == null || player == null) {
            return Optional.empty();
        }

        int centerX = MathHelper.floor(player.getX());
        int centerZ = MathHelper.floor(player.getZ());
        int originX = centerX - HALF;
        int originZ = centerZ - HALF;

        boolean hasCeiling = world.getDimension().hasCeiling();
        int bottomY = world.getBottomY();
        int ceilingStartY = MathHelper.clamp(
                MathHelper.floor(player.getY()) + 1, bottomY + 1, world.getTopYInclusive());

        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable probe = new BlockPos.Mutable();

        // One chunk lookup per 16 columns instead of per column.
        WorldChunk chunk = null;
        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;

        try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
            for (int px = 0; px < SIZE; px++) {
                int worldX = originX + px;

                // Vanilla's `d`: the previous column's height in the scan
                // direction, which is what the shading compares against. The
                // loop starts one row early so the first drawn row already has
                // a real neighbour to compare to rather than shading off zero.
                double previousHeight = 0.0;

                for (int pz = -1; pz < SIZE; pz++) {
                    int worldZ = originZ + pz;

                    int chunkX = ChunkSectionPos.getSectionCoord(worldX);
                    int chunkZ = ChunkSectionPos.getSectionCoord(worldZ);
                    if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
                        chunk = world.getChunk(chunkX, chunkZ);
                        cachedChunkX = chunkX;
                        cachedChunkZ = chunkZ;
                    }

                    if (chunk == null || chunk.isEmpty()) {
                        // Not loaded yet. Leave it transparent -- the app draws
                        // nothing there rather than inventing terrain.
                        if (pz >= 0) {
                            image.setColorArgb(px, pz, 0);
                        }
                        previousHeight = 0.0;
                        continue;
                    }

                    int startY = hasCeiling
                            ? ceilingStartY
                            : chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, worldX, worldZ) + 1;

                    pos.set(worldX, startY, worldZ);

                    BlockState state;
                    int waterDepth = 0;
                    int y = startY;

                    if (y <= bottomY) {
                        state = Blocks.BEDROCK.getDefaultState();
                    } else {
                        // Step down past anything that doesn't show on a map
                        // (air, glass, barriers) to the first block that does.
                        do {
                            pos.setY(--y);
                            state = chunk.getBlockState(pos);
                        } while (state.getMapColor(world, pos) == MapColor.CLEAR && y > bottomY);

                        if (y > bottomY && !state.getFluidState().isEmpty()) {
                            // Keep descending through the fluid to measure how
                            // deep it is; vanilla shades water by depth rather
                            // than by height delta, which is what gives coasts
                            // their gradient.
                            int probeY = y - 1;
                            probe.set(pos);
                            BlockState below;
                            do {
                                probe.setY(probeY--);
                                below = chunk.getBlockState(probe);
                                waterDepth++;
                            } while (probeY > bottomY && !below.getFluidState().isEmpty());

                            state = fluidStateIfVisible(world, state, pos);
                        }
                    }

                    double height = y;
                    MapColor mapColor = state.getMapColor(world, pos);

                    if (pz >= 0) {
                        MapColor.Brightness brightness =
                                brightnessFor(mapColor, height, previousHeight, waterDepth, worldX, worldZ);
                        image.setColorArgb(px, pz, mapColor.getRenderColor(brightness));
                    }

                    previousHeight = height;
                }
            }

            return Optional.of(new Tile(
                    encodeBase64Png(image),
                    originX,
                    originZ,
                    player.getX(),
                    player.getZ(),
                    player.getYaw(),
                    SIZE
            ));
        } catch (Exception e) {
            System.out.println("[ThorHud] Map render failed: " + e);
            return Optional.empty();
        }
    }

    /**
     * Vanilla's brightness pick, thresholds and all (FilledMapItem lines 149-168).
     *
     * The magic numbers are vanilla's, not tuned here -- changing them is what
     * makes a map stop looking like a Minecraft map.
     */
    private static MapColor.Brightness brightnessFor(
            MapColor mapColor, double height, double previousHeight, int waterDepth, int worldX, int worldZ) {

        // floorMod, not `&`, because world coordinates go negative and the
        // checkerboard has to stay stable across the origin.
        int checker = Math.floorMod(worldX + worldZ, 2);

        if (mapColor == MapColor.WATER_BLUE) {
            double shade = waterDepth * 0.1 + checker * 0.2;
            if (shade < 0.5) {
                return MapColor.Brightness.HIGH;
            }
            return shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        }

        double shade = (height - previousHeight) * 4.0 / (BLOCKS_PER_PIXEL + 4) + (checker - 0.5) * 0.4;
        if (shade > 0.6) {
            return MapColor.Brightness.HIGH;
        }
        return shade < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }

    /** FilledMapItem.getFluidStateIfVisible: ice and lily pads hide the water under them. */
    private static BlockState fluidStateIfVisible(World world, BlockState state, BlockPos pos) {
        FluidState fluidState = state.getFluidState();
        return !fluidState.isEmpty() && !state.isSideSolidFullSquare(world, pos, Direction.UP)
                ? fluidState.getBlockState()
                : state;
    }

    private static String encodeBase64Png(NativeImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (WritableByteChannel channel = Channels.newChannel(out)) {
            if (!((NativeImageInvoker) (Object) image).thorhud$write(channel)) {
                throw new IllegalStateException("NativeImage.write returned false");
            }
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
