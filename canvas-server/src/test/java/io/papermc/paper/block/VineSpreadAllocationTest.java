package io.papermc.paper.block;

import com.sun.management.ThreadMXBean;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in diagnostic, not a wall-clock CI assertion or a server performance claim. */
@VanillaFeature
@EnabledIfEnvironmentVariable(named = "CANVAS_VINE_MEASURE", matches = "1")
class VineSpreadAllocationTest {
    private static volatile int sink;

    // Exact pre-patch traversal, including its cursor/iterator and early exit.
    private static boolean baseline(BlockGetter level, BlockPos pos) {
        Iterable<BlockPos> iterable = BlockPos.betweenClosed(pos.getX() - 4, pos.getY() - 1, pos.getZ() - 4,
                pos.getX() + 4, pos.getY() + 1, pos.getZ() + 4);
        int max = 5;
        for (BlockPos p : iterable) {
            if (level.getBlockState(p).is(Blocks.VINE) && --max <= 0) return false;
        }
        return true;
    }

    private static final class World implements BlockGetter {
        private int salt;
        private final int mask;
        World(int mask) { this.mask = mask; }
        public BlockState getBlockState(BlockPos p) {
            int hash = p.getX() * 73428767 ^ p.getY() * 912931 ^ p.getZ() * 19349663 ^ salt;
            return ((hash & mask) == 0 ? Blocks.VINE : Blocks.AIR).defaultBlockState();
        }
        public BlockState getBlockStateIfLoaded(BlockPos p) { return getBlockState(p); }
        public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        public FluidState getFluidIfLoaded(BlockPos p) { return getFluidState(p); }
        public BlockEntity getBlockEntity(BlockPos p) { return null; }
        public int getHeight() { return 384; }
        public int getMinY() { return -64; }
    }

    private static long[] measure(MethodHandle method, World world, int count, ThreadMXBean bean) throws Throwable {
        BlockPos center = new BlockPos(-16, 64, 15);
        long thread = Thread.currentThread().threadId();
        long bytes = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        int accepted = 0;
        for (int i = 0; i < count; i++) {
            world.salt = i;
            if ((boolean) method.invokeExact((BlockGetter) world, center)) accepted++;
        }
        long ns = System.nanoTime() - start;
        bytes = bean.getThreadAllocatedBytes(thread) - bytes;
        sink = accepted;
        return new long[]{bytes, ns, accepted};
    }

    @Test
    void compareActualCandidateWithOriginalTraversal() throws Throwable {
        MethodType type = MethodType.methodType(boolean.class, BlockGetter.class, BlockPos.class);
        MethodHandle old = MethodHandles.lookup().findStatic(VineSpreadAllocationTest.class, "baseline", type);
        MethodHandle candidate = MethodHandles.privateLookupIn(VineBlock.class, MethodHandles.lookup())
                .findVirtual(VineBlock.class, "canSpread", type).bindTo(Blocks.VINE);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("VineAllocationProbe");
        for (int mask : new int[]{15, 255, 65535}) {
            World world = new World(mask);
            for (int i = 0; i < 10; i++) {
                measure(old, world, 20000, bean);
                measure(candidate, world, 20000, bean);
            }
            for (int round = 0; round < 6; round++) {
                long[] a, b;
                if ((round & 1) == 0) {
                    a = measure(old, world, 100000, bean);
                    b = measure(candidate, world, 100000, bean);
                } else {
                    b = measure(candidate, world, 100000, bean);
                    a = measure(old, world, 100000, bean);
                }
                assertEquals(a[2], b[2]);
                log.info("VINE_PROBE mask=" + mask + " round=" + round + " calls=100000 baselineBytes="
                        + a[0] + " candidateBytes=" + b[0] + " baselineNs=" + a[1] + " candidateNs=" + b[1]);
            }
        }
    }
}
