# Vine spread traversal candidate (2026-09-16)

## Change

`VineBlock.canSpread` replaces `BlockPos.betweenClosed` iterable/iterator traversal with
three bounded loops and one method-local mutable cursor. X remains fastest, then Y, then Z.
All 243 possible positions, the fifth-vine early exit, block-state reads and growth conditions
remain unchanged. No shared cache, asynchronous access, ticking reduction or gameplay setting change.

## Why this target

A JFR capture on the isolated Paradox clone attributed 30,542,952 sampled allocation-weight bytes
to `BlockPos.betweenClosed` called by `VineBlock.canSpread` during the initial 100-mob probe.
This is a sampling estimate, not an exact allocation counter or a server-wide percentage.
Larger entity collision allocations were also present but are a higher-risk next investigation.

## Correctness/build

The regression runs the actual private method against an instrumented BlockGetter and compares
both results and every visited position with the original traversal. 300 cases cover empty,
threshold, dense, randomized and chunk/world-edge positions. Passed before and after the change.

Full `test createPaperclipJar` passed: API XML 519 entries, 0 failures/errors, 2 skipped;
server XML 9,280 entries, 0 failures/errors, 22 skipped. These are XML entry counts.

The opt-in `VineSpreadAllocationTest` uses the actual candidate via a MethodHandle and the exact
original loop with identical synthetic block reads. Six alternating measurements after warmup:
- Dense fixture: both paths scalar-replaced allocations to zero; candidate traversal was faster.
- Sparse fixtures: original allocated 80 bytes/call, candidate 0 bytes/call in this JVM.
- Sparse loop timings were roughly 4x faster. This is a synthetic method-level measurement,
  NOT a 4x server gain. Different inlining/escape-analysis contexts may retain cursor allocations.

Enable with `CANVAS_VINE_MEASURE=1`. Never gate CI on wall-clock timing.

## Isolated live ABBA

Target: `survival-test-canvas-offline` on Paradox, network none, 16 GiB heap/20 GiB cap,
6 CPU quota, 4 region threads. Production Survival was not touched.

Both legs include the pre-existing experimental ticket patch equally. Baseline is its already
built artifact; candidate adds only the vine runtime change. Restore the same stopped filesystem
before every leg; retain 70 identical plugin JARs. Scenario `om-one-100-control`: discard bot
transport, one bot, 75 polar bears and 25 chickens, no custom-mob or physical-network claim.

| Leg | Build | testingworld snapshot MSPT | TPS | Entities | Loaded chunks |
| --- | --- | ---: | ---: | ---: | ---: |
| A1 | baseline | 0.90 | 20 | 101 | 1003 |
| B1 | candidate | 0.81 | 20 | 101 | 1003 |
| B2 | candidate | 0.82 | 20 | 101 | 1003 |
| A2 | baseline | 0.89 | 20 | 101 | 1003 |

All boots passed the initialization/enable exception gate; all runs returned idle with
`cleanupRemaining=0`. JFR and OPA captures exported per leg. The approximately 0.08ms difference
is encouraging but NOT established causation: short, lightly loaded runs, no tick p95/p99 series,
variable total sampled allocations and almost no vine stack samples in these four captures.
These runs establish integration compatibility, not a dependable overall performance percentage.
Movement/teleport churn, large custom-mob loads and long-duration testing remain unmeasured.

Hashes:
- baseline: `761ac3f2ca7146c6f311d771682c4ed45cea95b30e8951cc80461ec592bba79e`
- candidate: `6be6a0c4c4012d8b90e581777992c7ad431b8bae256f71890143cb14fdae4e4e`

Evidence on Paradox: `/var/lib/originworks/benchmarks/survival-20260915T221720Z/vine-results/`.
Local evidence: `scratch/canvas-vine/` (hosting workspace). The clone was restored to its original
pre-experiment filesystem/server JAR and left stopped. Results live outside that reset directory.

Decision: retain as a small experimental candidate with demonstrated method-level savings and
passing correctness/integration tests. Do not deploy to production or advertise a server-wide gain.
