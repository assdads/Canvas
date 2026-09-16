package io.papermc.paper.block;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@VanillaFeature
class VineSpreadTraversalTest {
    private static final Method CAN_SPREAD;
    static {
        try {
            CAN_SPREAD = VineBlock.class.getDeclaredMethod("canSpread", BlockGetter.class, BlockPos.class);
            CAN_SPREAD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void check(BlockPos center, Set<BlockPos> vines) throws Exception {
        List<BlockPos> expectedVisits = new ArrayList<>();
        int remaining = 5;
        boolean expected = true;
        for (BlockPos p : BlockPos.betweenClosed(center.getX() - 4, center.getY() - 1, center.getZ() - 4,
                center.getX() + 4, center.getY() + 1, center.getZ() + 4)) {
            expectedVisits.add(p.immutable());
            if (vines.contains(p) && --remaining == 0) {
                expected = false;
                break;
            }
        }
        List<BlockPos> actualVisits = new ArrayList<>();
        BlockGetter getter = (BlockGetter) Proxy.newProxyInstance(BlockGetter.class.getClassLoader(),
                new Class<?>[]{BlockGetter.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getBlockState")) {
                        throw new AssertionError("Unexpected world access: " + method.getName());
                    }
                    BlockPos p = (BlockPos) args[0];
                    actualVisits.add(p.immutable());
                    return (vines.contains(p) ? Blocks.VINE : Blocks.AIR).defaultBlockState();
                });
        assertEquals(expected, CAN_SPREAD.invoke(Blocks.VINE, getter, center));
        assertEquals(expectedVisits, actualVisits, "Block access order and early termination must match");
    }

    @Test
    void preservesTraversalAndCutoff() throws Exception {
        Random random = new Random(1709);
        for (BlockPos center : List.of(BlockPos.ZERO, new BlockPos(-16, -64, -17),
                new BlockPos(15, 319, 16), new BlockPos(29999980, 64, -29999980))) {
            List<BlockPos> positions = new ArrayList<>();
            BlockPos.betweenClosed(center.offset(-4, -1, -4), center.offset(4, 1, 4))
                    .forEach(p -> positions.add(p.immutable()));
            check(center, Set.of());
            for (int count : new int[]{1, 4, 5, 6, 243}) {
                check(center, new HashSet<>(positions.subList(0, count)));
                check(center, new HashSet<>(positions.subList(positions.size() - count, positions.size())));
            }
            for (int sample = 0; sample < 64; sample++) {
                Set<BlockPos> vines = new HashSet<>();
                for (BlockPos p : positions) if (random.nextInt(40) == 0) vines.add(p);
                check(center, vines);
            }
        }
    }
}
