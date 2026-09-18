package io.papermc.paper.block;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluids;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Grass/mycelium random ticks already hold their chunk; the light read now reuses it instead of a
 * second chunk-table lookup. These tests pin the two halves: the held-chunk light read composes the
 * same sky/block/darkening arithmetic as the lookup path, and the block calls it with that chunk.
 */
@VanillaFeature
class GrassLightReuseTest {
    /** A light-correct FULL chunk whose sky and block nibbles read {@code sky} and {@code block} everywhere. */
    private static ChunkAccess chunkWith(int sky, int block, boolean lightCorrect) {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.isLightCorrect()).thenReturn(lightCorrect);
        when(chunk.getPersistedStatus()).thenReturn(ChunkStatus.FULL);
        // world == null in the interface below: light sections -5..20, so 26 nibbles per layer.
        SWMRNibbleArray[] skies = new SWMRNibbleArray[26];
        SWMRNibbleArray[] blocks = new SWMRNibbleArray[26];
        for (int i = 0; i < 26; i++) {
            skies[i] = filled(sky);
            blocks[i] = filled(block);
        }
        when(chunk.starlight$getSkyNibbles()).thenReturn(skies);
        when(chunk.starlight$getBlockNibbles()).thenReturn(blocks);
        return chunk;
    }

    private static SWMRNibbleArray filled(int value) {
        byte[] bytes = new byte[SWMRNibbleArray.ARRAY_SIZE];
        java.util.Arrays.fill(bytes, (byte) ((value << 4) | value));
        return new SWMRNibbleArray(bytes);
    }

    @Test
    void heldChunkReadComposesSkyBlockAndDarkeningExactlyLikeTheLookupPath() {
        StarLightInterface light = new StarLightInterface(null, true, true, null);
        BlockPos pos = new BlockPos(-17, 64, 33);
        for (int sky = 0; sky <= 15; sky++) {
            for (int block = 0; block <= 15; block++) {
                ChunkAccess chunk = chunkWith(sky, block, true);
                assertEquals(sky, light.getSkyLightValue(pos, chunk));
                assertEquals(block, light.getBlockLightValue(pos, chunk));
                for (int dark = 0; dark <= 15; dark++) {
                    int skyPart = sky - dark;
                    int expected = skyPart == 15 ? 15 : Math.max(skyPart, block);
                    assertEquals(expected, light.getRawBrightness(pos, dark, chunk), "sky=" + sky + " block=" + block + " dark=" + dark);
                }
            }
        }
        // A chunk whose light is not yet correct goes through the same sky/block readers as the lookup
        // path (with a null world the interface counts as client-side, so the nibbles are still read).
        ChunkAccess uncorrected = chunkWith(3, 7, false);
        for (int dark = 0; dark <= 15; dark++) {
            int skyPart = light.getSkyLightValue(pos, uncorrected) - dark;
            int expected = skyPart == 15 ? 15 : Math.max(skyPart, light.getBlockLightValue(pos, uncorrected));
            assertEquals(expected, light.getRawBrightness(pos, dark, uncorrected), "dark=" + dark);
        }
        // No chunk is what an unloaded position reads through either entry point.
        for (int dark = 0; dark <= 15; dark++) {
            assertEquals(light.getRawBrightness(pos, dark), light.getRawBrightness(pos, dark, null), "dark=" + dark);
            assertEquals(Math.max(15 - dark, 0), light.getRawBrightness(pos, dark, null));
        }
    }

    private record Harness(ServerLevel level, LevelLightEngine engine, LevelChunk chunk, RandomSource random) {}

    private static Harness harness(int skyDarken, boolean disableLightChecks) {
        ServerLevel level = mock(ServerLevel.class);
        WorldConfiguration paper = mock(WorldConfiguration.class);
        paper.tickRates = mock(WorldConfiguration.TickRates.class);
        paper.tickRates.grassSpread = 1;
        when(level.paperConfig()).thenReturn(paper);
        io.canvasmc.canvas.WorldConfig canvas = mock(io.canvasmc.canvas.WorldConfig.class);
        canvas.disableGrassLightChecks = disableLightChecks;
        when(level.canvasConfig()).thenReturn(canvas);
        RegistryAccess access = mock(RegistryAccess.class);
        doReturn(BuiltInRegistries.BLOCK).when(access).lookupOrThrow(Registries.BLOCK);
        when(level.registryAccess()).thenReturn(access);
        when(level.getSkyDarken()).thenReturn(skyDarken);
        LevelLightEngine engine = mock(LevelLightEngine.class);
        when(level.getLightEngine()).thenReturn(engine);
        LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(chunk.getFluidState(any(BlockPos.class))).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(chunk.getBlockStateFinal(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.AIR.defaultBlockState());
        when(level.getChunkIfLoaded(any(BlockPos.class))).thenReturn(chunk);
        return new Harness(level, engine, chunk, mock(RandomSource.class));
    }

    @Test
    void grassTickReadsLightThroughTheChunkItAlreadyHoldsAndKeepsTheNineThreshold() {
        BlockPos pos = new BlockPos(5, 64, 9);
        for (int light : new int[] {0, 8, 9, 15}) {
            Harness h = harness(3, false);
            when(h.engine().getRawBrightness(eq(pos.above()), eq(3), same(h.chunk()))).thenReturn(light);
            Blocks.GRASS_BLOCK.defaultBlockState().randomTick(h.level(), pos, h.random());
            verify(h.engine()).getRawBrightness(pos.above(), 3, h.chunk());
            verify(h.engine(), never()).getRawBrightness(any(BlockPos.class), anyInt());
            verify(h.level(), never()).getMaxLocalRawBrightness(any(BlockPos.class));
            verify(h.level(), never()).getMaxLocalRawBrightness(any(BlockPos.class), anyInt());
            // Four spread attempts, three random draws each, only when the light check passes.
            verify(h.random(), times(light >= 9 ? 12 : 0)).nextInt(anyInt());
        }
    }

    @Test
    void outOfBoundsPositionsAndDisabledLightChecksSkipTheLightReadAsBefore() {
        // Beyond +-30,000,000 LevelReader.getMaxLocalRawBrightness returns 15 without a light read.
        Harness far = harness(0, false);
        Blocks.MYCELIUM.defaultBlockState().randomTick(far.level(), new BlockPos(30_000_000, 64, -2), far.random());
        verify(far.engine(), never()).getRawBrightness(any(BlockPos.class), anyInt(), any());
        verify(far.random(), times(12)).nextInt(anyInt());

        Harness disabled = harness(0, true);
        Blocks.GRASS_BLOCK.defaultBlockState().randomTick(disabled.level(), new BlockPos(5, 64, 9), disabled.random());
        verify(disabled.engine(), never()).getRawBrightness(any(BlockPos.class), anyInt(), any());
        verify(disabled.random(), times(12)).nextInt(anyInt());
    }
}
