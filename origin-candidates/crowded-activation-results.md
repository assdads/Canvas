# Crowded-region activation allocation experiment: not retained

## Scope and decision

Resumed the clustered CPU investigation using the isolated Paradox container
`survival-test-canvas-offline`. Production Survival was not accessed or changed.

Tested reusing an ArrayList's capacity across players inside one invocation of
`ActivationRange.activateEntities`. The candidate clears the list before each
spatial query. It keeps every spatial query, candidate order, spectator/marker
filter, ownership check and activation call. No state is shared across invocations,
regions or ticks. This does not deduplicate activation searches or skip gameplay.

The player-only comparison is inconclusive. No runtime patch is retained.
Only the two real-method regression tests and these findings are committed.

## Correctness

Baseline, candidate and restored original code passed `test createPaperclipJar`
on Java 25: 519 API test entries (2 skipped), 9,297 server entries (23 skipped),
zero failures/errors. Focused tests execute in the vanilla-registry suite:

- Spatial output starts empty for every player and on the next invocation.
- Spectators do not trigger queries; owned entities activate while foreign-region
  entities and disabled markers are skipped. Ownership calls remain in place.
- Re-entrant activation cannot clear or overwrite its caller's temporary list.

The tests use mocked world/tick context, not off-thread access to a live server.
Initial fixture construction errors were corrected before either measured build.

## Clean controls

The earlier 64-player/1,280-custom-mob diagnostic reached one target region near
24 ms, but reported cleanupRemaining=5236. It remains failed and excluded.

Inspection of the frozen OriginBenchmark-1.8.3.jar (metadata grouped7) confirms
that RampController sums each agent's integer remainder sequentially. Each
MobPopulator sweeps tagged entities at 48 blocks and counts them at 64 blocks.
Adjacent agents have overlapping observation areas and a shared run tag. Those
intermediate counts can include later agents' still-active mobs and can be counted
repeatedly. The value is not a unique final survivor count. This does not prove
that all entities eventually disappeared, so the cleanup assertion was NOT waived.
The failed capture was preserved and its runtime restored from the frozen reset.
The newer local benchmark source is not identical to this frozen JAR.

Separate controls retain the frozen plugin binaries and avoid cross-agent mob
ownership overlap:

| Control | Scripted population | Mean test-region census MSPT | Timed container CPU cores |
|---|---|---:|---:|
| Player crowd | 64 moving bots, no injected mobs | 2.1522 | 0.1683 |
| Single-owner herd | 1 moving bot, 640 custom farm mobs | 4.1122 | 0.5718 |

Both held exactly one player-containing testingworld region, reported zero action
failures and cleanupRemaining=0. Existing background entities are not removed.
An initial herd attempt hit a cached pre-player census; it is retained as
`a1-census-failed` and excluded. The sampler now waits, bounded at 30 seconds, for
an initial non-partial player census before sampling. It still fails if the
player region disappears during measurement.

These different populations cannot be subtracted to calculate a per-player or
per-mob cost; locations and AI/viewer interactions differ.

## Matched 64-player crowd ABBA

Each leg restored the same frozen files, used the same 70 plugin JAR hashes and
benchmark config, and held 64 moving bots in one target region. No injected mobs.
16 GiB ZGC heap, 20 GiB container limit, six CPU quota, four region workers.
Discard transport still traverses an embedded packet pipeline and plugin handlers;
it does NOT measure physical network throughput or real client rendering.

| Leg | Build | Mean region census MSPT | Container CPU cores |
|---|---|---:|---:|
| A1 | Original | 2.3022 | 0.1845 |
| B1 | Candidate | 2.2789 | 0.1842 |
| B2 | Candidate | 2.1933 | 0.1664 |
| A2 | Original | 2.2156 | 0.1821 |

Average region census: 2.2589 -> 2.2361 ms (-1.01%). CPU: 0.18333 ->
0.17528 cores (-4.39%, just 0.00805 cores). The CPU difference is largely the
second candidate leg; normal run variation is not separated from a patch effect.
No cgroup throttling was recorded. No CPU/MSPT improvement is established.

All four legs had zero action failures, complete cleanup and no enable/linkage
exceptions. Censuses were deduplicated by timestamp, yielding nine per leg. These
are not individual-tick p95/p99 latencies. The heap histogram happened after timed
CPU/JFR sampling; no heap or exact allocation claim is made for this candidate.

## More relevant CPU callers

Read the saved mixed-crowd diagnostic with Java's streaming JFR consumer. Of 4,344
region execution samples, inclusive counts included:

- ChunkMap.newTrackerTick: 1,417 (32.6%).
- Connection.send: 1,124 (25.9%).
- OriginHologramEngine ViewerConnection.write: 1,018; subsequent Cosmetics, Tab,
  Blocks, GUI and BetterModel handlers occur on the same nested packet stacks.
- TrackedEntity.updatePlayer: 596 (13.7%).
- ActivationRange.activateEntities: 399 (9.2%).
- TemptGoal.canUse: 351 (8.1%), including scans of local players and the custom
  OriginMobsEngine item predicate before targeting-distance rejection.

These shares overlap and must not be added. Method presence does not attribute
all inclusive time to that plugin. Hash-map membership frames resolve partly to
TickThread ownership checks, not just viewer-set lookups; do not remove guards.

The controls corroborate the distinction: the single-viewer herd is dominated by
entity ticking/movement; the player crowd spends more time in tracking and the
embedded packet pipeline. Before a mixed-crowd A/B, resolve its final-cleanup
observation problem while keeping final survivor checks strict. A farther-player
prefilter cannot be added casually to generic TargetingConditions: selectors may
have side effects, so changing their evaluation order changes behavior.

## Artifacts and final state

- Baseline SHA-256: 89ef8bddd52b076b17e899c03004e985426456f75b26aeccffddfc3a7f9e3250.
- Candidate SHA-256: 7acafb0ec9926cd2246236f2473dc06718a20d9fb480a40f3e7b821df3f87dbe.
- Local evidence: hosting `scratch/canvas-activation/` (build/test proofs, rejected
  diff, six-run summaries, fingerprints, series, cgroup counters, restoration).
- Caller/cleanup evidence: hosting `scratch/canvas-cluster/{callers.txt,
  control-callers.txt,cleanup-bytecode.txt}`.
- Private host: `activation-crowd-results/`, `cluster-control-results/`,
  `cluster-herd-results/` inside the existing benchmark snapshot directory.

After all tests, the isolated runtime was restored from vine-reset. Launcher hash
12ef42d0ae98f9211d2c7887fed73218770f43838c815fbae28ba884681188fc verified.
Container stopped, network none. The candidate was removed from local runtime
source and regenerated patch set; restored source passed the full build/tests.
Existing experimental patches stayed equal in both builds and were not bundled
into this tests/results-only commit.
