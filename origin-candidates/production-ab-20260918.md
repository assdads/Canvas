# Survival production A/B, 2026-09-18: current Canvas 835 vs OriginWorks fork, 500 bots + 10k mobs

Authorized live comparison on Survival (origin). One fresh-boot leg per build, same frozen
plugin set, same `region-spread-500-mobs` scenario (500 server-side bots, 20 full-AI OriginMobsEngine
animals each, `om density off`), 11 OPA censuses at hold+60 s .. hold+360 s (30 s cadence) plus one
30 s OPA sampling profile per leg. Production was restored to the original build afterwards.

## Builds

| Leg | server.jar | Runtime `versions/26.2/canvas-26.2.jar` | Boot |
|---|---|---|---|
| current | `12ef42d0…` Canvas `26.2-835-HEAD@b276251` (2026-06-23) | `0f29a732…` | 18.7 s |
| fork | `1303db61…` = `origin-patches` **e9e038ae** content (build string says `@8a45bdf`: light-hint change was in the tree, uncommitted at build time) | `019befd2…` | 26.3 s |

Fork = clean upstream `d826fc3a` (newer Paper base than 835) + every retained patch (ticket
no-expiry fast path, ticket-set-in-index, lazy section map/list, shared zero-bit storage,
random-tick body split, random-tick section bitmap, deferred collision box, activation exclusions,
grass light read through the held chunk). So "current vs fork" here includes the **upstream
835 → d826fc3a delta**, not only our patches.

## Results (hottest region census, `testingworld` region at x=-5240 z=4568, 41 bots, ~940 entities)

| Sample (offset from hold) | current | fork |
|---|---:|---:|
| 1 (+60 s) | 34.44 | 25.79 |
| 2 (+90 s) | 33.61 | 26.89 |
| 3 (+120 s) | 30.47 | 27.03 |
| 4 (+150 s) | 29.50 | 24.49 |
| 5 (+180 s) | 32.07 | 27.48 |
| 6 (+210 s) | 33.20 | 28.69 |
| 7 (+240 s) | 31.27 | 30.58 |
| 8 (+270 s) | 29.89 | 33.25 |
| 9 (+300 s) | 29.74 | 37.99 |
| 10 (+330 s) | 26.39 | 33.41 |
| 11 (+360 s) | 26.70 | 31.50 |
| **mean** | **30.66 ms** | **29.74 ms (−3.0 %)** |
| **median** | **30.47 ms** | **28.69 ms (−5.8 %)** |

Other series (OPA overview, its own 20 s cadence, 11 samples each):

| Measure | current | fork |
|---|---:|---:|
| Server-level estimated MSPT, mean | 31.3 ms | 29.9 ms (−4.5 %) |
| Process CPU, mean | 86.2 % | 85.8 % |
| System CPU, mean | 43.5 % | 44.1 % |
| Busiest-region load, mean | 62.6 % | 59.9 % |
| TPS / min region TPS | 20 / 19.69–19.79 | 20 / 19.67–19.87 |
| Regions / chunks / players | 144 / 87.6k→87.1k / 502 | 144 / 87.1k→87.2k / 501 |
| Entities | 11,921 → 11,171 | 11,276 → 11,870 |

**Reading:** a few percent lower on average with the fork, but the two series have opposite
trends (current falls 34 → 27 ms, fork rises 26 → 38 → 31 ms) and overlap; one leg each, no repeat.
Both legs' two highest samples coincide with the 30 s OPA sampling profile running (current: manual
profile at +60..+90 s = samples 1–2; fork: OPA's own `alert:cpu` profile at +277..+307 s = samples
8–9, my manual start was superseded). Excluding those, current 29.91 vs fork 28.43 ms (−5 %).
This is **not a demonstrated production gain**. It is consistent with the clone: the hottest
production region is a 41-player crowd region, where the fork measured null on the clone; the
scattered-player memory/CPU gains (−12.8 % MSPT, −761 MB heap on the clone) do not show up in a
census that reports only the hottest region.

Region-thread inclusive stack shares from the one profile per leg (different offsets, so indicative only):

| Path | current | fork |
|---|---:|---:|
| `ServerChunkCache.tick` | 37.1 % | 34.5 % |
| `ChunkHolderManager.tick` | 7.5 % | 5.8 % |
| `TicketSet.expireAndRemoveInto` | 4.1 % | 2.7 % |
| `ServerLevel.optimiseRandomTick` | 3.1 % | 6.8 % |
| `SpreadingSnowyBlock.randomTick` | 2.3 % | 0.3 % |
| `StarLightInterface.getRawBrightness` | 0.07 % | 0 % |
| `ActivationRange.activateEntities` | 1.4 % | 1.5 % |
| `ChunkMap.newTrackerTick` | 3.0 % | 3.2 % |

Ticket-maintenance and grass/light shares fell as on the clone. `optimiseRandomTick` **rose** here
(3.1 → 6.8 %), the opposite of the clone; the fork profile was captured during its CPU-alert spike on
an 87k-chunk world (≈4× the clone's), so this needs a matched-offset repeat before drawing a
conclusion about the section bitmap at production scale.

## What broke: OriginPerformanceAudit deadlocked the whole server (attempt 1)

First fork attempt reached the 500/10k hold at 18:18:22 UTC, produced one census (30.22 ms hottest,
501 players, 11,494 entities), then **every one of the 16 region tick threads parked forever** on
`ThreadedRegionizer.regionLock` (`StampedLock.writeLock` in `markNotTicking`/`tryMarkTicking`);
console commands stopped executing, `obench stop` never ran, graceful stop hung, the container had
to be killed (exit 137, 18:28:18). Thread dumps: `hosting/scratch/canvas-prod-ab/evidence/…/fork-attempt1-opa-timeout/threaddump-*.txt`.

Cause (from the dump + source): OPA's `WorldRegionCollector.enumerateRegions` called
`region.getOwnedPackedChunkPositions()` **inside** `computeForAllChunkRegions(...)`. The iteration
holds the regionizer's `StampedLock` read stamp; `getOwnedPackedChunkPositions` acquires the same
non-reentrant read lock again. Once a tick thread is queued for the write lock, the nested read parks,
the outer stamp is never released, and all writers wait on OPA. **The bytecode of both methods is
identical in the 835 runtime and the fork runtime** (`javap` checked) — this hazard was live on
production with the current build too; leg A only survived by timing. Fix: OPA **1.0.25** collects
the region handles under the iteration and reads owned positions after it returns (one un-nested read
lock per region). Deployed on Survival for the second fork attempt and kept on the restored production
build; both booted clean. Lobby still runs the old collector.

Kill left no damage that a scan could find: after the restart on the original build, `testingworld`
had 0 loaded regions, the entity-region scan found 0 tagged records (1374 → 2163 records), the
`obench_sim` sandbox world was drained by OriginWorldEngine, and all configs were restored (below).

## Config rewrite by the newer Canvas (rollback hazard)

The fork (newer upstream) rewrote `config/canvas-server.yml` (146-line diff: drops
`enable-work-stealing`, `enable-mid-tick-tasks`, `thread-priority`, structure optimizations,
`async-protocol-switch`, `maximum-packet-bytes`, pearl fix; adds `prevent-excessive-velocity-move-out-of-region`,
`flush-location-while-knockback`, `cleaner-time-span`, `log-ender-pearl-rewrite-actions`) and
`config/canvas-worlds.yml` (adds `experience-orbs-immune-to-explosions`). Both were restored
byte-exact from the stopped-state backup on every return to the original build (hashes verified
`52a06338…` / `da7b6d08…`). Any real upgrade must carry these files deliberately.

## Cleanup accounting

Both completed legs reported `cleanupRemaining=44` (current) / `41` (fork) from OriginBenchmark
1.8.3-grouped7, the overlapping-sweep accounting already fixed in 1.8.4 (not deployed here to keep
the plugin set frozen). Verified independently each time: 0 loaded entities in `testingworld`
(OPA census 0 regions; `@e` count equalled the all-world total), 0 tagged saved entity records.

## Procedure and state

- Stopped-state backup `.canvas-ticket-qa/rollback-20260918T174427Z` (398,645 files, 14.7 GB new
  data hard-linked against the 09-15 copy, rsync dry-run verified) before any change.
- Linkage preflight of all 71 plugin jars against the fork runtime (offline paperclip extraction):
  0 unresolvable direct references (packetevents: 210 uninspected multi-release entries, as before).
- Every boot: 0 plugin enable errors, the same 8 pre-existing error lines (spawn data, agents, furniture
  overlay, `farm_foreman/talk`, config stream).
- Final production state: server.jar `12ef42d0…` (835), runtime `0f29a732…`, `canvas-server.yml`
  and `canvas-worlds.yml` byte-identical to the backup, OriginMobsEngine config restored (`ai-density.enabled: true`),
  OriginPerformanceAudit **1.0.25** (`OriginPerformanceAudit-1.0.24.jar.disabled-before-1.0.25` retained),
  OriginBenchmark 1.8.3 unchanged. Boot 19.1 s, 0 enable errors.
- Real players during legs: current 2 (one an OriginPilot `OP_` bot), fork 1; 3 players online when
  the restore restart was announced.

Evidence (hosting repo): `scratch/canvas-prod-ab/compare.json`, `scratch/canvas-prod-ab/evidence/canvas-prod-ab-20260918/{current,fork,fork-attempt1-opa-timeout}`
(census overviews, ramp logs, provenance, thread dumps, config diffs); full per-census `worlds-*.json`,
`profile.json` and server logs remain on origin under `/tmp/canvas-prod-ab-20260918/`.
