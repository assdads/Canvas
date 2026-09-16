# Deferred collision-box allocation experiment

## Change

`CollisionUtil.getCollisionsForBlocksOrWorldBorder` now tests a single-box collision
shape using translated primitive coordinates. It creates the translated AABB only when
adding an accepted collision to the output list. Rejected intersections, rejected
predicates and check-only queries no longer need that temporary box.

The full-block branch still uses exact intersection. Other single-box shapes still use
Moonrise's existing epsilon overload. Translation adds the same coordinates in the same
order; predicates, chunk ownership checks, output ordering, complex shapes and movement
resolution are unchanged. There is no cache or shared scratch state.

## Verification

- Full `test createPaperclipJar` passed: API XML 519 tests, zero failures/errors, two skipped;
  server XML 9,282 tests, zero failures/errors, 23 skipped.
- Two new executed tests compare the original moved-AABB overloads with the primitive
  overloads: 100,000 random boxes plus contact, epsilon, slab/oversized/degenerate shapes,
  negative coordinates and world-edge coordinates.
- These are geometry-equivalence tests, NOT exhaustive world/predicate/movement tests.
- Four ABBA legs ran on the isolated Paradox clone: 100 moving server-side bots and
  2,000 spawned custom farm mobs, reset from the same frozen files before each leg.
- All four reported zero scripted-action failures, cleanupRemaining=0, matching plugin
  and benchmark-config fingerprints, and no plugin-enable/linkage errors.
- Original test runtime restored and stopped. Production Survival was not modified.

## Measurements

| Leg | Build | Mean hottest-region census MSPT | Collision-path AABB allocation sample weight, bytes |
|---|---|---:|---:|
| A1 | Baseline | 2.4300 | 1,161,716,416 |
| B1 | Candidate | 2.3233 | 632,466,064 |
| B2 | Candidate | 2.4589 | 643,530,232 |
| A2 | Baseline | 2.4678 | 1,321,599,872 |

AABB allocation sample weight for stacks containing this collision method fell 48.6%
in aggregate. This is JFR weighted sampling, not exact allocation counts or bytes saved.
Each leg supplied nine distinct full-load censuses; duplicated cached censuses were removed.
Hottest-region means fell 2.36%, but reported process CPU rose 1.71%. Total allocation
sample weight fell only 0.53%. These runs support a localized allocation reduction, NOT
an established server-wide CPU/tick improvement. Census MSPT is not per-tick p95/p99.

Both builds include the earlier vine and uncommitted ticket candidates equally, so this
experiment isolates only collision allocation deferral. The synthetic bots discard network
traffic; real-client protocol throughput is not measured. Shared host scheduling, JVM/JIT
variation and non-deterministic mob movement remain sources of variation.

## Artifact provenance

- Baseline SHA-256: `6be6a0c4c4012d8b90e581777992c7ad431b8bae256f71890143cb14fdae4e4e`
- Candidate SHA-256: `680433d5b083efaaf54ec5e7254363b8c8e664b9bb0ada934a2783950f77d5cb`
- Hosting workspace evidence: `scratch/canvas-collision/`.
- Private test-host evidence: `collision-heavy-results/` under the existing frozen benchmark root.
- The first A1 attempt failed in the observer's output-directory guard. Its evidence is
  retained separately as `a1-observer-failed`; it is excluded. The corrected runner reset
  the filesystem before the successful A1.

## Status

Experimental, not deployed to production. Next correctness coverage should exercise
collision-sensitive blocks and movement (stairs, fences, pistons, fluids and border cases)
before considering promotion. Avoid inferring a production speedup from allocation alone.
