# Random-tick loop layout: retain as experimental

## Scope

Split ServerLevel.optimiseRandomTick's per-section callback body into a private helper.
The outer loop still reads the live section array in ascending order. No cached active
section list, ownership change, random-tick frequency change, or shared scratch state.
The RNG refill/selection code and block/fluid callback order are unchanged.

A persistent active-section cache was not implemented: ChunkAccess.getSections exposes
its backing array, so replacement is not necessarily observable through a setter.
Callbacks may replace a later section in the same traversal. Correct invalidation is
wider than this local optimization.

## Evidence leading to the experiment

Saved JFR frames report LevelChunkSection.isRandomlyTickingBlocks as **Inlined**,
not an expensive virtual invocation. The hypothesis is code layout/inlining of the
caller, not removing getter calls. In the candidate captures optimiseRandomTick is
inlined; the extracted origin$tickRandomSection is separately compiled. Attribution
moves between inline frames, so disappearance of getter samples is not a speedup metric.

## Correctness and build

Full `test createPaperclipJar` passed. RandomTickSectionTraversalTest invokes the actual
private loop and passes both before and after the change. It checks speeds 0/1/3/6/11,
RNG nextLong counts, position/order of callbacks, negative coordinates, empty sections,
and a callback replacing a later section. This is not exhaustive fluid/event gameplay QA.
No world access was moved to another thread; normal region ownership remains required.

## Eight isolated heavy runs

Same frozen world/plugin reset, 100 moving server-side bots and 2,000 spawned custom
mobs. Network is disabled. Every leg passed benchmark action/cleanup checks, with
identical plugin and benchmark-config hashes. Nine distinct full-load censuses per leg.
ABBA followed by BAAB, same two artifacts throughout:

| Block | Leg | Build | Mean hottest-region census MSPT | Mean reported CPU % |
|---|---|---|---:|---:|
| ABBA | A1 | baseline | 2.4344 | 38.2333 |
| ABBA | B1 | candidate | 2.4422 | 36.5222 |
| ABBA | B2 | candidate | 2.2578 | 36.7667 |
| ABBA | A2 | baseline | 2.4578 | 37.1778 |
| BAAB | B1 | candidate | 2.2433 | 37.8778 |
| BAAB | A1 | baseline | 2.5011 | 36.6889 |
| BAAB | A2 | baseline | 2.4567 | 37.1000 |
| BAAB | B2 | candidate | 2.3011 | 37.1556 |

Combined: 2.4625 -> 2.3111 ms (**6.15% lower**) hottest-region census mean.
CPU: 37.3000 -> 37.0806% (**0.59% lower relative**), effectively inconclusive.
Three of four candidate legs were below every baseline leg; the first candidate leg
was not. This warrants retaining a small experimental patch, not promising a 6% server
speedup. Census snapshots are not per-tick p95/p99; dynamic world activity and chunk
streaming remain possible confounders. No real-client/network performance was tested.
Do not compare these numbers with old runs using different artifacts or plugin states.

## Artifacts and restoration

Baseline SHA-256: `680433d5b083efaaf54ec5e7254363b8c8e664b9bb0ada934a2783950f77d5cb`.
Candidate SHA-256: `b3e06f69584ea521c51c4f874f9e84e62f673896b6deb324b63b2d232faa7533`.
Prior ticket, vine and collision candidates were held equal in both builds; the ticket
candidate remains a separate uncommitted experiment and is not part of this change.

The original isolated runtime files were restored from vine-reset after each four-run
block. Original launcher hash verified; container stopped, networking none. Production
Survival was not changed. Private raw results: sections-heavy-results/ and
sections-repeat-results/ under the existing benchmark snapshot root. Local evidence:
hosting/scratch/canvas-sections/{manifest,summary,repeat-summary,restoration,repeat-restoration}.json,
scan-modes.txt, ab-scan-modes.txt and build/test logs.
