package io.papermc.paper.block;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import io.canvasmc.canvas.threadedregions.CanvasRegionizedWorldData;
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

/** Calls the actual random-tick loop. No world state is shared with a running server. */
@VanillaFeature
class RandomTickSectionTraversalTest {
    @Test
    void preservesRngOrderAndObservesSectionReplacementDuringCallbacks() throws Exception {
        for (int speed : new int[] {0, 1, 3, 6, 11}) {
            ServerLevel level = mock(ServerLevel.class);
            LevelChunk chunk = mock(LevelChunk.class);
            RandomSource random = mock(RandomSource.class);
            when(random.nextLong()).thenReturn(0L);
            RegionizedWorldData worldData = mock(RegionizedWorldData.class);
            CanvasRegionizedWorldData canvas = mock(CanvasRegionizedWorldData.class);
            Field rng = CanvasRegionizedWorldData.class.getDeclaredField("simpleUnsafeLocalRandom");
            rng.setAccessible(true); rng.set(canvas, random);
            when(worldData.getCanvasWorldData()).thenReturn(canvas);
            when(chunk.getPos()).thenReturn(new ChunkPos(-1, 2));
            when(level.getMinSectionY()).thenReturn(-4);
            List<String> visits = new ArrayList<>();
            LevelChunkSection empty = mock(LevelChunkSection.class);
            LevelChunkSection[] sections = new LevelChunkSection[3];
            BlockState state = mock(BlockState.class, RETURNS_DEEP_STUBS);
            when(state.getFluidState().isRandomlyTicking()).thenReturn(false);
            LevelChunkSection first = section(state, 0x321);
            LevelChunkSection replacement = section(state, 0x654);
            sections[0] = first; sections[1] = empty; sections[2] = empty;
            when(chunk.getSections()).thenReturn(sections);
            doAnswer(invocation -> {
                BlockPos p = invocation.getArgument(1);
                visits.add(p.getX()+","+p.getY()+","+p.getZ());
                sections[2] = replacement;
                return null;
            }).when(state).randomTick(eq(level), any(BlockPos.class), eq(random));
            Method method = ServerLevel.class.getDeclaredMethod("optimiseRandomTick", LevelChunk.class, int.class, RegionizedWorldData.class);
            method.setAccessible(true); method.invoke(level, chunk, speed, worldData);
            List<String> expected = new ArrayList<>();
            for (int i=0;i<speed;i++) expected.add("-15,-61,34");
            for (int i=0;i<speed;i++) expected.add("-12,-26,37");
            assertEquals(expected, visits, "speed="+speed);
            int active = speed == 0 ? 1 : 2;
            verify(random, times(active * (speed == 0 ? 1 : (speed + 4) / 5))).nextLong();
            verify(empty, never()).getStates();
        }
    }

    @Test
    void observesActivationAndRemovalAcrossSectionBoundariesAndTails() throws Exception {
        for (int count : new int[] {0, 1, 2, 3, 4, 5, 7, 8, 9, 24, 73, 128, 254}) {
            ServerLevel level = mock(ServerLevel.class);
            LevelChunk chunk = mock(LevelChunk.class);
            RandomSource random = mock(RandomSource.class);
            when(random.nextLong()).thenReturn(0L);
            RegionizedWorldData worldData = mock(RegionizedWorldData.class);
            CanvasRegionizedWorldData canvas = mock(CanvasRegionizedWorldData.class);
            Field rng = CanvasRegionizedWorldData.class.getDeclaredField("simpleUnsafeLocalRandom");
            rng.setAccessible(true); rng.set(canvas, random);
            when(worldData.getCanvasWorldData()).thenReturn(canvas);
            when(level.getMinSectionY()).thenReturn(-4);
            when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
            LevelChunkSection empty = mock(LevelChunkSection.class);
            LevelChunkSection[] sections = new LevelChunkSection[count];
            java.util.Arrays.fill(sections, empty);
            when(chunk.getSections()).thenReturn(sections);
            BlockState state = mock(BlockState.class, RETURNS_DEEP_STUBS);
            when(state.getFluidState().isRandomlyTicking()).thenReturn(false);
            LevelChunkSection ticking = section(state, 0);
            if (count > 0) sections[0] = ticking;
            if (count > 1) sections[count - 1] = ticking;
            List<Integer> visited = new ArrayList<>();
            doAnswer(invocation -> {
                BlockPos position = invocation.getArgument(1);
                int index = (position.getY() >> 4) + 4;
                visited.add(index);
                // Replaces the next raw array entry, including 3->4, 7->8 and tail boundaries.
                if (index + 1 < count) sections[index + 1] = ticking;
                // Remove a later active section during a callback. It becomes active again
                // only when the immediately preceding section is actually visited.
                if (index + 2 < count) sections[count - 1] = empty;
                return null;
            }).when(state).randomTick(eq(level), any(BlockPos.class), eq(random));
            Method method = ServerLevel.class.getDeclaredMethod("optimiseRandomTick", LevelChunk.class, int.class, RegionizedWorldData.class);
            method.setAccessible(true); method.invoke(level, chunk, 1, worldData);
            assertEquals(java.util.stream.IntStream.range(0, count).boxed().toList(), visited, "sections=" + count);
            verify(random, times(count)).nextLong();
            verify(empty, never()).getStates();
        }
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
