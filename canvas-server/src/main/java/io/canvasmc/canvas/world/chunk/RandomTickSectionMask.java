package io.canvasmc.canvas.world.chunk;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

/**
 * OriginWorks: helpers for the per-chunk bitmap of sections whose block counts report
 * random-ticking blocks. The words and the rescan countdown live directly on {@code LevelChunk}
 * so the random-tick pass touches the chunk, the words and the flagged sections, not every section
 * object. Worlds here run 41 to 73 sections per chunk, so the bitmap is a {@code long[]} sized
 * from the chunk's section count on the first pass.
 *
 * <p>Contract, read or written only by the region thread that owns the chunk:
 * <ul>
 *   <li>Bit {@code i} is exact for every write that goes through {@code LevelChunk.setBlockState},
 *       which calls {@link #update(long[], int, LevelChunkSection)} after the section write. A
 *       callback that activates or clears a later section that way is observed in the same pass,
 *       because the pass re-reads the live words after each visited section.</li>
 *   <li>A section mutated or replaced behind the chunk (direct {@code LevelChunkSection} writes,
 *       array entries replaced by plugins) is picked up by the full rescan the chunk performs at
 *       least every {@link #RESCAN_TICKS} passes. Until then that section may be skipped; it is
 *       never ticked twice. A section whose bit is set must still be checked live by the caller,
 *       so a stale set bit never ticks an inactive section and the visit order stays ascending.</li>
 * </ul>
 */
public final class RandomTickSectionMask {
    /** Full rescans happen at least this often, measured in random-tick passes of the chunk. */
    public static final int RESCAN_TICKS = Math.max(1, Integer.getInteger("origin.randomTickSectionRescanTicks", 20));

    private RandomTickSectionMask() {}

    public static int wordCount(final int sectionCount) {
        return (sectionCount + Long.SIZE - 1) >>> 6;
    }

    /** Rebuilds the words from every section's own counts; reuses {@code existing} when it is already sized. */
    public static long[] rescan(final long @Nullable [] existing, final LevelChunkSection[] sections) {
        final int wordCount = wordCount(sections.length);
        final long[] words = existing != null && existing.length == wordCount ? existing : new long[wordCount];
        for (int word = 0; word < wordCount; ++word) {
            final int base = word << 6;
            final int end = Math.min(base + Long.SIZE, sections.length);
            long bits = 0L;
            for (int i = base; i < end; ++i) {
                final LevelChunkSection section = sections[i];
                if (section != null && section.isRandomlyTickingBlocks()) {
                    bits |= 1L << (i - base);
                }
            }
            words[word] = bits;
        }
        return words;
    }

    /** Records the current state of one section after it was written through the chunk. */
    public static void update(final long @Nullable [] words, final int sectionIndex, final LevelChunkSection section) {
        final int word = sectionIndex >>> 6;
        if (words == null || sectionIndex < 0 || word >= words.length) {
            return; // before the first rescan, or an index the chunk cannot hold: the rescan is authoritative
        }
        final long bit = 1L << (sectionIndex & 63);
        if (section.isRandomlyTickingBlocks()) {
            words[word] |= bit;
        } else {
            words[word] &= ~bit;
        }
    }

    /** Bits of {@code word} strictly above bit {@code bit}; ascending iteration helper. */
    public static long remainingAfter(final long word, final int bit) {
        return bit >= Long.SIZE - 1 ? 0L : word & (-1L << (bit + 1));
    }
}
