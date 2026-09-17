# Lazy per-section random-tick list wrappers

## Change

`LevelChunkSection` now allocates its `ShortList` only when a ticking-block change,
recount of ticking content, or explicit `moonrise$getTickingBlockList()` call needs
it. Ordinary block reads, non-ticking changes and empty recounts do not allocate it.
This follows the previous lazy membership-map optimization, which is present in
both builds here. It removes another layer of empty per-section objects.

The public getter remains non-null and mutable. Once materialized, a list retains
its identity across clears/recounts; callers holding the returned reference see
subsequent changes. No shared mutable empty singleton, nullable public return,
pooling or removal of membership indexing is introduced. Lists are never freed
on becoming empty, avoiding repeated allocation and stale retained references.

No random-tick frequency, order, RNG consumption, block counting, world format or
chunk load/unload scheduling is changed. Section-copy behavior is left as it was;
this is not a copy-constructor repair. Region/worldgen ownership requirements
remain in force. The getter may now initialize state, so it must be used in the
section's owning execution context, not as an off-thread read API.

## Tests and builds

Full baseline and candidate `test createPaperclipJar` builds passed on Java 25:
519 API test entries (2 skipped), 9,295 server entries (23 skipped), no failures
or errors. Four new tests invoke actual section methods:

- Non-null mutable getter, stable identity and isolation between sections.
- Empty-to-ticking transitions and exact swap-removal order.
- Direct palette writes followed by recount, 4,096 entries, independent mutable
  copies after recount, and return to empty without replacing an exposed list.
- Ordinary empty reads, repeated recounts and non-ticking block changes. The
  candidate additionally asserts that the private list remains absent until used.

The existing random-tick traversal and ShortList operation tests also passed.
The patch is stored in the canonical Minecraft source-patch layer; generated
Minecraft sources are not committed.

## Isolated live ABBA comparison

Target: `survival-test-canvas-offline` on Paradox, network `none`, no published ports.
16 GiB maximum ZGC heap, 20 GiB container memory cap, six-CPU quota. Every leg
restored the same `vine-reset` filesystem before starting. All plugin JAR hashes
and benchmark-config hashes matched.

Workload: 100 moving server-side bots and 2,000 spawned custom farm mobs, without
real-client packet transport. Thirty seconds of separate post-ramp observations
preceded ninety seconds of timed CPU/census/JFR data. Moving bots can continue
loading chunks; this is not a claim of fully settled gameplay.

Live `GC.class_histogram` was run after timed sampling and profiling because it
induces collection. Its total is the sum of live shallow object sizes, not RSS or
committed heap. CPU comes from container CPU-time deltas over the timed interval.

| Leg | Build | Live-object bytes after GC | ShortList objects | Section objects | Mean hottest-region census MSPT | CPU cores used |
|---|---|---:|---:|---:|---:|---:|
| A1 | baseline | 5,076,207,960 | 2,478,350 | 2,478,350 | 1.533 | 2.180 |
| B1 | candidate | 4,999,577,608 | 66,822 | 2,478,350 | 1.501 | 2.115 |
| B2 | candidate | 4,999,905,688 | 66,814 | 2,478,350 | 1.488 | 2.147 |
| A2 | baseline | 5,076,955,240 | 2,478,350 | 2,478,350 | 1.450 | 2.105 |

Averages:

- Live heap: **5,076,581,600 -> 4,999,741,648 B**, saving **76,839,952 B
  (73.28 MiB, 1.51%)**, on top of the lazy-map baseline.
- ShortList objects: **2,478,350 -> 66,818**, about 97.30% fewer. Their shallow
  size falls from 79,307,200 to 2,138,176 B. The approximately 77.17 MB object
  reduction corroborates the approximately 76.84 MB whole-heap difference;
  other live objects vary slightly between legs.
- Section count is identical in all four legs. Membership-map counts remain
  approximately 66,818, as expected: this patch removes wrappers, not active maps.
- CPU: **2.1422 -> 2.1308 cores (-0.53%)**, inconclusive; no observed quota
  throttling during the timed intervals.
- Hottest-region census mean: **1.4915 -> 1.4945 ms (+0.20%)**, inconclusive.
  These are census measurements, not per-tick p95/p99 or saturation results.
- Container memory remained roughly 17.2–18.7 GB. Reduced live heap does not imply
  an equal immediate RSS or committed-memory reduction under ZGC.

All four legs reported zero scripted-action failures, `cleanupRemaining=0`, and
no plugin-enable or linkage failures. No leg needed exclusion or a relaxed gate.
The reset filesystem was restored and its original launcher hash verified;
container stopped with network `none`. Production Survival was not modified.

## Decision and evidence

Retain as an experimental **RAM-only** optimization. Saving scales with sections
that never need membership lists. There is no demonstrated CPU or MSPT benefit
and no production rollout from this experiment. Zero-bit storage sharing and
active-section indexing remain separate, unimplemented candidates.

- Baseline launcher SHA-256: `2de34c80033cd50a65fa758e7c506f15ff1e5f1fe6cb980db4dcc5fc2255bfac`.
- Candidate launcher SHA-256: `dcb08a64c63bbd2a0b1eb2cce77e1f94a83f4ef8f7cd724edde0af3e062386a2`.
- Both builds contain the earlier vine, collision, random-tick layout, lazy-map
  changes and the separately staged ticket candidate; those were held equal.
- Private remote evidence: `section-list-heavy-results/{a1,b1,b2,a2}/` under
  `/var/lib/originworks/benchmarks/survival-20260915T221720Z/`.
- Local evidence: hosting `scratch/canvas-section-lists/`, including exact live
  histograms, per-leg fingerprints, CPU counters, census series, build reports,
  canonical patch and restoration proof.
