package io.papermc.paper.block;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
class ActivationExclusionTest {
    private static int nextId;
    static Entity entity(AABB bounds) {
        Entity e = mock(Entity.class);
        when(e.getId()).thenReturn(++nextId);
        when(e.getBoundingBox()).thenReturn(bounds);
        return e;
    }

    @Test
    void ordinaryQueryPreservesOrderBoundsAndPredicateOrder() {
        ChunkEntitySlices slices = new ChunkEntitySlices(null, 0, 0, FullChunkStatus.FULL, null, -4, 20);
        Entity first = entity(new AABB(0, 64, 0, 1, 65, 1));
        Entity outside = entity(new AABB(30, 64, 0, 31, 65, 1));
        Entity last = entity(new AABB(2, 64, 0, 3, 65, 1));
        slices.addEntity(first, 4); slices.addEntity(outside, 4); slices.addEntity(last, 4);
        List<Entity> seen = new ArrayList<>(), result = new ArrayList<>();
        slices.getEntities((Entity)null, new AABB(-1, 63, -1, 4, 66, 2), result, e -> { seen.add(e); return true; });
        assertEquals(List.of(first, last), result);
        assertEquals(result, seen);
        result.clear(); seen.clear();
        slices.getEntities(first, new AABB(-1, 63, -1, 4, 66, 2), result, e -> {seen.add(e); return false;});
        assertTrue(result.isEmpty()); assertEquals(List.of(last), seen);
    }
    @Test
    void exclusionsSkipOnlyOwnedIdentitiesWithoutTouchingTheirBounds() {
        ChunkEntitySlices slices = new ChunkEntitySlices(null, 0, 0, FullChunkStatus.FULL, null, -4, 20);
        Entity active = entity(new AABB(0, 64, 0, 1, 65, 1));
        Entity pending = entity(new AABB(2, 64, 0, 3, 65, 1));
        slices.addEntity(active, 4); slices.addEntity(pending, 4);
        clearInvocations(active, pending);
        var excluded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Entity, Boolean>());
        excluded.add(active);
        List<Entity> result = new ArrayList<>();
        slices.getEntities((Entity)null, new AABB(-1, 63, -1, 4, 66, 2), result, null, excluded);
        assertEquals(List.of(pending), result);
        verify(active, never()).getBoundingBox();
        excluded.clear(); result.clear();
        slices.getEntities((Entity)null, new AABB(-1, 63, -1, 4, 66, 2), result, null, excluded);
        assertEquals(List.of(active, pending), result);
    }

    @Test
    void randomExclusionsMatchOriginalQueryFollowedByIdentityFiltering() {
        java.util.Random random = new java.util.Random(7391);
        ChunkEntitySlices slices = new ChunkEntitySlices(null, 0, 0, FullChunkStatus.FULL, null, -4, 20);
        List<Entity> all = new ArrayList<>();
        for (int i=0;i<80;i++) {
            double x=random.nextDouble()*16, y=random.nextDouble()*64;
            Entity e=entity(new AABB(x,y,0,x+1,y+1,1));
            all.add(e); slices.addEntity(e, (int)y >> 4);
        }
        for (int trial=0;trial<300;trial++) {
            var excluded=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Entity,Boolean>());
            for (Entity e:all) if(random.nextBoolean()) excluded.add(e);
            double x=random.nextDouble()*16,y=random.nextDouble()*64;
            AABB bounds=new AABB(x,y,-1,x+8,y+20,2);
            List<Entity> original=new ArrayList<>(), actual=new ArrayList<>();
            slices.getEntities((Entity)null,bounds,original,null);
            original.removeIf(excluded::contains);
            slices.getEntities((Entity)null,bounds,actual,null,excluded);
            assertEquals(original,actual,"trial="+trial);
        }
    }

}
