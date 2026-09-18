# Stacked comparison: fork `origin-patches` vs clean upstream base

## Why

Every retained patch was measured against the fork as it stood the day before, never the whole
fork against the unmodified upstream build. This is the one comparison that says what deploying
the fork would change. Same isolated Paradox clone, same frozen world and plugin set
(`vine-reset`), same reset before every leg, ABBA order, four legs per workload.

- **Baseline**: `canvas-baseline.jar` `366dd16e...` - upstream `ddc374bb` + only the recipe test
  fixture correction (`d826fc3a`), no runtime changes.
- **Candidate**: `1303db61...` - fork `origin-patches` at `e9e038ae` (every retained patch:
  ticket no-expiry fast path, deferred collision box, random-tick method layout, lazy tick
  membership map and list, activation exclusions, shared zero-bit storage, ticket-set index,
  section bitmap, grass light read through the held chunk, plus the vine traversal change).

Both jars are built on the previous Paper base (`paperRef a2a42c5b`). The upstream merge that
moved the fork to `e5fe7172` (`42bee392`) built and passed its tests afterwards but was not
benchmarked.

## Scattered players: 100 moving bots, no injected mobs, ~17,200 chunks, 86 regions

| Leg | Build | Hottest-region census MSPT | Container CPU cores | Live heap after GC |
|---|---|---:|---:|---:|
| a1 | upstream | 0.926 ms | 0.651 | 5.419 GB |
| b1 | fork | 0.801 ms | 0.628 | 4.656 GB |
| b2 | fork | 0.808 ms | 0.584 | 4.660 GB |
| a2 | upstream | 0.919 ms | 0.645 | 5.420 GB |

| Mean | Upstream | Fork | Change |
|---|---:|---:|---:|
| Hottest-region census MSPT | 0.922 ms | **0.804 ms** | **-12.8%** |
| Container CPU cores | 0.648 | **0.606** | **-6.5%** |
| Live heap after GC | 5.420 GB | **4.658 GB** | **-14.1% (-761 MB)** |

Every fork leg is below every upstream leg on all three measures. The heap figure matches the
sum of the three measured RAM patches (589 + 73 + 65 MiB ~= 727 MiB ~= 762 MB); the MSPT figure
is larger than any single patch measured on its own, which is consistent with the section
bitmap, the ticket-set index and the light read all removing work from the same region tick.

## Mixed crowd: 64 moving bots + 1,280 custom farm mobs in one target region

| Leg | Build | Target-region census MSPT | Container CPU cores |
|---|---|---:|---:|
| a1 | upstream | 22.86 ms | 2.197 |
| b1 | fork | 22.65 ms | 2.207 |
| b2 | fork | 23.09 ms | 2.274 |
| a2 | upstream | 23.45 ms | 2.312 |

| Mean | Upstream | Fork | Change |
|---|---:|---:|---:|
| Target-region census MSPT | 23.16 ms | 22.87 ms | -1.2% |
| Container CPU cores | 2.255 | 2.241 | -0.6% |

**No measurable stacked benefit on the crowd in four legs.** The legs overlap (fork 22.65-23.09,
upstream 22.86-23.45). The activation-exclusion patch measured -5.3% region MSPT / -5.9% CPU on
its own eight-leg test against the fork of that day; that gain does not show up here against
the clean upstream base with four legs. Either it is smaller than the crowd's run-to-run
variance, or another retained patch costs something on this workload. This needs its own
eight-leg stacked crowd set before anyone quotes a crowd number for the fork; do not read the
scattered result across to crowded regions.

## Verification

- All eight legs `PASS`: zero scripted-action failures, `cleanupRemaining=0`, plugin JAR hashes
  unchanged, min sampled region TPS >= 19.97 (scattered); crowd legs report `cleaned` with no
  tagged benchmark entities left. Leg `a2` of the crowd set saw 0.09 s of cgroup CPU throttling,
  the only throttling in either set.
- Both restoration records: `server.jar` back to the frozen reset `12ef42d0...`, container
  stopped, network `none`, plugins and benchmark config matched.

## Limits

Server-side bots with discarded network transport on a 6-CPU, 16 GiB-heap clone; census
averages over a 300 s (scattered) / observation window, not per-tick p95/p99; no real-client
packet path, so nothing here measures chunk-packet or tracking cost as a player would feel it.
The scattered result is the one that separates cleanly; the crowd result is a null result.

## Evidence

Clone: `stack-spread-results/{a1,b1,b2,a2}`, `stack-crowd-results/{a1,b1,b2,a2}`, each with
`summary.json` and `restoration.json`, under
`/var/lib/originworks/benchmarks/survival-20260915T221720Z/`. Locally:
`scratch/canvas-stack/{stack.out,spread-summary.json,crowd-summary.json}` and the six
`stack-*.py` scripts used.
