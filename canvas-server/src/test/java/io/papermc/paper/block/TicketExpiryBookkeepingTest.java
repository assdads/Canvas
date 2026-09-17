package io.papermc.paper.block;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ThreadedTicketLevelPropagator;
import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import ca.spottedleaf.moonrise.patches.chunk_system.util.stream.TicketSet;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Drives the real ChunkHolderManager ticket table, expiry bookkeeping and tick() with a mocked
 * region and ticket-level propagator. Nothing here touches a live world.
 */
@VanillaFeature
class TicketExpiryBookkeepingTest {
    private static final int REGION_SHIFT = 2;
    private static final long PERMANENT = Long.MIN_VALUE;

    private record Fixture(ChunkHolderManager manager, ThreadedTicketLevelPropagator propagator) {}

    private static Fixture fixture() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        when(level.moonrise$getRegionChunkShift()).thenReturn(REGION_SHIFT);
        ChunkTaskScheduler scheduler = mock(ChunkTaskScheduler.class);
        when(scheduler.getChunkSystemLockShift()).thenReturn(6);
        ChunkHolderManager manager = new ChunkHolderManager(level, scheduler);
        ThreadedTicketLevelPropagator propagator = mock(ThreadedTicketLevelPropagator.class);
        Field field = ChunkHolderManager.class.getDeclaredField("ticketLevelPropagator");
        field.setAccessible(true);
        field.set(manager, propagator);
        return new Fixture(manager, propagator);
    }

    private static TicketType type(String name, long timeout) {
        return ChunkSystemTicketType.create(name, null, timeout);
    }

    private static long section(long chunkKey) {
        return CoordinateUtils.getChunkKey(CoordinateUtils.getChunkX(chunkKey) >> REGION_SHIFT, CoordinateUtils.getChunkZ(chunkKey) >> REGION_SHIFT);
    }

    @SuppressWarnings("unchecked")
    private static void tick(ChunkHolderManager manager, long... ownedSections) {
        ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = mock(ThreadedRegionizer.ThreadedRegion.class);
        when(region.getOwnedSectionsUnsynchronised()).thenAnswer(invocation -> new LongArrayList(ownedSections).iterator());
        try (var scheduler = mockStatic(TickRegionScheduler.class)) {
            scheduler.when(TickRegionScheduler::getCurrentRegion).thenReturn(region);
            manager.tick();
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Method expiringCounter() {
        try {
            return TicketSet.class.getMethod("getExpiringCount");
        } catch (NoSuchMethodException e) {
            return null; // upstream layout keeps counts in the section maps instead
        }
    }

    /** Every chunk with an expiring ticket is indexed once, under its own region section, with the right count. */
    @SuppressWarnings("unchecked")
    private static void assertBookkeeping(ChunkHolderManager manager) throws Exception {
        var tickets = (ConcurrentChainedLong2ReferenceHashTable<TicketSet>) field(manager, "tickets");
        var sections = (ConcurrentChainedLong2ReferenceHashTable<?>) field(manager, "sectionToChunkToExpireCount");
        Method counter = expiringCounter();
        Long2IntOpenHashMap expected = new Long2IntOpenHashMap();
        for (var iterator = tickets.entryIterator(); iterator.hasNext();) {
            var entry = iterator.next();
            TicketSet set = entry.getValue();
            assertFalse(set.isEmpty(), "empty ticket set retained at " + entry.getKey());
            int expiring = 0;
            for (Ticket ticket : set) {
                if (ticket.moonrise$getRemoveDelay() != PERMANENT) expiring++;
            }
            if (expiring > 0) expected.put(entry.getKey(), expiring);
            if (counter != null) assertEquals(expiring, (int) counter.invoke(set), "count stored on set at " + entry.getKey());
        }
        Long2IntOpenHashMap actual = new Long2IntOpenHashMap();
        for (var iterator = sections.entryIterator(); iterator.hasNext();) {
            var entry = iterator.next();
            long sectionKey = entry.getKey();
            Object map = entry.getValue();
            if (map instanceof Long2IntOpenHashMap counts) {
                assertFalse(counts.isEmpty(), "empty section map retained");
                for (var chunk : counts.long2IntEntrySet()) {
                    assertEquals(sectionKey, section(chunk.getLongKey()));
                    assertFalse(actual.containsKey(chunk.getLongKey()), "chunk indexed twice");
                    actual.put(chunk.getLongKey(), chunk.getIntValue());
                }
            } else {
                var sets = (Long2ObjectOpenHashMap<TicketSet>) map;
                assertFalse(sets.isEmpty(), "empty section map retained");
                for (var chunk : sets.long2ObjectEntrySet()) {
                    assertEquals(sectionKey, section(chunk.getLongKey()));
                    assertSame(tickets.get(chunk.getLongKey()), chunk.getValue(), "indexed set must be the live set");
                    assertFalse(actual.containsKey(chunk.getLongKey()), "chunk indexed twice");
                    actual.put(chunk.getLongKey(), (int) counter.invoke(chunk.getValue()));
                }
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void timedTicketsExpireOnTheirOwnerTickAndPropagateTheNewLevel() throws Exception {
        Fixture fixture = fixture();
        ChunkHolderManager manager = fixture.manager();
        TicketType timed = type("origin:timed3", 3L);
        TicketType permanent = type("origin:permanent", 0L);
        long chunk = CoordinateUtils.getChunkKey(5, -3);
        assertTrue(manager.addTicketAtLevel(timed, chunk, 30, null));
        verify(fixture.propagator()).setSource(5, -3, ChunkHolderManager.convertBetweenTicketLevels(30));
        assertTrue(manager.addTicketAtLevel(permanent, chunk, 31, null));
        assertBookkeeping(manager);

        for (int i = 0; i < 2; i++) {
            tick(manager, section(chunk));
            assertEquals(2, manager.getTicketsAt(5, -3).size(), "tick " + i);
            assertBookkeeping(manager);
        }
        tick(manager, CoordinateUtils.getChunkKey(50, 50)); // another region's tick must not advance this chunk
        assertEquals(2, manager.getTicketsAt(5, -3).size());
        tick(manager, section(chunk));
        List<Ticket> left = manager.getTicketsAt(5, -3);
        assertEquals(1, left.size());
        assertSame(permanent, left.get(0).getType());
        verify(fixture.propagator()).setSource(5, -3, ChunkHolderManager.convertBetweenTicketLevels(31));
        assertBookkeeping(manager);
    }

    @Test
    void removingTheLowestTicketDefersTheLevelChangeThroughAnExpiringUnknownTicket() throws Exception {
        Fixture fixture = fixture();
        ChunkHolderManager manager = fixture.manager();
        TicketType permanent = type("origin:permanent2", 0L);
        long chunk = CoordinateUtils.getChunkKey(-9, 17);
        assertTrue(manager.addTicketAtLevel(permanent, chunk, 33, null));
        assertBookkeeping(manager);
        clearInvocations(fixture.propagator());

        assertTrue(manager.removeTicketAtLevel(permanent, chunk, 33, null));
        List<Ticket> deferred = manager.getTicketsAt(-9, 17);
        assertEquals(1, deferred.size());
        assertSame(TicketType.UNKNOWN, deferred.get(0).getType());
        assertEquals(33, deferred.get(0).getTicketLevel());
        assertEquals(1L, deferred.get(0).moonrise$getRemoveDelay());
        verifyNoMoreInteractions(fixture.propagator());
        assertBookkeeping(manager);

        tick(manager, section(chunk));
        assertTrue(manager.getTicketsAt(-9, 17).isEmpty());
        assertFalse(manager.hasTickets());
        verify(fixture.propagator()).removeSource(-9, 17);
        assertBookkeeping(manager);
        assertFalse(manager.removeTicketAtLevel(permanent, chunk, 33, null));
    }

    @Test
    void replacingBetweenTimedAndPermanentKeepsTheIndexAndTheSameSet() throws Exception {
        Fixture fixture = fixture();
        ChunkHolderManager manager = fixture.manager();
        TicketType flexible = type("origin:flexible", 10L);
        long chunk = CoordinateUtils.getChunkKey(2, 2);
        assertTrue(manager.addTicketAtLevel(flexible, chunk, 30, null));
        assertBookkeeping(manager);
        flexible.moonrise$setTimeout(0L);
        assertFalse(manager.addTicketAtLevel(flexible, chunk, 30, null)); // replaced, now permanent
        assertEquals(PERMANENT, manager.getTicketsAt(2, 2).get(0).moonrise$getRemoveDelay());
        assertBookkeeping(manager);
        flexible.moonrise$setTimeout(10L);
        assertFalse(manager.addTicketAtLevel(flexible, chunk, 30, null)); // replaced, timed again
        assertEquals(10L, manager.getTicketsAt(2, 2).get(0).moonrise$getRemoveDelay());
        assertBookkeeping(manager);
        for (int i = 0; i < 9; i++) tick(manager, section(chunk));
        assertEquals(1, manager.getTicketsAt(2, 2).size());
        assertFalse(manager.addTicketAtLevel(flexible, chunk, 30, null)); // refresh resets the countdown
        assertEquals(10L, manager.getTicketsAt(2, 2).get(0).moonrise$getRemoveDelay());
        for (int i = 0; i < 10; i++) tick(manager, section(chunk));
        assertTrue(manager.getTicketsAt(2, 2).isEmpty());
        assertBookkeeping(manager);
    }

    private record ModelTicket(TicketType type, int level, long remaining) {}

    private static long remainingFor(TicketType type) {
        return type.timeout() <= 0L ? PERMANENT : type.timeout();
    }

    private static int minLevel(List<ModelTicket> tickets) {
        int min = ChunkHolderManager.MAX_TICKET_LEVEL + 1;
        for (ModelTicket ticket : tickets) min = Math.min(min, ticket.level());
        return min;
    }

    private static List<ModelTicket> observed(ChunkHolderManager manager, long chunk) {
        List<ModelTicket> ret = new ArrayList<>();
        for (Ticket ticket : manager.getTicketsAt(CoordinateUtils.getChunkX(chunk), CoordinateUtils.getChunkZ(chunk))) {
            ret.add(new ModelTicket(ticket.getType(), ticket.getTicketLevel(), ticket.moonrise$getRemoveDelay()));
        }
        return ret;
    }

    private static final Comparator<ModelTicket> ORDER = Comparator.comparingInt(ModelTicket::level)
        .thenComparingLong(t -> t.type().moonrise$getId());

    @Test
    void randomizedAddsRemovesAndPartialRegionTicksMatchAnIndependentModel() throws Exception {
        Random random = new Random(20260917L);
        Fixture fixture = fixture();
        ChunkHolderManager manager = fixture.manager();
        TicketType[] types = {type("origin:r0", 0L), type("origin:r1", 1L), type("origin:r3", 3L), type("origin:r7", 7L)};
        long[] chunks = {CoordinateUtils.getChunkKey(0, 0), CoordinateUtils.getChunkKey(3, 3), CoordinateUtils.getChunkKey(4, 0),
            CoordinateUtils.getChunkKey(7, 1), CoordinateUtils.getChunkKey(-1, -1), CoordinateUtils.getChunkKey(-4, 3)};
        long[] sections = {section(chunks[0]), section(chunks[2]), section(chunks[4]), section(chunks[5])};
        Map<Long, List<ModelTicket>> model = new HashMap<>();
        for (long chunk : chunks) model.put(chunk, new ArrayList<>());

        for (int step = 0; step < 600; step++) {
            int op = random.nextInt(10);
            if (op < 4) {
                long chunk = chunks[random.nextInt(chunks.length)];
                TicketType type = types[random.nextInt(types.length)];
                int level = 28 + random.nextInt(6);
                List<ModelTicket> list = model.get(chunk);
                boolean fresh = list.removeIf(t -> t.type() == type && t.level() == level) == false;
                list.add(new ModelTicket(type, level, remainingFor(type)));
                assertEquals(fresh, manager.addTicketAtLevel(type, chunk, level, null), "step " + step);
            } else if (op < 6) {
                long chunk = chunks[random.nextInt(chunks.length)];
                List<ModelTicket> list = model.get(chunk);
                List<ModelTicket> removable = list.stream().filter(t -> t.type() != TicketType.UNKNOWN).toList();
                if (removable.isEmpty()) {
                    assertFalse(manager.removeTicketAtLevel(types[0], chunk, 40, null), "step " + step);
                    continue;
                }
                ModelTicket victim = removable.get(random.nextInt(removable.size()));
                int before = minLevel(list);
                list.remove(victim);
                if (minLevel(list) != before) list.add(new ModelTicket(TicketType.UNKNOWN, before, 1L));
                assertTrue(manager.removeTicketAtLevel(victim.type(), chunk, victim.level(), null), "step " + step);
            } else {
                List<Long> owned = new ArrayList<>();
                for (long section : sections) if (random.nextBoolean()) owned.add(section);
                for (long chunk : chunks) {
                    if (!owned.contains(section(chunk))) continue;
                    List<ModelTicket> next = new ArrayList<>();
                    for (ModelTicket ticket : model.get(chunk)) {
                        if (ticket.remaining() == PERMANENT) { next.add(ticket); continue; }
                        long remaining = ticket.remaining() - 1;
                        if (remaining > 0) next.add(new ModelTicket(ticket.type(), ticket.level(), remaining));
                    }
                    model.put(chunk, next);
                }
                tick(manager, owned.stream().mapToLong(Long::longValue).toArray());
            }
            for (long chunk : chunks) {
                List<ModelTicket> expected = new ArrayList<>(model.get(chunk));
                expected.sort(ORDER);
                assertEquals(expected, observed(manager, chunk), "step " + step + " chunk " + chunk);
            }
            assertBookkeeping(manager);
        }
    }
}
