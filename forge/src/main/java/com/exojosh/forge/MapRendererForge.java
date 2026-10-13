package com.exojosh.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Optional;

/**
 * Renders a top-down minimap tile around the player as a PNG for Forge 1.20.1.
 */
public final class MapRendererForge {

    public static final int SIZE = 128;
    private static final int HALF = SIZE / 2;
    private static final int BLOCKS_PER_PIXEL = 1;

    private MapRendererForge() {}

    public record Tile(
            String base64Png,
            int originX,
            int originZ,
            double playerX,
            double playerZ,
            float yaw,
            int size
    ) {}

    public static Optional<Tile> render(Minecraft client) {
        Level world = client.level;
        Player player = client.player;
        if (world == null || player == null) {
            return Optional.empty();
        }

        int centerX = Mth.floor(player.getX());
        int centerZ = Mth.floor(player.getZ());
        int originX = centerX - HALF;
        int originZ = centerZ - HALF;

        boolean hasCeiling = world.dimensionType().hasCeiling();
        int bottomY = world.getMinBuildHeight();
        int topY = world.getMaxBuildHeight() - 1;
        int ceilingStartY = Mth.clamp(Mth.floor(player.getY()) + 1, bottomY + 1, topY);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        LevelChunk chunk = null;
        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;

        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);

        for (int px = 0; px < SIZE; px++) {
            int worldX = originX + px;
            double previousHeight = 0.0;

            for (int pz = -1; pz < SIZE; pz++) {
                int worldZ = originZ + pz;

                int chunkX = SectionPos.blockToSectionCoord(worldX);
                int chunkZ = SectionPos.blockToSectionCoord(worldZ);
                if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
                    chunk = world.getChunk(chunkX, chunkZ);
                    cachedChunkX = chunkX;
                    cachedChunkZ = chunkZ;
                }

                if (chunk == null || chunk.isEmpty()) {
                    if (pz >= 0) {
                        image.setRGB(px, pz, 0);
                    }
                    previousHeight = 0.0;
                    continue;
                }

                int startY = hasCeiling
                        ? ceilingStartY
                        : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) + 1;

                pos.set(worldX, startY, worldZ);
                BlockState state;
                int waterDepth = 0;
                int y = startY;

                if (y <= bottomY) {
                    state = Blocks.BEDROCK.defaultBlockState();
                } else {
                    do {
                        pos.setY(--y);
                        state = chunk.getBlockState(pos);
                    } while (state.getMapColor(world, pos) == MapColor.NONE && y > bottomY);

                    if (y > bottomY && !state.getFluidState().isEmpty()) {
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
                    int rgb = mapColor.calculateRGBColor(brightness);
                    int argb = (0xFF << 24) | (rgb & 0xFFFFFF);
                    image.setRGB(px, pz, argb);
                }

                previousHeight = height;
            }
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            String base64Png = Base64.getEncoder().encodeToString(out.toByteArray());

            return Optional.of(new Tile(
                    base64Png,
                    originX,
                    originZ,
                    player.getX(),
                    player.getZ(),
                    player.getYRot(),
                    SIZE
            ));
        } catch (Exception e) {
            System.out.println("[ThorHud] Map render failed: " + e);
            return Optional.empty();
        }
    }

    private static MapColor.Brightness brightnessFor(
            MapColor mapColor, double height, double previousHeight, int waterDepth, int worldX, int worldZ) {
        int checker = Math.floorMod(worldX + worldZ, 2);

        if (mapColor == MapColor.WATER) {
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

    private static BlockState fluidStateIfVisible(Level world, BlockState state, BlockPos pos) {
        FluidState fluidState = state.getFluidState();
        return !fluidState.isEmpty() && !state.isFaceSturdy(world, pos, Direction.UP)
                ? fluidState.createLegacyBlock()
                : state;
    }
}
