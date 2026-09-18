# Per-chunk random-tick section bitmap

## Decision

Retained as an experimental fork change. Eight reset-isolated legs on the scattered-player
workload separated cleanly: every candidate leg had lower hottest-region census MSPT and lower
container CPU than every baseline leg. Mean hottest-region MSPT **0.890 -> 0.839 ms (-5.7%)**,
mean container CPU **0.657 -> 0.618 cores (-6.0%)**, live heap unchanged. The profile shows the
mechanism directly: the random-tick section scan's share of region samples fell from 30.2% to
23.9% while the per-section body and the block callbacks kept their share, and the bitmap's own
cost is about 1.6%.

This is a CPU result on the isolated Paradox clone with server-side bots and discarded network
transport. It is not a per-tick p95/p99 measurement and not a production figure.

## What changed

`ServerLevel.optimiseRandomTick` used to walk every section of every ticking chunk each tick and
ask `isRandomlyTickingBlocks()`; the earlier diagnostic showed **94.6% of those checks hit
inactive sections** (`section-scan-results.md`). The chunk now carries a bitmap:

- `LevelChunk` holds `long[] origin$randomTickWords` (one bit per section, sized on the first
  pass; the worlds here have 41 and 73 sections, so the 64-bit cap of the first draft was hit and
  replaced by a word array) and an `int` rescan countdown.
- `LevelChunk.setBlockState` updates the section's bit after every write that goes through the
  chunk, so a callback that activates or clears a later section is seen in the same pass: the pass
  re-reads the live word after each visited section.
- Every `RESCAN_TICKS` (default 20, system property `origin.randomTickSectionRescanTicks`) passes
  the chunk rebuilds the words from every section's own counts; a fresh chunk rescans on its first
  pass.
- A set bit is a hint only. The pass still checks `isRandomlyTickingBlocks()` live before ticking
  a section, and visits flagged sections in ascending order with the same RNG consumption and the
  same `origin$tickRandomSection` body as before. Nothing about random-tick speed, simulation
  distance, block selection or callback order changes.

Helper: `io.canvasmc.canvas.world.chunk.RandomTickSectionMask` (rescan, update, ascending
iteration; no instance state).

### The one semantic difference, stated plainly

A section that becomes active **behind the chunk** — a direct `LevelChunkSection.setBlockState`
call, or a plugin replacing an entry of the raw `getSections()` array — is not seen by
`LevelChunk.setBlockState`, so its bit stays clear until the next rescan. That section can miss up
to `RESCAN_TICKS` random-tick passes (about one second when the chunk ticks every tick). It is
never ticked twice and an inactive section is never ticked, because the live check remains. The
earlier note in `section-scan-results.md` rejected an index without a rescan; the bounded rescan
is what makes this one acceptable. Writes through `Level.setBlock` / Bukkit / OriginBlocksEngine
all go through `LevelChunk.setBlockState` and are exact.

Per-chunk cost: one `long[]` of one or two words plus an `int`. With about 17,200 loaded chunks the
post-GC live-heap delta was -0.0% (within the leg-to-leg noise of ±0.1%).

## Correctness tests

`RandomTickSectionMaskTest` (real `LevelChunkSection` objects, 4 tests):

- rescan matches a plain per-section scan for every section count from 0 to 254, including the
  63/64/65 and 127/128/129 word boundaries;
- tracked writes set and clear bits exactly in both directions and ignore indexes the chunk cannot
  hold or a not-yet-sized array;
- the words array is sized once, refilled in place on later rescans, and a direct section write is
  seen by the rescan;
- a recounted section is reflected by the rescan, not by a stale bit.

`RandomTickSectionTraversalTest` (real `optimiseRandomTick` via reflection; the chunk mock
delegates the bitmap methods to the real `LevelChunk` code, so the countdown under test is the
shipped one; 4 tests):

- unchanged RNG order and callback order for speeds 0/1/3/6/11 with a later section activated
  through the chunk during a callback;
- ascending visits across section counts 0..254 including 41 and 73 (the live worlds) and both
  word boundaries, with callbacks activating the next section through the chunk and clearing a
  later one;
- a section replaced behind the chunk is skipped until the next rescan and then ticked on every
  later pass, never twice;
- a stale set bit never ticks an inactive section and keeps the ascending order.

Candidate build passed `test createPaperclipJar`: canvas-api 519 tests (2 skipped), canvas-server
9,316 tests (23 skipped), zero failures or errors, all 8 focused tests executed.

## Measurements

Paradox `survival-test-canvas-offline`, network none, no published ports; no production access.
Each leg restores the same `vine-reset` filesystem, identical 70 plugin JAR hashes and benchmark
configuration (OriginBenchmark 1.8.4), 100 moving RTP-spread bots, zero injected mobs, about
17,200 chunks across 86 ticking regions. Nine cached census samples per leg over ~90 s; CPU from
cgroup usage deltas; heap histogram after a forced GC following the recording. Baseline is the
ticket-lookup candidate (`686b6c5e…`), so every earlier retained patch is held equal in both legs.

Set 1, ABBA (`section-mask-spread-results`):

| Leg | Build | Mean hottest-region MSPT | Max | CPU cores | Live heap |
|---|---|---:|---:|---:|---:|
| A1 | Baseline | 0.874 | 0.91 | 0.6370 | 4.658 GB |
| B1 | Bitmap | 0.841 | 0.96 | 0.6239 | 4.658 GB |
| B2 | Bitmap | 0.859 | 0.93 | 0.6278 | 4.655 GB |
| A2 | Baseline | 0.890 | 1.00 | 0.6702 | 4.658 GB |

Set 2, BAAB (`section-mask-repeat-results`):

| Leg | Build | Mean hottest-region MSPT | Max | CPU cores | Live heap |
|---|---|---:|---:|---:|---:|
| B1 | Bitmap | 0.832 | 0.89 | 0.6248 | 4.656 GB |
| A1 | Baseline | 0.882 | 0.97 | 0.6360 | 4.654 GB |
| A2 | Baseline | 0.913 | 1.02 | 0.6832 | 4.654 GB |
| B2 | Bitmap | 0.825 | 0.87 | 0.5933 | 4.655 GB |

Eight-leg means: MSPT 0.890 -> 0.839 (-5.7%); CPU 0.6566 -> 0.6175 cores (-6.0%); OPA-reported
process CPU 10.60% -> 10.01%; live heap 4.6560 -> 4.6559 GB. Baseline MSPT range 0.874–0.913,
candidate 0.825–0.859; baseline CPU 0.636–0.683, candidate 0.593–0.628. No cgroup throttling.

Region-thread JFR shares (mean of four legs each, percent of region samples):

| Method | Baseline | Bitmap |
|---|---:|---:|
| `ServerLevel.optimiseRandomTick` (inclusive) | 30.23 | 23.91 |
| `ServerLevel.origin$tickRandomSection` (per-section body) | 13.90 | 14.68 |
| `BlockStateBase.randomTick` (block callbacks) | 8.93 | 9.38 |
| `LevelChunk.origin$randomTickWordsForTick` + rescan | 0.00 | 1.56 |
| `ServerLevel.tickChunk` (inclusive) | 36.10 | 30.30 |

The body and callback shares are flat, which is what "same block ticks, less scanning" should
look like. Candidate legs also had fewer total region samples (3,006–3,144 vs 3,268–3,504).

One BAAB B2 census recorded 53 regions at 19.79–19.82 TPS with 0.04–0.86 ms MSPT — a brief
server-wide blip (GC or the census itself), not a slow region; baseline legs show the same kind of
sample at 19.96–19.98.

Earlier drafts, kept for the record, not for comparison: the first draft capped the bitmap at 64
sections and silently fell back to the full scan on the 73-section test world
(`section-mask-bypassed64-results`, no change measured, as expected); the second used a separate
mask object per chunk (`section-mask-object-results`, -3.7% MSPT / -5.3% CPU) before the fields
were inlined into `LevelChunk`.

## Restoration

All eight legs reached 100 bots with zero scripted-action failures and `cleanupRemaining=0`; no
plugin-enable or linkage errors. The clone was restored to the frozen reset
(`12ef42d0ae98f9211d2c7887fed73218770f43838c815fbae28ba884681188fc`) after each set, verified by
hash, and left stopped with network none.

- Tested baseline: `686b6c5eb4841a778c27e925e6aa568ff6a27605e53df19a19dad5e7f83116cc`.
- Tested candidate: `51e86503298ae5eff779fceeedcab09e5a8f3903996c87195f5f5119eacc8921`.
- Hosting evidence: `scratch/canvas-section-mask/` (build reports, both summaries, per-method
  shares for all eight captures, leaf profiles).

## Before production

Not authorized for Survival by this record. Still owed: a bounded settle-only measurement, a
mixed-crowd leg to confirm no regression when few sections are inactive, and a gameplay check
that a freshly pasted (FAWE) crop field resumes growth within the rescan window.
