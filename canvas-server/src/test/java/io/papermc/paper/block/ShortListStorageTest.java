package io.papermc.paper.block;

import ca.spottedleaf.moonrise.common.list.ShortList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.lang.management.ManagementFactory;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
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

@VanillaFeature
class ShortListStorageTest {
    private static volatile Object allocationSink;

    @Test
    void matchesSwapRemovalOrderAcrossLazyAndReusedStates() {
        ShortList actual = new ShortList();
        List<Short> expected = new ArrayList<>();
        Random random = new Random(742811L);
        assertFalse(actual.remove((short) 0));
        actual.clear(); actual.setMinCapacity(0); actual.setMinCapacity(-1);
        for (int iteration = 0; iteration < 40000; iteration++) {
            short value = (short) random.nextInt(65536);
            switch (random.nextInt(10)) {
                case 0 -> { actual.clear(); expected.clear(); }
                case 1 -> actual.setMinCapacity(random.nextInt(4097));
                case 2, 3, 4 -> {
                    if (!expected.isEmpty() && iteration % 2 == 0) value = expected.get(random.nextInt(expected.size()));
                    int index = expected.indexOf(value);
                    assertEquals(index >= 0, actual.remove(value));
                    if (index >= 0) {
                        short end = expected.remove(expected.size() - 1);
                        if (index < expected.size()) expected.set(index, end);
                    }
                }
                default -> {
                    boolean inserted = !expected.contains(value);
                    assertEquals(inserted, actual.add(value));
                    if (inserted) expected.add(value);
                    assertFalse(actual.add(value));
                }
            }
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i).shortValue(), actual.getRaw(i));
        }
        actual.clear();
        for (int i = 0; i < 4096; i++) assertTrue(actual.add((short) i));
        for (int i = 0; i < 4096; i++) assertTrue(actual.remove((short) i));
        assertEquals(0, actual.size());
        for (short value : new short[] {Short.MIN_VALUE, -1, 0, Short.MAX_VALUE}) assertTrue(actual.add(value));
        assertEquals(4, actual.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualSectionTracksBlockChangesAndRecounts() {
        PalettedContainer<BlockState> blocks = new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), null);
        PalettedContainer<Holder<Biome>> biomes = mock(PalettedContainer.class);
        LevelChunkSection section = new LevelChunkSection(blocks, biomes);
        assertFalse(section.isRandomlyTickingBlocks());
        assertEquals(0, section.moonrise$getTickingBlockList().size());
        for (int pass = 0; pass < 4; pass++) {
            section.setBlockState(3, 5, 7, Blocks.GRASS_BLOCK.defaultBlockState());
            section.setBlockState(15, 0, 0, Blocks.OAK_LEAVES.defaultBlockState());
            assertTrue(section.isRandomlyTickingBlocks());
            assertEquals(2, section.moonrise$getTickingBlockList().size());
            section.recalcBlockCounts();
            assertEquals(2, section.moonrise$getTickingBlockList().size());
            section.setBlockState(3, 5, 7, Blocks.STONE.defaultBlockState());
            section.setBlockState(15, 0, 0, Blocks.AIR.defaultBlockState());
            assertFalse(section.isRandomlyTickingBlocks());
            assertEquals(0, section.moonrise$getTickingBlockList().size());
            section.recalcBlockCounts();
            assertFalse(section.isRandomlyTickingBlocks());
            assertEquals(0, section.moonrise$getTickingBlockList().size());
        }
    }

    @Test
    void reportEmptyConstructionAllocationWithoutTimingClaims() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) return;
        bean.setThreadAllocatedMemoryEnabled(true);
        int count = 30000;
        ShortList[] lists = new ShortList[count];
        for (int i = 0; i < count; i++) lists[i] = new ShortList();
        allocationSink = lists;
        long id = Thread.currentThread().threadId();
        long start = bean.getThreadAllocatedBytes(id);
        for (int i = 0; i < count; i++) lists[i] = new ShortList();
        long bytes = bean.getThreadAllocatedBytes(id) - start;
        allocationSink = lists;
        // Test diagnostic only. No runtime logging or wall-clock speed assertion.
        System.err.println("SHORT_LIST_EMPTY_ALLOCATION count=" + count + " bytes=" + bytes + " bytesPerList=" + (bytes / count));
        assertEquals(0, lists[count - 1].size());
        allocationSink = null;
    }
}
