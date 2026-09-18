package io.papermc.paper.block;

import io.canvasmc.canvas.world.chunk.RandomTickSectionMask;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the random-tick section bitmap helpers against real sections. */
@VanillaFeature
class RandomTickSectionMaskTest {
    @SuppressWarnings("unchecked")
    private static LevelChunkSection section() {
        PalettedContainer<BlockState> blocks = new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), null);
        PalettedContainer<Holder<Biome>> biomes = mock(PalettedContainer.class);
        return new LevelChunkSection(blocks, biomes);
    }

    private static LevelChunkSection[] sections(int count) {
        LevelChunkSection[] sections = new LevelChunkSection[count];
        for (int i = 0; i < count; i++) sections[i] = section();
        return sections;
    }

    private static long[] bits(int wordCount, int... indexes) {
        long[] words = new long[wordCount];
        for (int i : indexes) words[i >>> 6] |= 1L << (i & 63);
        return words;
    }

    @Test
    void rescanSizesOnceThenFillsInPlaceAndSeesDirectSectionWrites() {
        LevelChunkSection[] sections = sections(73); // the overworld dimension type here: 1168 blocks tall
        sections[5].setBlockState(1, 2, 3, Blocks.GRASS_BLOCK.defaultBlockState());
        sections[72].setBlockState(0, 0, 0, Blocks.LAVA.defaultBlockState());
        long[] words = RandomTickSectionMask.rescan(null, sections);
        assertArrayEquals(bits(2, 5, 72), words);
        // A direct section write that bypasses the chunk is only seen by the next rescan.
        sections[64].setBlockState(4, 4, 4, Blocks.OAK_LEAVES.defaultBlockState());
        assertArrayEquals(bits(2, 5, 72), words);
        assertSame(words, RandomTickSectionMask.rescan(words, sections), "an already sized array is reused");
        assertArrayEquals(bits(2, 5, 64, 72), words);
        long[] wrongSize = new long[1];
        assertNotSame(wrongSize, RandomTickSectionMask.rescan(wrongSize, sections));
    }

    @Test
    void trackedWritesAreExactInBothDirectionsAndIgnoreIndexesTheChunkCannotHold() {
        LevelChunkSection[] sections = sections(41); // the OriginNature dimension type here
        LevelChunkSection ticking = section();
        ticking.setBlockState(0, 0, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        RandomTickSectionMask.update(null, 1, ticking); // before the first rescan: nothing to record
        long[] words = RandomTickSectionMask.rescan(null, sections);
        assertArrayEquals(bits(1), words);
        sections[1].setBlockState(0, 0, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        RandomTickSectionMask.update(words, 1, sections[1]);
        assertArrayEquals(bits(1, 1), words);
        sections[1].setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        RandomTickSectionMask.update(words, 1, sections[1]);
        assertArrayEquals(bits(1), words);
        // Stone in section 2 is not randomly ticking: the write must leave the bit clear.
        sections[2].setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        RandomTickSectionMask.update(words, 2, sections[2]);
        assertArrayEquals(bits(1), words);
        RandomTickSectionMask.update(words, 64, ticking);
        RandomTickSectionMask.update(words, -1, ticking);
        assertArrayEquals(bits(1), words, "indexes outside the sized words never touch the mask");
        RandomTickSectionMask.update(words, 40, ticking);
        RandomTickSectionMask.update(words, 63, ticking);
        assertArrayEquals(bits(1, 40, 63), words);
        assertEquals(0L, RandomTickSectionMask.remainingAfter(words[0], 63));
        assertEquals(Long.MIN_VALUE, RandomTickSectionMask.remainingAfter(words[0], 62));
        assertEquals(Long.MIN_VALUE | (1L << 40), RandomTickSectionMask.remainingAfter(words[0], 39));
        assertEquals(-2L, RandomTickSectionMask.remainingAfter(-1L, 0));
    }

    @Test
    void rescanMatchesEverySectionCountAcrossWordBoundaries() {
        for (int count : new int[] {0, 1, 2, 24, 41, 63, 64, 65, 73, 127, 128, 129, 254}) {
            LevelChunkSection[] sections = sections(count);
            long[] expected = new long[RandomTickSectionMask.wordCount(count)];
            for (int i = 0; i < count; i += 3) {
                sections[i].setBlockState(i & 15, 0, 0, Blocks.GRASS_BLOCK.defaultBlockState());
                expected[i >>> 6] |= 1L << (i & 63);
            }
            assertArrayEquals(expected, RandomTickSectionMask.rescan(null, sections), "count=" + count);
        }
        assertEquals(0, RandomTickSectionMask.wordCount(0));
        assertEquals(1, RandomTickSectionMask.wordCount(64));
        assertEquals(2, RandomTickSectionMask.wordCount(65));
        assertEquals(2, RandomTickSectionMask.wordCount(73));
        assertEquals(4, RandomTickSectionMask.wordCount(254));
        LevelChunkSection[] withNull = sections(70);
        withNull[2] = null;
        withNull[69].setBlockState(0, 0, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        assertArrayEquals(bits(2, 69), RandomTickSectionMask.rescan(null, withNull));
    }

    @Test
    void recountedSectionsAreReflectedByRescanNotByStaleBits() {
        LevelChunkSection[] sections = sections(2);
        sections[0].setBlockState(0, 0, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        long[] words = RandomTickSectionMask.rescan(null, sections);
        assertArrayEquals(bits(1, 0), words);
        sections[0].setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        sections[0].recalcBlockCounts();
        assertFalse(sections[0].isRandomlyTickingBlocks());
        assertArrayEquals(bits(1, 0), words, "the caller's live check protects a stale set bit");
        assertArrayEquals(bits(1), RandomTickSectionMask.rescan(words, sections));
        assertTrue(RandomTickSectionMask.RESCAN_TICKS >= 1);
    }
}
