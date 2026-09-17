# Ticket expiry bookkeeping without the second chunk lookup

Status: **experimental, retained**. Verified on the isolated Paradox clone only; no production
deployment is authorized by this record.

## What changed

`ChunkHolderManager.tick()` walks, per owned region section, the chunks that hold at least one
timed ticket. Upstream keeps that index as `section -> (chunk -> expiring-ticket count)` and then
performs a second concurrent hash-table lookup, `this.tickets.get(chunkKey)`, for every indexed
chunk on every tick to reach the `TicketSet`.

The fork now keeps the index as `section -> (chunk -> TicketSet)` and stores the expiring-ticket
count on the `TicketSet` itself (`expiringCount`, read/written only under the chunk's ticket lock).
`tick()` reads the set straight from the index entry; the concurrent table lookup is gone from the
per-chunk loop. `addExpireCount`/`removeExpireCount` maintain the count on the set and insert or
drop the index entry exactly on the 0 -> 1 and 1 -> 0 transitions, which is the same membership
the old counter map expressed.

Not changed: ticket lifetimes, expiry tick, `Long.MIN_VALUE` permanent sentinel, sorted-set
ordering, minimum-level propagation, ticket counters, `UNKNOWN` deferral on level change, the
section-level `processTicketUpdatesNoLock` pass, area locks, or any chunk load/unload timing.
`TicketSet.copy()` snapshots do not carry the count; copies are only handed out read-only.

Invariant the index relies on: a `TicketSet` is only removed from the ticket table in `tick()`
after it has become empty, which means every ticket in it expired, so its expiring count reached 0
and its index entry was removed in the same locked pass. No other path removes a set
(`git grep 'this.tickets.remove'` -> one site).

## Correctness evidence

- `TicketExpiryBookkeepingTest` (4 tests, run against the real `ChunkHolderManager` +
  `TicketSet` + `ThreadedTicketLevelPropagator` with a mocked region): timed tickets expire on the
  owner tick and propagate the new level; removing the lowest ticket defers the level change through
  an expiring `UNKNOWN` ticket; replacing between timed and permanent keeps the index and the same
  set instance; a randomized sequence of adds/removes/partial-region ticks is compared against an
  independent model of the expected index contents after every step.
- Full `test createPaperclipJar`: canvas-api 519 tests, canvas-server 9310 tests, 0 failures,
  0 errors (candidate build `686b6c5eb4841a778c27e925e6aa568ff6a27605e53df19a19dad5e7f83116cc`).
- Eight live legs (below) each finished `obench` teardown with `cleanupRemaining=0`, zero scripted
  action failures, unchanged plugin JAR hashes, no plugin enable/initialization errors, and
  `minRegionTps >= 19.69`.

## Live comparison

Isolated clone, network `none`, 16 GiB heap, frozen `vine-reset` restored before every leg.
Workload: 100 scattered moving bots, no injected mobs, ~17,200 loaded chunks across ~86 regions.
Baseline jar `da999820a71553a69b727b573bd89b024f9953cf74798b8aad444478f40d1402` is the HEAD
runtime (all earlier retained patches, including the experimental `removed == 0` hunk, in BOTH legs).

Ticket share = inclusive share of region-thread JFR execution samples under
`ChunkHolderManager#tick` (`TicketCpuProbe.java`, 90 s capture per leg).

| Set | Leg | Build | Ticket share | Container CPU cores | Hottest-region census MSPT | Post-GC live heap |
|---|---|---|---:|---:|---:|---:|
| ABBA | a1 | baseline | 17.45% | 0.6515 | 0.896 | 4.657 GB |
| ABBA | b1 | candidate | 12.99% | 0.6250 | 0.964 (one 1.55 census) | 4.661 GB |
| ABBA | b2 | candidate | 14.56% | 0.6344 | 0.908 | 4.660 GB |
| ABBA | a2 | baseline | 16.92% | 0.6598 | 0.927 | 4.656 GB |
| BAAB | b1 | candidate | 13.56% | 0.6299 | 0.892 | 4.656 GB |
| BAAB | a1 | baseline | 14.41% | 0.6536 | 0.922 | 4.658 GB |
| BAAB | a2 | baseline | 16.97% | 0.6367 | 0.893 | 4.658 GB |
| BAAB | b2 | candidate | 14.00% | 0.6698 | 0.917 | 4.656 GB |

Means over the eight legs:

| Measurement | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| Ticket-maintenance share of region samples | 16.44% | 13.78% | -2.66 points (-16% relative) |
| Container CPU cores | 0.6504 | 0.6398 | -1.6% |
| Hottest-region census MSPT | 0.909 ms | 0.920 ms | +1.2% |
| Post-GC live heap | 4.657 GB | 4.658 GB | +0.02% |

Reading: the mechanism shows up as intended. Every candidate leg's ticket share is below the
baseline mean, and three of four baseline legs are above every candidate leg; the concurrent
table `get` frames beneath `tick` are the frames that disappeared. The whole-process numbers do
NOT establish a speedup: the first set measured -4.0% CPU cores, the reversed set +0.7%, and the
hottest-region census is flat within noise (the +1.2% is driven by one 1.55 ms census in ABBA b1).
Heap is neutral, as expected for swapping an `int` map value for a reference plus one `int` field
per set. JFR shares are relative to a fixed sampling budget, so a lower ticket share raises the
apparent share of everything else (`optimiseRandomTick` reads ~1-2 points higher on candidate
legs for that reason, not because random ticking got slower).

Not measured: per-tick p95/p99, real-client network load, region merge/split under load beyond
what the benchmark's movement produces, plugin `PLUGIN_TICKET` churn, portal/teleport tickets.

## Reproduction

- Build: `scratch/canvas-ticket-lookup/build.py candidate` in the hosting workspace (fixup +
  rebuild source patches, then `test createPaperclipJar`; asserts the four focused tests ran).
- Legs: `ticket-lookup-ab.py run <a1|b1|b2|a2> <baseline|candidate> heavy` and
  `ticket-lookup-repeat.py` (same script, `ticket-lookup-repeat-results`, run BAAB).
- Summaries: `ticket-lookup-summary.py`, `ticket-lookup-repeat-summary.py` (also restore the
  clone runtime to the frozen reset and print `RESTORED <server.jar sha256>`).
- Evidence on Paradox: `ticket-lookup-spread-results/*`, `ticket-lookup-repeat-results/*`
  (`result.json`, `capture.jfr`, `opa.json`, `heap-histogram.txt`, `server.log`).

## Promotion gate

Keep experimental. Before any production use: the standing gates in `ORIGIN.md` (chunk
load/unload, teleport/portal, plugin-ticket and region-transfer checks on a real-player workload)
plus a repeat of this comparison on the production host. A ~16% cut of a ~16% path is at most a
few percent of region CPU; do not quote it as a server-wide gain.
