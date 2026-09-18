package io.papermc.paper.block;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import io.canvasmc.canvas.threadedregions.CanvasRegionizedWorldData;
import io.canvasmc.canvas.world.chunk.RandomTickSectionMask;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Calls the actual random-tick loop. No world state is shared with a running server.
 * The chunk is a mock whose bitmap methods call the real {@code LevelChunk} code, so the countdown
 * and word handling under test are the shipped ones; callbacks that write "through the chunk" call
 * {@link RandomTickSectionMask#update} the way {@code LevelChunk.setBlockState} does.
 */
@VanillaFeature
class RandomTickSectionTraversalTest {
    private record Harness(ServerLevel level, LevelChunk chunk, RandomSource random, RegionizedWorldData worldData,
                           LevelChunkSection[] sections, Method method) {
        void run(int speed) throws Exception {
            this.method.invoke(this.level, this.chunk, speed, this.worldData);
        }
        /** What LevelChunk.setBlockState does after writing section {@code index}. */
        void written(int index, LevelChunkSection section) {
            RandomTickSectionMask.update(this.chunk.origin$randomTickWords(), index, section);
        }
    }

    private static Harness harness(LevelChunkSection[] sections, ChunkPos pos) throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        LevelChunk chunk = mock(LevelChunk.class);
        RandomSource random = mock(RandomSource.class);
        when(random.nextLong()).thenReturn(0L);
        RegionizedWorldData worldData = mock(RegionizedWorldData.class);
        CanvasRegionizedWorldData canvas = mock(CanvasRegionizedWorldData.class);
        Field rng = CanvasRegionizedWorldData.class.getDeclaredField("simpleUnsafeLocalRandom");
        rng.setAccessible(true); rng.set(canvas, random);
        when(worldData.getCanvasWorldData()).thenReturn(canvas);
        when(chunk.getPos()).thenReturn(pos);
        when(level.getMinSectionY()).thenReturn(-4);
        when(chunk.getSections()).thenReturn(sections);
        // The mock skips the constructor, so the fields start as null / 0 exactly like a fresh chunk's bitmap state.
        doCallRealMethod().when(chunk).origin$randomTickWordsForTick(any());
        when(chunk.origin$randomTickWords()).thenCallRealMethod();
        when(chunk.origin$randomTickRescanCountdown()).thenCallRealMethod();
        Method method = ServerLevel.class.getDeclaredMethod("optimiseRandomTick", LevelChunk.class, int.class, RegionizedWorldData.class);
        method.setAccessible(true);
        return new Harness(level, chunk, random, worldData, sections, method);
    }

    private static BlockState tickingState() {
        BlockState state = mock(BlockState.class, RETURNS_DEEP_STUBS);
        when(state.getFluidState().isRandomlyTicking()).thenReturn(false);
        return state;
    }

    @Test
    void preservesRngOrderAndObservesTrackedActivationDuringCallbacks() throws Exception {
        for (int speed : new int[] {0, 1, 3, 6, 11}) {
            List<String> visits = new ArrayList<>();
            LevelChunkSection empty = mock(LevelChunkSection.class);
            LevelChunkSection[] sections = new LevelChunkSection[3];
            BlockState state = tickingState();
            LevelChunkSection first = section(state, 0x321);
            LevelChunkSection replacement = section(state, 0x654);
            sections[0] = first; sections[1] = empty; sections[2] = empty;
            Harness h = harness(sections, new ChunkPos(-1, 2));
            doAnswer(invocation -> {
                BlockPos p = invocation.getArgument(1);
                visits.add(p.getX()+","+p.getY()+","+p.getZ());
                sections[2] = replacement;
                h.written(2, replacement);
                return null;
            }).when(state).randomTick(eq(h.level()), any(BlockPos.class), eq(h.random()));
            h.run(speed);
            List<String> expected = new ArrayList<>();
            for (int i=0;i<speed;i++) expected.add("-15,-61,34");
            for (int i=0;i<speed;i++) expected.add("-12,-26,37");
            assertEquals(expected, visits, "speed="+speed);
            int active = speed == 0 ? 1 : 2;
            verify(h.random(), times(active * (speed == 0 ? 1 : (speed + 4) / 5))).nextLong();
            verify(empty, never()).getStates();
            assertEquals(RandomTickSectionMask.RESCAN_TICKS, h.chunk().origin$randomTickRescanCountdown());
        }
    }

    @Test
    void observesTrackedActivationAndRemovalAcrossSectionBoundariesAndTails() throws Exception {
        // 41 and 73 are the section counts of the worlds this fork runs; 63..65 and 127..129 cross word boundaries.
        for (int count : new int[] {0, 1, 2, 3, 4, 5, 7, 8, 9, 24, 41, 63, 64, 65, 73, 127, 128, 129, 254}) {
            LevelChunkSection empty = mock(LevelChunkSection.class);
            LevelChunkSection[] sections = new LevelChunkSection[count];
            java.util.Arrays.fill(sections, empty);
            BlockState state = tickingState();
            LevelChunkSection ticking = section(state, 0);
            if (count > 0) sections[0] = ticking;
            if (count > 1) sections[count - 1] = ticking;
            Harness h = harness(sections, new ChunkPos(0, 0));
            List<Integer> visited = new ArrayList<>();
            doAnswer(invocation -> {
                BlockPos position = invocation.getArgument(1);
                int index = (position.getY() >> 4) + 4;
                visited.add(index);
                // Activates the next entry through the chunk, including 63->64 and 127->128 word boundaries and tails.
                if (index + 1 < count) { sections[index + 1] = ticking; h.written(index + 1, ticking); }
                // Remove a later active section during a callback. It becomes active again
                // only when the immediately preceding section is actually visited.
                if (index + 2 < count) { sections[count - 1] = empty; h.written(count - 1, empty); }
                return null;
            }).when(state).randomTick(eq(h.level()), any(BlockPos.class), eq(h.random()));
            h.run(1);
            assertEquals(java.util.stream.IntStream.range(0, count).boxed().toList(), visited, "sections=" + count);
            verify(h.random(), times(count)).nextLong();
            verify(empty, never()).getStates();
            assertEquals(RandomTickSectionMask.wordCount(count), h.chunk().origin$randomTickWords().length);
        }
    }

    @Test
    void untrackedReplacementIsSkippedUntilTheNextRescanAndNeverLater() throws Exception {
        LevelChunkSection empty = mock(LevelChunkSection.class);
        LevelChunkSection[] sections = new LevelChunkSection[73];
        java.util.Arrays.fill(sections, empty);
        BlockState state = tickingState();
        LevelChunkSection first = section(state, 0x321);
        LevelChunkSection late = section(state, 0x654);
        sections[0] = first;
        Harness h = harness(sections, new ChunkPos(0, 0));
        List<Integer> visits = new ArrayList<>();
        doAnswer(invocation -> {
            BlockPos p = invocation.getArgument(1);
            visits.add((p.getY() >> 4) + 4);
            return null;
        }).when(state).randomTick(eq(h.level()), any(BlockPos.class), eq(h.random()));
        h.run(1); // pass 1 rescans a fresh chunk
        assertEquals(List.of(0), visits);
        sections[70] = late; // replaced behind the chunk in the second word: no update() call
        for (int pass = 2; pass <= RandomTickSectionMask.RESCAN_TICKS; pass++) {
            visits.clear();
            h.run(1);
            assertEquals(List.of(0), visits, "pass=" + pass + " must not see the untracked section yet");
        }
        visits.clear();
        h.run(1); // pass RESCAN_TICKS + 1 rescans
        assertEquals(List.of(0, 70), visits, "the rescan pass must pick the replaced section up");
        visits.clear();
        h.run(1);
        assertEquals(List.of(0, 70), visits, "and it stays visible afterwards");
        verify(empty, never()).getStates();
    }

    @Test
    void staleSetBitNeverTicksAnInactiveSectionAndKeepsOrder() throws Exception {
        LevelChunkSection[] sections = new LevelChunkSection[4];
        BlockState state = tickingState();
        LevelChunkSection a = section(state, 0x001);
        LevelChunkSection b = section(state, 0x002);
        LevelChunkSection c = section(state, 0x003);
        LevelChunkSection d = section(state, 0x004);
        sections[0] = a; sections[1] = b; sections[2] = c; sections[3] = d;
        Harness h = harness(sections, new ChunkPos(0, 0));
        List<Integer> visits = new ArrayList<>();
        doAnswer(invocation -> {
            BlockPos p = invocation.getArgument(1);
            visits.add((p.getY() >> 4) + 4);
            return null;
        }).when(state).randomTick(eq(h.level()), any(BlockPos.class), eq(h.random()));
        h.run(1);
        assertEquals(List.of(0, 1, 2, 3), visits);
        // Section 1 loses its ticking blocks behind the chunk: the bit stays set, the live check must skip it.
        when(b.isRandomlyTickingBlocks()).thenReturn(false);
        clearInvocations(b);
        visits.clear();
        h.run(1);
        assertEquals(List.of(0, 2, 3), visits);
        verify(b, never()).getStates();
        assertEquals(0b1111L, h.chunk().origin$randomTickWords()[0], "a stale set bit is only cleared by a rescan or a tracked write");
        h.written(1, b);
        assertEquals(0b1101L, h.chunk().origin$randomTickWords()[0]);
    }

    @SuppressWarnings("unchecked")
    private static LevelChunkSection section(BlockState state, int position) {
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.isRandomlyTickingBlocks()).thenReturn(true);
        var list = new ca.spottedleaf.moonrise.common.list.ShortList();
        list.add((short)position);
        when(section.moonrise$getTickingBlockList()).thenReturn(list);
        PalettedContainer<BlockState> states = mock(PalettedContainer.class);
        when(states.get(anyInt())).thenReturn(state);
        when(section.getStates()).thenReturn(states);
        return section;
    }
}
