package io.papermc.paper.block;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SpreadingSnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.tags.FluidTags;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@VanillaFeature
class GrassPropagationTest {
    private static boolean propagate(ChunkAccess chunk, BlockState state, BlockPos pos) throws Exception {
        Method m = SpreadingSnowyBlock.class.getDeclaredMethod("canPropagate", ChunkAccess.class, BlockState.class, BlockPos.class);
        m.setAccessible(true);
        return (boolean)m.invoke(null, chunk, state, pos);
    }

    @Test
    void preservesSnowFluidAndLightSemantics() throws Exception {
        ChunkAccess chunk = mock(ChunkAccess.class);
        List<BlockState> aboveStates = new java.util.ArrayList<>();
        for (var block : List.of(Blocks.AIR, Blocks.SNOW, Blocks.WATER, Blocks.LAVA, Blocks.STONE,
                Blocks.OAK_SLAB, Blocks.OAK_STAIRS, Blocks.OAK_LEAVES, Blocks.GLASS, Blocks.ICE)) {
            aboveStates.addAll(block.getStateDefinition().getPossibleStates());
        }
        for (var base : List.of(Blocks.GRASS_BLOCK, Blocks.MYCELIUM)) {
            BlockState state = base.defaultBlockState();
            for (BlockState above : aboveStates) {
                when(chunk.getBlockState(any(BlockPos.class))).thenReturn(above);
                when(chunk.getFluidState(any(BlockPos.class))).thenReturn(above.getFluidState());
                boolean alive;
                if (above.is(Blocks.SNOW) && above.getValue(SnowLayerBlock.LAYERS) == 1) alive = true;
                else if (above.getFluidState().isFull()) alive = false;
                else alive = LightEngine.getLightDampeningInto(state, above, Direction.UP, above.getLightDampening()) < 15;
                boolean expected = alive && !above.getFluidState().is(FluidTags.WATER);
                assertEquals(expected, propagate(chunk, state, new BlockPos(-17, 319, 16)), above.toString());
            }
        }
    }

    @Test
    void doesNotCacheStateBetweenCalls() throws Exception {
        ChunkAccess chunk = mock(ChunkAccess.class);
        BlockPos pos = new BlockPos(15, -64, -16);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(chunk.getFluidState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState().getFluidState());
        assertTrue(propagate(chunk, Blocks.GRASS_BLOCK.defaultBlockState(), pos));
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.WATER.defaultBlockState());
        when(chunk.getFluidState(any(BlockPos.class))).thenReturn(Blocks.WATER.defaultBlockState().getFluidState());
        assertFalse(propagate(chunk, Blocks.GRASS_BLOCK.defaultBlockState(), pos));
    }
}
