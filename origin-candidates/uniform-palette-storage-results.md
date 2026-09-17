# Shared zero-bit storage for scattered-player chunk memory

## Scope and decision

This experiment follows the request to prioritize scattered-player chunk cost,
not crowd tracking. `PalettedContainer` previously allocated one `ZeroBitStorage`
for each uniform block/biome container. It now obtains immutable zero indices from
its `Strategy`, which owns one final storage instance with the correct entry count.
Both fresh construction and disk unpack use it. Block palettes, biome palettes,
non-zero storage, copy semantics, validation and thread ownership are unchanged.
The existing `ZeroBitStorage.copy()` already returns itself; its writes are no-ops,
its size is final and its shared raw array is empty. No global map, object pool,
shared mutable palette or change to chunk loading/random-tick frequency is added.

Retain as an experimental RAM optimization. No CPU or MSPT gain is established.
Production Survival was not accessed, restarted or modified.

## Correctness and build

Baseline and candidate passed full Java 25 `test createPaperclipJar` builds:
519 API test entries (2 skipped), 9,305 server entries (23 skipped), no failures/errors.
Four new real-method tests cover:

- Zero reads/writes, size-specific iteration/unpack and independent entry-count maps.
- Uniform containers and copies transitioning to writable multi-value palettes
  without changing other containers; candidate verifies shared storage identity.
- Packet read/write and disk unpack for both 64-entry biome and 4096-entry block
  strategies, including malformed bit counts and missing non-zero data rejection.
- Mixed-content disk round trips and anti-xray preset-triggered palette growth.

An initial test incorrectly assumed the upstream SingleValuePalette.copy() creates
another palette; upstream already returns itself. Removed that identity assumption
before baseline testing, retaining assertions of independent values after resize.
This optimization does not repair or alter upstream palette-copy semantics.
NMS ownership policy remains in force. Tests use standalone containers, not
asynchronous access to a live world.

## Chunk-focused live comparison

Isolated `survival-test-canvas-offline` on Paradox, network none, no published
ports. Same 16 GiB heap, 20 GiB memory cap, six CPU quota, four region workers and
two chunk workers in each leg. ABBA, with the immutable `vine-reset` filesystem
restored before every leg. OriginBenchmark 1.8.4 is installed identically in each
leg; all 70 plugin hashes and benchmark config hashes match.

Workload: **100 moving bots spread by seeded RTP, zero injected mobs**.
Scenario `canvas-memory-100` inherits the 13,000-block RTP radius and 64-block
movement radius. Production view/simulation settings are not lowered. Existing
world entities remain (seven animals plus varying dropped items/background entities).
All legs reported 100 players, peak target regions 84; final whole-server census
had 86 regions and 17,199–17,205 chunks. This isolates the broad world footprint
better than the earlier 100-player/2,000-mob workload but is not entity-free.

Thirty seconds of post-ramp observations precede 90 seconds of timed CPU/JFR/census.
Bots continue moving: this is not proof of entirely settled chunk loading. Heap
histograms induce GC only after timing/profiling; they count live shallow object
bytes, not RSS. Census timestamps are deduplicated (9 measurements per leg), not
individual-tick p95/p99. Transport is discard; physical network cost is absent.

| Leg | Build | Live-object bytes after GC | ZeroBitStorage objects | Mean hottest-region census MSPT | CPU cores consumed |
|---|---|---:|---:|---:|---:|
| A1 | Baseline | 4,722,797,720 | 4,201,782 | 0.9033 | 0.6656 |
| B1 | Candidate | 4,653,843,784 | 9 | 0.9144 | 0.6461 |
| B2 | Candidate | 4,658,732,400 | 9 | 0.9144 | 0.6306 |
| A2 | Baseline | 4,725,499,800 | 4,201,779 | 0.9122 | 0.6718 |

- Average live heap: **4,724,148,760 -> 4,656,288,092 B**, saving **67,860,668 B
  (64.72 MiB, 1.44%)** beyond existing lazy list/map optimizations held equal.
- ZeroBitStorage shallow bytes: approximately 67,228,488 -> 144 B. This directly
  corroborates the memory result; section count (2,478,350) and palette-container
  count (4,956,701) are identical in all four heap censuses.
- CPU: 0.66871 -> 0.63833 cores (-4.54%), only ~0.0304 cores. Four short runs,
  different dropped items and unsynchronized background work do not establish a
  repeatable CPU improvement. No quota throttling was recorded.
- Hottest-region census: 0.90778 -> 0.91444 ms (+0.73%). Essentially unchanged;
  no claim of lower region latency or saturation capacity.
- Minimum sampled region TPS: 19.70 or higher. ZGC may retain committed memory;
  the live-heap saving is not a promise of equal immediate OS memory savings.

## What the scattered profile shows

Streaming JFR analysis of the two baseline captures, inclusive region sample shares:

| Path | A1 | A2 |
|---|---:|---:|
| ServerChunkCache.tick | 66.11% | 67.37% |
| ServerLevel.optimiseRandomTick | 30.34% | 29.96% |
| ChunkHolderManager.tick | 15.13% | 16.35% |

These shares overlap (the chunk-cache path contains the others). They are sampled
CPU attribution, not wall time or wasted-work estimates. The result supports
prioritizing random-tick section scanning and ticket maintenance for this workload.
It does not justify disabling random ticks or removing ownership guards. Sharing
immutable storage addresses RAM; a CPU rewrite remains a separate experiment.

## Verification and artifacts

All four legs reached 100 bots / zero injected mobs, had zero scripted-action
failures, reported cleanupRemaining=0, returned idle and had no enable/linkage
exceptions. Cleanup is benchmark accounting, not an independent full-world entity
audit. Final reset restored launcher
`12ef42d0ae98f9211d2c7887fed73218770f43838c815fbae28ba884681188fc`;
container stopped, network none. No production changes.

- Baseline launcher: `c7a604e64fbb3d61d44695f0e86c614d30a2d58527846d7cf4dbc8641ccb738d`.
- Candidate launcher: `ac82a794db0184551f7db21f0506cc1af1f81f1fdb05e429d2869ab29bc43ef2`.
- Both contain all earlier integration-branch changes and the separately staged
  ticket candidate. Only strategy-owned zero storage differs at runtime.
- Local evidence: hosting `scratch/canvas-zero-storage/` including build reports,
  raw live histograms, fingerprints, series, cgroup counters and restoration.
- Remote evidence: `zero-storage-spread-results/{a1,b1,b2,a2}` under the existing
  `/var/lib/originworks/benchmarks/survival-20260915T221720Z` directory.

No automatic production rollout is authorized by this result. Long-running
chunk load/unload and pack/plugin interaction coverage remains a promotion gate.
