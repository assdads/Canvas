# Grass light read through the held chunk

## Decision

Retained as an experimental fork change. Eight reset-isolated legs on the scattered-player
workload (ABBA, then BAAB): mean container CPU **0.643 -> 0.595 cores (-7.5%)**, every candidate
leg below every baseline leg; mean hottest-region census MSPT **0.872 -> 0.829 ms (-4.9%)**, but
the MSPT legs overlap (candidate legs 0.811-0.844 ms, baseline legs 0.832-0.920 ms), so the MSPT
figure is a direction, not a clean separation. Live heap unchanged (+0.02%).

The profile shows the mechanism exactly where the change was made and nowhere else:

| Share of region JFR samples (mean of 4 legs each) | Baseline | Candidate |
|---|---:|---:|
| `StarLightInterface#getRawBrightness` | 4.26% | **1.14%** |
| `StarLightInterface#getAnyChunkNow` | 2.21% | **0.48%** |
| `ChunkHolderManager#getChunkHolder` | 1.90% | **0.77%** |
| `SpreadingSnowyBlock#randomTick` | 8.59% | **6.11%** |
| `BlockStateBase#randomTick` (all block callbacks) | 9.82% | 7.29% |
| `ServerLevel#origin$tickRandomSection` (per-section body) | 15.07% | 12.80% |
| `ChunkHolderManager#tick` (unrelated control) | 14.96% | 15.41% |

The remaining `getAnyChunkNow` share (0.48%) is other light readers, not grass. The -7.5% CPU
figure is larger than the removed lookup's own share (about 2.2-2.3 points of region samples);
part of the difference is run-to-run variance and the two later baseline legs ("a2" in both
sets) running hotter than their partners. Treat the confirmed saving as "the grass tick's chunk
lookup and its hash-table probe are gone", not as a guaranteed 7.5% on production.

This is a CPU result on the isolated Paradox clone with server-side bots and discarded network
transport, on a world whose random-tick load is dominated by grass. It is not a per-tick
p95/p99 measurement and not a production figure. A world with less exposed grass will see less.

## What changed

`SpreadingSnowyBlock.randomTick` (grass and mycelium) already looks the ticking chunk up once
(`level.getChunkIfLoaded(pos)`, the existing Paper/Canvas optimisation) and then asked
`level.getMaxLocalRawBrightness(pos.above())` for the light check. That call went
`LevelReader -> Level.getRawBrightness -> LevelLightEngine -> StarLightInterface.getRawBrightness`,
whose first line is `getAnyChunkNow(x >> 4, z >> 4)`: a second concurrent chunk-table lookup for
the chunk the tick is standing in (`pos.above()` shares the column, so it is the same chunk).

- `StarLightInterface.getRawBrightness(BlockPos, int, ChunkAccess)` is a new overload holding the
  original body; the two-argument method now calls it after the same `getAnyChunkNow`. `null`
  reads as an unloaded chunk exactly as before.
- `LevelLightEngine.getRawBrightness(BlockPos, int, ChunkAccess)` forwards to it.
- `SpreadingSnowyBlock.origin$maxLocalRawBrightness(level, chunk, pos)` reproduces
  `LevelReader.getMaxLocalRawBrightness(pos)` (the +/-30,000,000 bounds rule returns 15, otherwise
  the light read with `level.getSkyDarken()`) and passes the held `LevelChunk`.

Equivalence: the lookup path returns the chunk holder's current chunk. For a chunk that is being
randomly ticked that is the full `LevelChunk` the tick already holds; if it were an
`ImposterProtoChunk`, every field the light read touches (`isLightCorrect`, `getPersistedStatus`,
the Starlight sky/block nibbles and emptiness maps) delegates to the wrapped `LevelChunk`, so the
value is the same either way. The `disableGrassLightChecks` short-circuit, the >= 9 threshold, the
spread loop, the RNG draws and every event call are untouched. No cache, no shared state, no
change in what ticks or how often.

## Verification

- Full Canvas build: canvas-api 519 tests, canvas-server 9,319 tests, 0 failures
  (`scratch/canvas-light-hint/candidate-build.json`, jar `1303db61...`).
- `GrassLightReuseTest` (3 tests, run against the real `StarLightInterface` with populated nibble
  arrays and a real `SpreadingSnowyBlock.randomTick` via mocks for the level):
  - `heldChunkReadComposesSkyBlockAndDarkeningExactlyLikeTheLookupPath` - for every sky darkening
    0..15 and several sky/block nibble values, the held-chunk read equals the lookup path, on a
    light-correct chunk and on one whose light is not yet correct.
  - `grassTickReadsLightThroughTheChunkItAlreadyHoldsAndKeepsTheNineThreshold` - the grass tick
    reads light through the chunk it already looked up (no second chunk lookup on the level), and
    spreads at brightness 9 but not at 8.
  - `outOfBoundsPositionsAndDisabledLightChecksSkipTheLightReadAsBefore` - the bounds rule returns
    15 without touching the light engine; `disableGrassLightChecks` skips the read entirely.
- Eight live legs, all `PASS`: zero scripted-action failures, `cleanupRemaining=0`, plugin JAR
  hashes unchanged, min sampled region TPS >= 19.75, no host throttling. The first attempt at
  the fourth ABBA leg (`a2`) died on an RCON read timeout after its observation window; its
  partial directory is kept as `light-hint-spread-results/a2-incomplete-rcon-timeout` and is not
  counted. The leg was rerun from the frozen reset.

## Workload

Same scattered workload as the ticket-lookup and section-bitmap experiments: 100 server-side
moving bots, no injected mobs, `canvas-memory-100` scenario (extends `region-spread-500`),
about 17,200 loaded chunks across 86 ticking regions, 300 s observation after a 10 s settle,
frozen world and plugin set (`vine-reset`) rsynced back before every leg. Baseline jar is the
section-bitmap candidate (`51e86503...`), so this measures the light change alone on top of the
fork's other retained patches.

Legs (hottest-region census MSPT / container CPU cores):

| Set | Leg | Build | MSPT | Cores |
|---|---|---|---:|---:|
| ABBA | a1 | baseline | 0.836 | 0.632 |
| ABBA | b1 | candidate | 0.821 | 0.602 |
| ABBA | b2 | candidate | 0.811 | 0.601 |
| ABBA | a2 | baseline | 0.901 | 0.693 |
| BAAB | b1 | candidate | 0.840 | 0.579 |
| BAAB | a1 | baseline | 0.832 | 0.625 |
| BAAB | a2 | baseline | 0.920 | 0.622 |
| BAAB | b2 | candidate | 0.844 | 0.597 |

## Evidence

On the Paradox clone under `/var/lib/originworks/benchmarks/survival-20260915T221720Z/`:
`light-hint-spread-results/{a1,b1,b2,a2}` (ABBA), `light-hint-repeat-results/{b1,a1,a2,b2}`
(BAAB), each with `capture.jfr`, `opa.json`, `heap-histogram.txt`, `series.json`, `server.log`,
`result.json`; `summary.json` and `restoration.json` per set. Locally:
`scratch/canvas-light-hint/{summary-abba.txt,summary-baab.txt,method-share-8legs.txt,
candidate-build.json}`. Clone restored to the frozen reset (`server.jar` `12ef42d0...`) and
stopped, network `none`, after both sets. Production untouched.

## Not covered

- Other `getRawBrightness` callers (mob spawning light checks, other light-gated random ticks)
  still take the lookup path; each would need its own held-chunk argument and its own test.
- No mixed-crowd leg and no settle-only leg for this change; the mechanism is confined to the
  grass/mycelium tick, so the scattered workload is the one that exercises it.
