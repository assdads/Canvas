# Grass propagation single-read candidate: rejected

## Hypothesis

Two saved CPU captures put random-tick traversal ahead of chunk decoding. In collision
A1, 536 of 7,487 region execution samples attributed their top frame to
LevelChunkSection.isRandomlyTickingBlocks; 90 attributed it to SpreadingSnowyBlock.randomTick.
Inlining and caller attribution mean this is not a reason to rewrite the boolean getter.

The tested change instead reused the above-block state within canPropagate, avoiding
one repeated chunk palette lookup and one above-position construction. It introduced
no persistent cache, altered RNG, tick-rate change or event-order change. Existing
vine, collision and ticket changes were identical in both builds.

## Verification

GrassPropagationTest invokes the actual private canPropagate method with controlled
chunk reads. It checks grass/mycelium against all states of air, snow, water, lava,
stone, slabs, stairs, leaves, glass and ice, including waterlogged shapes. A second
test changes the above block between calls to reject cross-call caching. Both pass
before and after the change, and after restoring the original implementation.
These are helper tests, not a complete randomTick/event or world mutation proof.
Full test/createPaperclipJar passed for the candidate and restored source.

## Isolated ABBA experiment

100 moving server-side bots / 2,000 spawned custom mobs, approximately 17,190 loaded
chunks and 80 ticking regions; networking disabled. Same reset snapshot, plugin JAR
hashes and benchmark configuration, nine distinct full-load censuses per leg.

| Leg | Build | Mean hottest-region census MSPT | Reported CPU % |
|---|---|---:|---:|
| A1 | Baseline | 2.4011 | 36.6778 |
| B1 | Candidate | 2.4089 | 37.8556 |
| B2 | Candidate | 2.4633 | 39.3556 |
| A2 | Baseline | 2.3156 | 35.3000 |

Candidate averages were 3.30% higher MSPT and 7.27% higher reported CPU. This is not
proof of a regression: entity/item counts and a few chunks varied, host scheduling
and JIT effects are uncontrolled, and census means are not per-tick percentiles.
It does not demonstrate a benefit. No allocation-saving percentage is claimed.

All legs reported zero scripted-action failures and cleanupRemaining=0, with no
plugin-enable or linkage errors. Test state was restored, original launcher hash
12ef42d0ae98f9211d2c7887fed73218770f43838c815fbae28ba884681188fc verified,
and the container stopped with network none. Production was not changed.

## Decision

Removed the runtime patch and rebuilt. Retain regression tests and this record only.
Do not stack this candidate onto the fork on the strength of fewer source-level reads.
Private captures: grass-heavy-results/{a1,b1,b2,a2}; local evidence:
hosting/scratch/canvas-grass/{manifest,summary,restoration,restored-test-results}.json.
Baseline SHA256: 680433d5b083efaaf54ec5e7254363b8c8e664b9bb0ada934a2783950f77d5cb.
Candidate SHA256: bc24fe62d646fc54b1d24c137aaec3cf713887ea81e9119207580697fd9b3ff6.
