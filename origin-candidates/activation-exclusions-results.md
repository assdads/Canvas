# Call-local activation exclusions for overlapping player crowds

## Decision and scope

Retain as an experimental crowded-region CPU candidate, not production-approved.
No live Survival modifications were made. All runs used the isolated Paradox
`survival-test-canvas-offline` container (network none, no published ports).

After an entity passes the existing region-ownership and marker checks and is
active for the current tick, its identity is recorded in a set local to that
`activateEntities` invocation. Subsequent player spatial queries skip these
identities before repeating bounding-box intersection and candidate-list writes.
Entities not activated by an earlier player remain eligible for later players.

There is no cross-tick cache, region-global state, entity-field write from a
foreign region, AI-frequency reduction or activation-range change. Ownership is
checked before adding any entity to the set. The normal query overload delegates
with no exclusions; its existing bounding-box/predicate ordering is preserved.
The optimization still scans entity arrays and adds identity-set overhead. It is
not an algorithm that eliminates all player-by-mob work.

## Benchmark repair (OriginBenchmark 1.8.4)

The previous grouped7 snapshot used one run tag for all herds and players.
Adjacent agent teardown sweeps could remove/count neighboring herds and bots,
then sum intermediate overlapping counts. Instead of discarding that error,
MobPopulator now derives each herd's tag from the unique run tag and its column.
All spawn/adoption/cleanup paths use that ownership tag. The existing UUID
remainder de-duplication remains. Two tests cover signed-coordinate uniqueness
across 1,089 columns and between-run separation.

OriginBenchmark was built with build_plugin, then explicitly tested (the tool's
build command skipped tests). All 24 plugin tests passed. This 1.8.4 build was
installed only as a per-leg overlay on the immutable snapshot. It is a newer
local source build, not claimed byte-identical to grouped7 plus one class.
Every baseline and candidate below uses the SAME driver SHA-256:
`b511f970b493d02f7af9fb8ddca228c9412ed5c43ce2c776716b952e9c9667a8`.
Do not directly compare its numbers against previous grouped7 experiments.

## Eight mixed-crowd runs: ABBA followed by BAAB

64 moving discard-transport bots on adjacent chunk centres, 20 custom farm mobs
per agent (1,280 total), one player-containing target region. Each leg resets the
same world/plugin files. 16 GiB ZGC heap, 20 GiB container cap, six-CPU quota, four
region workers. 30 seconds of separate post-ramp observations, 90 seconds of
census/cgroup/JFR measurements. Ten distinct census timestamps per mixed leg.
These are census means, NOT per-tick p95/p99 or proof of saturation capacity.

| Batch / leg | Build | Mean target-region MSPT | Container CPU cores |
|---|---|---:|---:|
| ABBA A1 | baseline | 23.624 | 2.2291 |
| ABBA B1 | candidate | 22.842 | 2.2134 |
| ABBA B2 | candidate | 23.267 | 2.2256 |
| ABBA A2 | baseline | 23.853 | 2.3736 |
| BAAB B1 | candidate | 21.870 | 2.1657 |
| BAAB A1 | baseline | 24.442 | 2.3283 |
| BAAB A2 | baseline | 24.010 | 2.3792 |
| BAAB B2 | candidate | 22.913 | 2.1528 |

Combined means: **23.98225 -> 22.723 ms (-5.25%)**;
**2.32755 -> 2.18939 CPU cores (-5.94%)**. Every candidate leg's mean MSPT is
lower than every baseline leg's mean in this batch. Improvement is not uniform:
ABBA shows -2.88% MSPT, BAAB -7.57%. One candidate leg records 0.1204 seconds
of cgroup throttling; the others record zero.

First-batch JFR activation inclusive samples:
- A1 369 / 4,233; A2 344 / 4,284 (~8.37% pooled region samples).
- B1 221 / 4,092; B2 240 / 4,173 (~5.58%).
These overlapping sampled shares corroborate reduced activation work, not exact
time or a promise of the same percentage on Origin hardware.

## Single-viewer herd control: no demonstrated gain

Four ABBA legs: one bot and 640 custom mobs, same per-leg reset and instrumentation.

| Leg | Build | Mean target-region MSPT | Container CPU cores |
|---|---|---:|---:|
| A1 | baseline | 4.1356 | 0.5760 |
| B1 | candidate | 4.1422 | 0.5461 |
| B2 | candidate | 4.3011 | 0.5796 |
| A2 | baseline | 4.1044 | 0.5551 |

Means: **4.120 -> 4.2217 ms (+2.47%)**, CPU **0.56554 -> 0.56285 cores
(-0.48%)**. This is not a universal improvement. The set has construction cost
without another nearby player's search to amortize it. More sparse/spread-out
controls and longer runs are required before production promotion.

## Correctness and limitations

- Baseline full build: 519 API entries (2 skipped), 9,298 server entries (23 skipped).
- Final candidate full `test createPaperclipJar`: 519 API entries (2 skipped),
  9,301 server entries (23 skipped), zero failures/errors. Final candidate JAR
  hash exactly matches the one used for all measurements.
- Six focused real-method tests: ordinary query order and predicate behavior;
  exclusion without reading excluded bounds; 300 random exclusion/bounds cases;
  spectators/markers and region-ownership guards; reentrant activation storage;
  later-player activation of an out-of-range entity, immunity and next-tick reset.
- All 12 included legs report zero scripted-action failures, cleanupRemaining=0,
  idle benchmark after teardown, and no plugin-enable/linkage exceptions.
- Plugin fingerprints matched across all legs. Config fingerprints match within
  each population. Mixed-crowd census population is exactly 64 players/1,280
  custom mobs, 731 total loaded chunks and 852 tile entities; dropped items varied
  (55-86), so simulation state is not deterministic despite identical reset.
- The vanilla `tag @e list` RCON attempt returned EMPTY OUTPUT. It cannot count as
  a verified independent zero-survivor check. The completed cleanup gate is the
  benchmark's owner-scoped remainder, not proof about all untagged model children.
  No existing failing remainder was waived. The discarded mutable filesystem is
  reset between legs and after experiments. Do not repeat the earlier interim
  claim that the empty response independently proved no tags.
- Discard bots run embedded plugin packet handlers but do not certify real TCP
  throughput, client-visible tracking correctness, or cross-region handoffs.
- Existing ticket/vine/collision/layout/lazy-list candidates are held equal in
  both builds; only activation exclusions vary. They are not individually
  re-certified here.

## Artifacts and remaining gate

- Baseline: `422af1b10f7c5bc1ec84d9b1fc62d036d48e935bb302479a208a3bbb19787377`.
- Candidate: `5f2a64582d7af9ce67c8b02fadd49f5d7341603e7e74fe5d3c70d746c514369d`.
- Local: hosting `scratch/canvas-activation-skip/` (builds/tests, exact series,
  fingerprints, summaries, JFR caller output, restoration proofs).
- Remote under the existing snapshot: `activation-skip-results/`,
  `activation-skip-repeat-results/`, `activation-skip-herd-results/`.
- Restored original launcher: `12ef42d0ae98f9211d2c7887fed73218770f43838c815fbae28ba884681188fc`;
  test container independently confirmed stopped/network none.

OriginBenchmark 1.8.4 is journalled. The estate-wide `verify.mjs` exits **2**:
its deep, journal and audit-test tool-health suites fail, plus estate findings.
Do not claim that gate passed or promote the plugin release while tooling is
untrustworthy. Repairing unrelated estate tooling is outside this experiment.
No production deployment or engine hot-swap was performed.
