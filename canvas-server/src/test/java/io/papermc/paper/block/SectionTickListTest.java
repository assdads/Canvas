package io.papermc.paper.block;

import ca.spottedleaf.moonrise.common.list.ShortList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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

@VanillaFeature
class SectionTickListTest {
    @SuppressWarnings("unchecked")
    private static LevelChunkSection section() {
        PalettedContainer<BlockState> blocks = new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), null);
        PalettedContainer<Holder<Biome>> biomes = mock(PalettedContainer.class);
        when(biomes.copy()).thenAnswer(invocation -> mock(PalettedContainer.class));
        return new LevelChunkSection(blocks, biomes);
    }

    private static List<Short> entries(ShortList list) {
        List<Short> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) result.add(list.getRaw(i));
        return result;
    }

    @Test
    void getterRemainsNonNullStableMutableAndSectionLocal() {
        LevelChunkSection first = section(), second = section();
        first.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        first.recalcBlockCounts();
        ShortList exposed = first.moonrise$getTickingBlockList();
        assertNotNull(exposed);
        assertSame(exposed, first.moonrise$getTickingBlockList());
        assertNotSame(exposed, second.moonrise$getTickingBlockList());
        // Existing callers may retain and mutate the returned list. Never share an empty singleton.
        assertTrue(exposed.add((short) 123));
        assertEquals(List.of((short) 123), entries(first.moonrise$getTickingBlockList()));
        assertEquals(0, second.moonrise$getTickingBlockList().size());
        first.recalcBlockCounts();
        assertSame(exposed, first.moonrise$getTickingBlockList());
        assertEquals(0, exposed.size());
        first.setBlockState(3, 5, 7, Blocks.GRASS_BLOCK.defaultBlockState());
        assertEquals(List.of((short) 0x573), entries(exposed));
        first.setBlockState(3, 5, 7, Blocks.STONE.defaultBlockState());
        assertEquals(0, exposed.size());
        first.recalcBlockCounts();
        assertSame(exposed, first.moonrise$getTickingBlockList());
    }

    @Test
    void dormantSectionActivatesWithExactSwapRemovalOrder() {
        LevelChunkSection section = section();
        for (int i = 0; i < 4; i++) {
            section.recalcBlockCounts();
            assertFalse(section.isRandomlyTickingBlocks());
        }
        section.setBlockState(1, 2, 3, Blocks.GRASS_BLOCK.defaultBlockState());
        section.setBlockState(4, 5, 6, Blocks.OAK_LEAVES.defaultBlockState());
        section.setBlockState(7, 8, 9, Blocks.GRASS_BLOCK.defaultBlockState());
        ShortList list = section.moonrise$getTickingBlockList();
        assertEquals(List.of((short) 0x231, (short) 0x564, (short) 0x897), entries(list));
        section.setBlockState(1, 2, 3, Blocks.STONE.defaultBlockState());
        assertEquals(List.of((short) 0x897, (short) 0x564), entries(list));
        section.setBlockState(7, 8, 9, Blocks.MYCELIUM.defaultBlockState());
        assertEquals(List.of((short) 0x897, (short) 0x564), entries(list));
        section.recalcBlockCounts();
        assertEquals(java.util.Set.of((short) 0x897, (short) 0x564), new java.util.HashSet<>(entries(list)));
        assertSame(list, section.moonrise$getTickingBlockList());
    }

    @Test
    void recountAfterPaletteEditsAndCopyUsesIndependentMembership() {
        LevelChunkSection original = section();
        for (int i = 0; i < 4096; i++) {
            original.getStates().getAndSetUnchecked(i & 15, i >>> 8, (i >>> 4) & 15,
                Blocks.GRASS_BLOCK.defaultBlockState());
        }
        original.recalcBlockCounts();
        ShortList list = original.moonrise$getTickingBlockList();
        assertEquals(4096, list.size());
        LevelChunkSection copy = original.copy();
        // Preserve existing copy behavior; counts/list are explicitly recalculated for a mutable copy.
        copy.recalcBlockCounts();
        assertNotSame(list, copy.moonrise$getTickingBlockList());
        assertEquals(entries(list), entries(copy.moonrise$getTickingBlockList()));
        copy.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        assertEquals(4095, copy.moonrise$getTickingBlockList().size());
        assertEquals(4096, list.size());
        for (int i = 0; i < 4096; i++) {
            original.setBlockState(i & 15, i >>> 8, (i >>> 4) & 15, Blocks.AIR.defaultBlockState());
        }
        original.recalcBlockCounts();
        assertSame(list, original.moonrise$getTickingBlockList());
        assertEquals(0, list.size());
        assertFalse(original.isRandomlyTickingBlocks());
    }

    @Test
    void ordinaryEmptyReadsAndRecountsDoNotMaterializeAnAbsentList() throws Exception {
        LevelChunkSection section = section();
        Field field = LevelChunkSection.class.getDeclaredField("tickingBlocks");
        field.setAccessible(true);
        Object initial = field.get(section);
        assertNull(initial, "An untouched section must not own an empty tick-list wrapper");
        for (int i = 0; i < 20; i++) {
            section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
            section.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
            section.recalcBlockCounts();
            assertTrue(section.hasOnlyAir());
            assertFalse(section.isRandomlyTickingBlocks());
            section.getBlockState(0, 0, 0);
            section.getFluidState(0, 0, 0);
        }
        assertSame(initial, field.get(section));
        assertNotNull(section.moonrise$getTickingBlockList());
        assertSame(field.get(section), section.moonrise$getTickingBlockList());
    }
}
