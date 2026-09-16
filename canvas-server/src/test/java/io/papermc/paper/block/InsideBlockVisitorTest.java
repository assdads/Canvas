package io.papermc.paper.block;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercises the real private traversal with controlled loaded/unloaded chunks, not a rewritten visitor. */
@VanillaFeature
class InsideBlockVisitorTest {
    private static final Method CHECK;
    static {
        try {
            CHECK = Entity.class.getDeclaredMethod("checkInsideBlocks", Vec3.class, Vec3.class,
                InsideBlockEffectApplier.StepBasedCollector.class, it.unimi.dsi.fastutil.longs.LongSet.class, int.class);
            CHECK.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    private static Entity entity(ServerLevel level, AABB box) throws Exception {
        Entity entity = mock(Entity.class, invocation -> {
            if (invocation.getMethod().getName().equals("makeBoundingBox")) return box;
            return org.mockito.Answers.CALLS_REAL_METHODS.answer(invocation);
        });
        Field field = Entity.class.getDeclaredField("level");
        field.setAccessible(true);
        field.set(entity, level);
        doReturn(true).when(entity).isAlive();
        doReturn(level).when(entity).level();
        return entity;
    }

    private static int check(Entity e, int limit) throws Exception {
        return (int) CHECK.invoke(e, Vec3.ZERO, Vec3.ZERO,
            mock(InsideBlockEffectApplier.StepBasedCollector.class), new LongOpenHashSet(), limit);
    }

    @Test
    void cachesMissingChunksButNeverAcrossInvocations() throws Exception {
        ServerLevel level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        when(level.getServer().debugSubscribers().hasAnySubscriberFor(any())).thenReturn(false);
        when(level.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(null);
        Entity e = entity(level, new AABB(15, 64, 0, 18, 65, 1));
        clearInvocations(level);
        assertEquals(1, check(e, 16));
        verify(level, times(1)).getChunkIfLoaded(0, 0);
        verify(level, times(1)).getChunkIfLoaded(1, 0);
        assertEquals(1, check(e, 16));
        verify(level, times(2)).getChunkIfLoaded(0, 0);
        verify(level, times(2)).getChunkIfLoaded(1, 0);
    }

    @Test
    void movingTraversalKeepsIterationLimitAndReentrantState() throws Exception {
        ServerLevel level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        when(level.getServer().debugSubscribers().hasAnySubscriberFor(any())).thenReturn(false);
        LevelChunk chunk = mock(LevelChunk.class);
        when(level.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(chunk);
        AABB box = new AABB(17, 64, 0, 18, 65, 1);
        Entity e = entity(level, box);
        Vec3 from = new Vec3(12, 64, 0), to = new Vec3(17, 64, 0);
        for (int limit : new int[] {0, 1, 2, 16}) {
            List<BlockPos> expected = new ArrayList<>();
            int[] last = {0};
            net.minecraft.world.level.BlockGetter.forEachBlockIntersectedBetween(from, to, box.deflate(1.0E-5F), (pos, iteration) -> {
                if (iteration >= limit) return false;
                last[0] = iteration;
                expected.add(pos.immutable());
                return true;
            });
            List<BlockPos> actual = new ArrayList<>();
            boolean[] nested = {false};
            doAnswer(call -> {
                BlockPos pos = ((BlockPos)call.getArgument(0)).immutable();
                if (!nested[0]) {
                    actual.add(pos);
                    nested[0] = true;
                    try { assertEquals(1, check(e, 1)); }
                    finally { nested[0] = false; }
                }
                return Blocks.AIR.defaultBlockState();
            }).when(chunk).getBlockState(any());
            int result = (int) CHECK.invoke(e, from, to,
                mock(InsideBlockEffectApplier.StepBasedCollector.class), new LongOpenHashSet(), limit);
            assertEquals(last[0] + 1, result);
            assertEquals(expected, actual);
        }
    }

    @Test
    void preservesAirVisitOrderAndEarlyExit() throws Exception {
        ServerLevel level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        when(level.getServer().debugSubscribers().hasAnySubscriberFor(any())).thenReturn(false);
        LevelChunk chunk = mock(LevelChunk.class);
        when(level.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(chunk);
        List<BlockPos> seen = new ArrayList<>();
        when(chunk.getBlockState(any())).thenAnswer(call -> {
            seen.add(((BlockPos)call.getArgument(0)).immutable());
            return Blocks.AIR.defaultBlockState();
        });
        Entity e = entity(level, new AABB(-2, 64, -1, 1, 65, 1));
        assertEquals(1, check(e, 16));
        List<BlockPos> expected = new ArrayList<>();
        BlockPos.betweenClosed(new AABB(-2, 64, -1, 1, 65, 1).deflate(1.0E-5F))
            .forEach(p -> expected.add(p.immutable()));
        assertEquals(expected, seen);
        seen.clear();
        assertEquals(1, check(e, 0));
        assertTrue(seen.isEmpty());
        doReturn(false).when(e).isAlive();
        assertEquals(1, check(e, 16));
        assertTrue(seen.isEmpty());
    }
}
