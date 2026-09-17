# Random-tick section scan measurement and rejected four-wide loop

## Decision

No new runtime optimization retained. The section scan is mostly inactive work,
but a persistent active-section index cannot be kept correct merely by hooking
block changes: `ChunkAccess.getSections()` returns its actual mutable array, and
callers can retain it and replace entries without an observable setter. Existing
random-tick callbacks can replace or activate a later section during the same pass.
Replacing this API, fencing every retained array reference, or intercepting plugin
bytecode would exceed this local optimization. An index that refreshes only on
chunk load, recount or tick boundaries would silently miss valid updates.

Tested a smaller alternative: manually expand the scan loop to four sequential
checks per iteration, followed by a tail. Every section is still fetched live,
checked and processed in order; no preloading across callbacks. This avoids stale
index state, but the comparison did not justify retaining it. Runtime restored.

## Diagnostic measurement

Built a temporary Java 25 diagnostic with a counter owned by each region. Every
1,024th random-tick chunk invocation used an equivalent instrumented scan and
emitted a stackless `origin.RandomTickSectionScan` JFR event. The diagnostic was
used only in the network-none clone and removed before baseline/candidate timing.
It does not contain shared counters or change RNG consumption, callbacks or the
number of section visits. Full build/tests passed.

100 moving RTP-spread bots, zero injected mobs; same existing frozen test world.
During the approximately 90-second recording:

- 14,230 sampled chunk invocations.
- 1,038,790 section checks (73 per sampled invocation).
- 56,015 active-section entries: **94.61% of checks inactive**.
- 1,434 sampled invocations had no active section.
- Measured instrumented total: 13,686,541 ns.
- Measured active-section helper bodies: 6,586,030 ns.
- Remainder (scan, branches and timer overhead): 7,100,511 ns, **51.88%**.

This is diagnostic sampling, not an exact full-server census or a possible 52%
server speedup. Periodic sampling can alias traversal order. The residual includes
System.nanoTime overhead, and the instrumented method has a different compiled
layout. The active helper measurement includes selection/RNG work even when no
block callback fires. Do not extrapolate these nanoseconds into an exact server
CPU budget. The inactive ratio supports investigating the scan; it does not
justify an unsafe index.

## Correctness tests

Existing real-method regression preserves RNG consumption and callback order for
speeds 0, 1, 3, 6 and 11, including replacement of a later inactive section.
Added a test for section counts 0, 1, 2, 3, 4, 5, 7, 8, 9, 24, 73, 128 and 254:

- Each callback activates the next array entry before that entry is visited.
- A later active section is removed and activated again by its predecessor.
- Traversal crosses group boundaries and tails with exact ascending visits.
- Empty sections never enter the active body; RNG call count remains exact.

Baseline, candidate and restored runtime passed `test createPaperclipJar`:
519 API entries (2 skipped), 9,306 server entries (23 skipped), zero failures or
errors. Both focused tests executed. The final change retains only the regression
and this report. No instrumented classes or unrolled runtime method are retained.

## Matched scattered-player ABBA

Paradox `survival-test-canvas-offline`, network none, no ports; no production access
or modification. Each leg restores the same `vine-reset` filesystem, uses identical
70 plugin JAR hashes and benchmark configuration, including OriginBenchmark 1.8.4.
100 moving bots, no injected mobs, approximately 17,200 chunks and 86 total regions.
Existing animals, items and block entities remain; background item counts vary.
Heap histograms occur after the timed recording, not during it. CPU is measured
from cgroup usage deltas over approximately 90 seconds per leg.

| Leg | Build | Mean hottest-region census MSPT | Mean CPU cores | Live shallow-object bytes after GC |
|---|---|---:|---:|---:|
| A1 | Baseline | 0.8989 | 0.6537 | 4,657,146,728 |
| B1 | Four-wide | 0.9333 | 0.6901 | 4,652,701,568 |
| B2 | Four-wide | 0.9533 | 0.6956 | 4,654,197,296 |
| A2 | Baseline | 0.9489 | 0.6579 | 4,655,260,616 |

Mean CPU: **0.65580 -> 0.69284 cores (+5.65%)**.
Mean hottest-region census MSPT: **0.92389 -> 0.94333 (+2.10%)**.
Live-object total: -0.059%, with unchanged 2,478,350 section objects. No useful RAM
improvement. No cgroup quota throttling; minimum sampled region TPS 19.98.

Reject the runtime candidate: no measured win. These short runs do not prove a
universal regression. Nine distinct cached census samples per leg are not per-tick
p95/p99, and the hottest sampled region may differ between samples. Continued bot
movement means this is not a separately certified settled-loading phase. Discard
transport does not measure actual client-network cost.

## Restoration and evidence

All four A/B legs reached 100 bots, reported zero scripted-action failures,
cleanupRemaining=0 and idle; no plugin-enable/linkage errors. The diagnostic also
passed its teardown. Cleanup is plugin accounting, not an independent world audit.
Final stopped filesystem reset restored launcher:
`12ef42d0ae98f9211d2c7887fed73218770f43838c815fbae28ba884681188fc`.
Docker remained stopped, network none, no published ports.

- Tested baseline: `da999820a71553a69b727b573bd89b024f9953cf74798b8aad444478f40d1402`.
- Tested candidate: `e43fa29650b09fd95596b3e8ac79db4474f477901f8b3cc1e3050f5bd93d158c`.
- Diagnostic: `72d7cb8d0fd878ac6dc76a9bb865fbb5b6d2d545d16a8b78d9046acf0e716043`.
- Final restored build: `09e845e2d79a3d4b11f953af0f0d2b23bcc23ab72109fa98a5568ab38962edf1`.
- Prior integration patches and separately staged ticket experiment held equal.
- Hosting evidence: `scratch/canvas-section-scan/` (build reports, raw series,
  fingerprints, diagnostic event reader/counts, restoration, rejected method).
- Remote: `section-scan-diagnostic-results/a1` and `section-scan-ab-results/`
  under the existing private benchmark snapshot directory.

A summary-script assertion initially used a mis-decoded Czech failure label.
Corrected it to explicit Unicode escapes matching the actual status output;
assertion still requires zero action failures. No gate was skipped.

A next CPU investigation can target redundant ticket lookups without introducing
persistent section state. The larger index requires an explicit mutation contract,
not more optimistic invalidation guesses.
