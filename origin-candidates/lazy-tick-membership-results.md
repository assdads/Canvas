# Lazy random-tick membership storage

## Change and scope

`ShortList` previously constructed a `Short2ShortOpenHashMap` (including two backing
arrays) for every instance. Every `LevelChunkSection` owns one such list, including
sections which never contain random-ticking blocks. The test world has a large
section count, so eager empty maps were a material retained-heap cost.

The candidate initializes the same map only on first insertion or a positive
`setMinCapacity` request. Removing from a never-initialized list returns false;
clearing it is a no-op. Once allocated, the map and capacity remain reusable just
as before. No pooling, synchronization, global cache, tick skipping, world format
change, altered RNG or gameplay change is introduced. Existing region/worldgen
ownership requirements still apply; `ShortList` does not become thread-safe.

This targets only the shared collection helper. It leaves section traversal,
chunk load/unload, the section array and active membership indexing intact.
The earlier ticket, vine, collision and random-tick layout changes were identical
in both builds. The ticket patch remains staged separately and is not part of
this commit.

## Correctness and build

Baseline and candidate each passed `test createPaperclipJar` on Java 25:
519 API tests (2 skipped), 9,291 server test entries (23 skipped), zero failures
or errors. Three new tests execute in the vanilla-registry suite:

- 40,000 deterministic mixed insert/remove/clear/reserve operations checked against
  a reference list, including duplicate insertion and exact swap-removal order.
- Dense 4,096-entry add/remove and signed-short boundary values.
- Actual `LevelChunkSection` transitions between air, grass, leaves and stone,
  repeated recounts, empty-to-active and active-to-empty cycles.

A thread-allocation counter diagnostic, with objects retained in an array, measured
empty construction at **264 B/list baseline vs 24 B/list candidate** on the local
JDK. This is not a timing result or a guarantee of the same object layout on other
JVMs. Runtime object sizes differ, so live heap evidence is reported separately.

## Isolated live ABBA comparison

Target: existing `survival-test-canvas-offline` on Paradox, network `none`, no
published ports. 16 GiB maximum ZGC heap, 20 GiB Docker memory limit, six-CPU quota.
Every leg restored `vine-reset` before boot. Plugin JAR and benchmark-config hashes
matched. Workload: **100 moving server-side bots, 2,000 spawned custom farm mobs**.
No real client/network transport was exercised.

Measurement: 30 seconds of separate post-ramp observations, then 90 seconds of
census/JFR data. Continuing movement may still stream chunks; this is not proof of
a completely settled world. Container `cpu.stat` deltas measure consumed CPU over
the timed interval. A **live `GC.class_histogram` was taken only after timing and
profiling**, since the heap census itself induces GC and must not pollute timed
MSPT/CPU results. The histogram sums shallow sizes of all live objects; it is not
RSS, committed heap or exact retained size for one object graph.

| Leg | Build | Live-object bytes after GC | Membership maps | Chunk sections | Mean hottest-region census MSPT | CPU cores used |
|---|---|---:|---:|---:|---:|---:|
| A1 | baseline | 5,694,853,376 | 2,478,350 | 2,478,350 | 1.528 | 2.199 |
| B1 | candidate | 5,076,175,048 | 66,807 | 2,478,350 | 1.506 | 2.156 |
| B2 | candidate | 5,077,582,992 | 66,814 | 2,478,350 | 1.537 | 2.120 |
| A2 | baseline | 5,693,899,544 | 2,478,350 | 2,478,350 | 1.480 | 2.138 |

Averages:

- Live heap: **5,694,376,460 B -> 5,076,879,020 B**, saving **617,497,440 B
  (588.89 MiB, 10.84%)**. Object counts and eliminated map/short-array sizes support
  this result; it is not based on noisy allocation-sample weights.
- Membership maps: 2,478,350 -> about 66,811 (**97.30% fewer**).
- Map shallow sizes: 198,268,000 B -> about 5,344,840 B. Short-array totals:
  about 551.53 MB -> 127.08 MB. Short arrays also serve other callers, so this is
  corroborating whole-heap evidence, not an exact dominator analysis.
- CPU: 2.1682 -> 2.1379 average cores (**1.40% lower**), too small and inconsistent
  across run order to establish a speedup. No cgroup throttling during sampling.
- Hottest-region census: 1.5040 -> 1.5215 ms (**1.16% higher**), also inconclusive.
  These are census means, not individual-tick p95/p99.
- Docker memory usage remained around 17-19 GB during timing because committed
  ZGC heap, cache and other memory do not instantly shrink with fewer live objects.
  Do not describe the 589 MiB live-heap result as an equal immediate RSS saving.

## Excluded larger-load attempt

The first baseline attempt used 150 moving bots and 3,000 spawned mobs. It reached
full load and reported zero action failures, but ended with `cleanupRemaining=9`.
The runner rejected it, stopped the test container, preserved its files under
`a1-cleanup-failed`, then reset from the frozen clean state. It is **excluded** from
all A/B statistics and no claim of clean teardown is made for it. No benchmark
plugin patch or relaxed cleanup assertion was used to make it pass.

All four included legs had zero scripted-action failures, `cleanupRemaining=0`,
and no plugin-enable or linkage failures. The test filesystem was restored from
`vine-reset`, original launcher hash verified, and container left stopped/network
none. Production Survival was neither restarted nor edited.

## Decision and artifacts

Retain as an **experimental memory optimization**. It has a repeatable local and
live heap saving for this tall-world workload, but no demonstrated CPU/MSPT gain
and no production deployment approval. Savings depend on loaded section counts
and how many sections ever acquire ticking-block membership. Initialized maps
are intentionally not discarded after clear, avoiding allocation churn.

- Baseline launcher SHA-256: `115253018492268aabe3279ca692ec8d7c9bd8cd3112ff7e713906247991dd33`.
- Candidate launcher SHA-256: `ffff90963528dfb176cc47584878048c3cbe0d69d19d3edf745656efd186b384`.
- Private remote evidence: `shortlist-heavy-results/{a1,b1,b2,a2}/` under the
  existing benchmark root, including heap histograms, JFR, census and CPU counters.
- Local evidence: hosting `scratch/canvas-shortlist/` build logs, exact manifests,
  per-leg proofs, raw census series, summary and restoration record.
