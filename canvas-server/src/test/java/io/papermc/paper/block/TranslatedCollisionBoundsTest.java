package io.papermc.paper.block;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import java.util.Random;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards the exact overload substitution used by the translated single-box collision path.
 * This checks geometry, not world lookup, predicate callbacks or complete entity movement.
 */
@org.bukkit.support.environment.VanillaFeature
class TranslatedCollisionBoundsTest {
    private static void compare(AABB query, AABB shape, int x, int y, int z) {
        AABB moved = shape.move((double)x, (double)y, (double)z);
        double minX = shape.minX + (double)x;
        double minY = shape.minY + (double)y;
        double minZ = shape.minZ + (double)z;
        double maxX = shape.maxX + (double)x;
        double maxY = shape.maxY + (double)y;
        double maxZ = shape.maxZ + (double)z;
        assertEquals(query.intersects(moved), query.intersects(minX, minY, minZ, maxX, maxY, maxZ));
        assertEquals(CollisionUtil.voxelShapeIntersect(query, moved),
            CollisionUtil.voxelShapeIntersect(query, minX, minY, minZ, maxX, maxY, maxZ));
    }

    @Test
    void matchesAtContactEpsilonAndWorldEdges() {
        double e = CollisionUtil.COLLISION_EPSILON;
        double[] offsets = {-e * 2, -e, Math.nextUp(-e), -0.0, 0.0, Math.nextDown(e), e, e * 2};
        AABB[] shapes = {new AABB(0, 0, 0, 1, 1, 1), new AABB(0, 0, 0, 1, 0.5, 1),
            new AABB(-0.5, -0.5, -0.5, 1.5, 1.5, 1.5), new AABB(0, 0, 0, 0, 0, 0)};
        for (int coord : new int[] {-30000000, -17, -16, -1, 0, 15, 16, 30000000}) {
            for (AABB shape : shapes) {
                AABB moved = shape.move(coord, coord, coord);
                for (double offset : offsets) {
                    compare(new AABB(moved.maxX + offset, moved.minY, moved.minZ,
                        moved.maxX + offset + 1, moved.maxY + 1, moved.maxZ + 1), shape, coord, coord, coord);
                    compare(new AABB(moved.minX, moved.maxY + offset, moved.minZ,
                        moved.maxX + 1, moved.maxY + offset + 1, moved.maxZ + 1), shape, coord, coord, coord);
                    compare(new AABB(moved.minX, moved.minY, moved.maxZ + offset,
                        moved.maxX + 1, moved.maxY + 1, moved.maxZ + offset + 1), shape, coord, coord, coord);
                }
            }
        }
    }

    @Test
    void matchesForRandomTranslatedBoxes() {
        Random random = new Random(0xC0111510L);
        for (int i = 0; i < 100000; ++i) {
            int x = random.nextInt(60000001) - 30000000;
            int y = random.nextInt(4096) - 2048;
            int z = random.nextInt(60000001) - 30000000;
            AABB shape = new AABB(random.nextDouble() - 1, random.nextDouble() - 1, random.nextDouble() - 1,
                random.nextDouble() + 1, random.nextDouble() + 1, random.nextDouble() + 1);
            AABB query = new AABB(x + random.nextDouble() * 4 - 2, y + random.nextDouble() * 4 - 2,
                z + random.nextDouble() * 4 - 2, x + random.nextDouble() * 4,
                y + random.nextDouble() * 4, z + random.nextDouble() * 4);
            compare(query, shape, x, y, z);
        }
    }
}
