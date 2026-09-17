package io.papermc.paper.block;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.IdMapper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@VanillaFeature
class UniformPaletteStorageTest {
    private static final String EMPTY = "empty", OTHER = "other", THIRD = "third";
    private static Strategy<String> strategy(boolean blocks) {
        IdMapper<String> ids = new IdMapper<>();
        ids.add(EMPTY); ids.add(OTHER); ids.add(THIRD);
        return blocks ? Strategy.createForBlockStates(ids) : Strategy.createForBiomes(ids);
    }
    private static PalettedContainer<String> fresh(Strategy<String> strategy, String value) {
        return new PalettedContainer<>(value, strategy, null);
    }

    @Test
    void zeroStorageWritesStayNoOpsAndSizesStayIndependent() {
        for (int size : new int[]{64, 4096}) {
            ZeroBitStorage storage = new ZeroBitStorage(size);
            assertEquals(size, storage.getSize()); assertEquals(0, storage.getBits());
            assertSame(storage, storage.copy()); assertEquals(0, storage.getRaw().length);
            for (int i = 0; i < size; i++) {
                assertEquals(0, storage.getAndSet(i, 0)); storage.set(i, 0);
                assertEquals(0, storage.get(i));
            }
            int[] values = new int[size + 1]; Arrays.fill(values, 9);
            storage.unpack(values);
            assertEquals(9, values[size]);
            for (int i = 0; i < size; i++) assertEquals(0, values[i]);
            AtomicInteger count = new AtomicInteger();
            storage.getAll(value -> { assertEquals(0, value); count.incrementAndGet(); });
            assertEquals(size, count.get());
            var entries = storage.moonrise$countEntries();
            assertEquals(size, entries.get(0).size());
            entries.clear(); assertEquals(size, storage.moonrise$countEntries().get(0).size());
        }
    }

    @Test
    void independentUniformPalettesAndCopiesResizeWithoutCrossWrites() {
        for (boolean blocks : new boolean[]{false, true}) {
            Strategy<String> strategy = strategy(blocks);
            PalettedContainer<String> first = fresh(strategy, EMPTY), second = fresh(strategy, OTHER);
            PalettedContainer<String> copy = first.copy();
            assertSame(first.data.storage(), second.data.storage(), "Uniform containers reuse only immutable indices");
            assertSame(first.data.storage(), copy.data.storage());
            assertNotSame(first.data.palette(), second.data.palette());
            // Existing SingleValuePalette.copy() returns itself; writes must resize independently.
            first.set(0, 0, 0, THIRD);
            copy.set(1, 0, 0, OTHER);
            assertEquals(THIRD, first.get(0, 0, 0)); assertEquals(EMPTY, first.get(1, 0, 0));
            assertEquals(EMPTY, copy.get(0, 0, 0)); assertEquals(OTHER, copy.get(1, 0, 0));
            for (int i = 0; i < strategy.entryCount(); i++) assertEquals(OTHER, second.get(i));
            assertEquals(0, second.bitsPerEntry());
            AtomicInteger total = new AtomicInteger();
            second.count((value, count) -> { assertEquals(OTHER, value); total.addAndGet(count); });
            assertEquals(strategy.entryCount(), total.get());
        }
    }

    @Test
    void diskUnpackAndPacketReadPreserveDataAndValidation() {
        for (boolean blocks : new boolean[]{false, true}) {
            Strategy<String> strategy = strategy(blocks);
            var packed = new PalettedContainerRO.PackedData<>(List.of(OTHER), Optional.empty(), 0);
            PalettedContainer<String> loaded = PalettedContainer.unpack(strategy, packed, EMPTY, null).getOrThrow();
            PalettedContainer<String> received = fresh(strategy, EMPTY);
            FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
            try {
                loaded.write(bytes, null, 0); received.read(bytes);
                assertEquals(0, bytes.readableBytes());
            } finally { bytes.release(); }
            assertSame(loaded.data.storage(), received.data.storage(), "Disk and fresh construction share the same strategy storage");
            assertEquals(strategy.entryCount(), received.data.storage().getSize());
            assertEquals(OTHER, received.get(0)); assertEquals(OTHER, received.get(strategy.entryCount() - 1));
            loaded.set(0, 0, 0, THIRD); assertEquals(OTHER, received.get(0));
            var invalid = new PalettedContainerRO.PackedData<>(List.of(OTHER), Optional.empty(), 1);
            assertTrue(PalettedContainer.unpack(strategy, invalid, EMPTY, null).error().isPresent());
            var missing = new PalettedContainerRO.PackedData<>(List.of(OTHER, THIRD), Optional.empty(), -1);
            assertTrue(PalettedContainer.unpack(strategy, missing, EMPTY, null).error().isPresent());
        }
    }

    @Test
    void packAndUnpackMixedContentAndPresetGrowthRemainIndependent() {
        Strategy<String> strategy = strategy(true);
        PalettedContainer<String> original = fresh(strategy, EMPTY);
        for (int i = 0; i < 4096; i += 7) original.set(i & 15, i >>> 8, (i >>> 4) & 15, OTHER);
        PalettedContainer<String> loaded = PalettedContainer.unpack(strategy, original.pack(strategy), EMPTY, null).getOrThrow();
        for (int i = 0; i < 4096; i++) assertEquals(original.get(i), loaded.get(i));
        loaded.set(0, 0, 0, THIRD); assertEquals(OTHER, original.get(0));
        var packed = new PalettedContainerRO.PackedData<>(List.of(OTHER), Optional.empty(), 0);
        PalettedContainer<String> presets = PalettedContainer.unpack(strategy, packed, EMPTY, new String[]{THIRD}).getOrThrow();
        PalettedContainer<String> separate = fresh(strategy, EMPTY);
        presets.set(1, 0, 0, THIRD);
        assertEquals(OTHER, presets.get(0)); assertEquals(THIRD, presets.get(1, 0, 0));
        assertEquals(EMPTY, separate.get(1, 0, 0)); assertEquals(0, separate.bitsPerEntry());
    }
}
