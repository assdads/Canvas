# Entity block-intersection visitor experiment: not adopted

A candidate replaced Entity.checkInsideBlocks' captured callback, AtomicInteger and two
one-element arrays with one invocation-local BlockStepVisitor. All block/fluid callbacks,
limits and chunk-lookup order were retained. No shared state or cross-call cache was added.

## Correctness and build

Three tests invoke the actual private method using controlled level/chunk mocks. They cover
missing-chunk caching, no cache reuse between calls, air visit order, alive/iteration early
exit, moving traversal, and nested invocations. All pass on BOTH original and candidate.
Full `test createPaperclipJar` passed for candidate and again after restoring original code.
These are not complete block/fluid gameplay tests and do not establish production safety.

## Heavy ABBA on the isolated Paradox copy

Each leg reset the same world/plugin files and ran 100 moving server-side bots with
2,000 custom farm mobs. Older patches were equal in both artifacts. Networking remained
none; no production access, SQL service or real-client network traffic was involved.

| Measurement | Baseline mean | Candidate mean |
| --- | ---: | ---: |
| Hottest-region census MSPT | 2.3756 | 2.4544 |
| Reported process CPU percent | 36.5167 | 38.4611 |
| Total JFR allocation sample weight, bytes | 125337347640 | 125732002804 |

Candidate region time was 3.32% higher and reported CPU 5.32% higher. These four short runs
are not enough to prove a regression, but provide no adoption case. Census observations
are not individual-tick p95/p99. Allocation samples had substantial baseline variance:
chunk-array weight was about 5.87 GB in A1 but 0.098 GB in A2. Do not turn that into a
claimed multi-GB saving. Candidate replaces holders structurally but no reliable total
allocation or throughput improvement was demonstrated.

All four runs had zero scripted-action failures, cleanupRemaining=0, and no plugin-enable,
linkage or colliding-entity-with-block exceptions in the checked logs. Plugin and benchmark
configuration fingerprints matched. Test files were restored and the container stopped;
original test launcher SHA-256:
`12ef42d0ae98f9211d2c7887fed73218770f43838c815fbae28ba884681188fc`.

## Decision

Removed the runtime candidate from the active source/patch set. Retained regression tests
and this negative result. The separate hosting workspace keeps the exact rejected diff,
built artifacts, manifests and raw summary in `scratch/canvas-intersections/`. Private
captures remain under `intersections-heavy-results/` in the isolated snapshot directory.
Production Survival was untouched.
