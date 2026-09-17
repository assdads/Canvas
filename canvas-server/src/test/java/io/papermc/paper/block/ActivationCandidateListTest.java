package io.papermc.paper.block;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import io.papermc.paper.entity.activation.ActivationRange;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.spigotmc.SpigotWorldConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@VanillaFeature
class ActivationCandidateListTest {
    private static ServerLevel world(List<Player> players, EntityLookup lookup) throws Exception {
        ServerLevel world = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        SpigotWorldConfig config = mock(SpigotWorldConfig.class);
        config.simulationDistance = 4;
        config.miscActivationRange = 32;
        config.ignoreSpectatorActivation = true;
        Field field = Level.class.getDeclaredField("spigotConfig");
        field.setAccessible(true); field.set(world, config);
        doReturn(players).when(world).getLocalPlayers();
        when(world.getCurrentWorldData()).thenReturn(mock(RegionizedWorldData.class));
        when(world.moonrise$getEntityLookup()).thenReturn(lookup);
        when(world.getHeight()).thenReturn(384);
        var paper = mock(io.papermc.paper.configuration.WorldConfiguration.class);
        paper.entities = mock(io.papermc.paper.configuration.WorldConfiguration.Entities.class);
        paper.entities.markers = mock(io.papermc.paper.configuration.WorldConfiguration.Entities.Markers.class);
        when(world.paperConfig()).thenReturn(paper);
        paper.entities.markers.tick = false;
        return world;
    }

    private static void alwaysActive(Entity entity) throws Exception {
        Field field = Entity.class.getDeclaredField("defaultActivationState");
        field.setAccessible(true); field.setBoolean(entity, true);
    }

    private static Player player(double x) {
        Player player = mock(Player.class);
        when(player.getBoundingBox()).thenReturn(new AABB(x, 64, 0, x + 1, 66, 1));
        return player;
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearsBetweenPlayersAndInvocationsWithoutLosingOwnershipChecks() throws Exception {
        Player first = player(0), spectator = player(20), second = player(40);
        when(spectator.isSpectator()).thenReturn(true);
        EntityLookup lookup = mock(EntityLookup.class);
        ServerLevel world = world(List.of(first, spectator, second), lookup);
        Entity owned = mock(Entity.class), foreign = mock(Entity.class);
        Marker marker = mock(Marker.class);
        alwaysActive(owned); alwaysActive(foreign);
        owned.activatedTick = foreign.activatedTick = marker.activatedTick = Long.MIN_VALUE;
        AtomicInteger queries = new AtomicInteger();
        doAnswer(invocation -> {
            List<Entity> into = invocation.getArgument(2);
            assertTrue(into.isEmpty(), "A previous player's candidates escaped into the next query");
            assertNull(invocation.getArgument(3), "Do not reorder filtering into the spatial query");
            AABB bounds = invocation.getArgument(1);
            assertEquals((queries.get() % 2 == 0 ? 0 : 40) - 32, bounds.minX);
            if (queries.getAndIncrement() % 2 == 0) into.addAll(List.of(owned, foreign, marker));
            return null;
        }).when(lookup).getEntities(isNull(Entity.class), any(AABB.class), anyList(), isNull());
        try (var clock = mockStatic(RegionizedServer.class); var tick = mockStatic(TickThread.class)) {
            clock.when(RegionizedServer::getCurrentTick).thenReturn(100L);
            tick.when(() -> TickThread.isTickThreadFor(owned)).thenReturn(true);
            tick.when(() -> TickThread.isTickThreadFor(marker)).thenReturn(true);
            tick.when(() -> TickThread.isTickThreadFor(foreign)).thenReturn(false);
            ActivationRange.activateEntities(world);
            ActivationRange.activateEntities(world);
            assertEquals(4, queries.get());
            tick.verify(() -> TickThread.isTickThreadFor(owned), times(2));
            tick.verify(() -> TickThread.isTickThreadFor(foreign), times(2));
            tick.verify(() -> TickThread.isTickThreadFor(marker), times(2));
        }
        assertEquals(100L, owned.activatedTick);
        assertEquals(Long.MIN_VALUE, foreign.activatedTick);
        assertEquals(Long.MIN_VALUE, marker.activatedTick);
        verify(spectator, never()).getBoundingBox();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reentrantActivationHasIndependentCandidateStorage() throws Exception {
        EntityLookup lookup = mock(EntityLookup.class);
        ServerLevel world = world(List.of(player(0)), lookup);
        Entity owned = mock(Entity.class); alwaysActive(owned);
        owned.activatedTick = Long.MIN_VALUE;
        AtomicInteger depth = new AtomicInteger();
        doAnswer(invocation -> {
            List<Entity> into = invocation.getArgument(2);
            assertTrue(into.isEmpty());
            if (depth.getAndIncrement() == 0) {
                into.add(owned);
                ActivationRange.activateEntities(world);
                assertEquals(List.of(owned), into, "Nested activation cleared its caller's candidates");
            }
            return null;
        }).when(lookup).getEntities(isNull(Entity.class), any(AABB.class), anyList(), isNull());
        try (var clock = mockStatic(RegionizedServer.class); var tick = mockStatic(TickThread.class)) {
            clock.when(RegionizedServer::getCurrentTick).thenReturn(100L);
            tick.when(() -> TickThread.isTickThreadFor(owned)).thenReturn(true);
            ActivationRange.activateEntities(world);
        }
        assertEquals(2, depth.get());
        assertEquals(100L, owned.activatedTick);
    }
}
