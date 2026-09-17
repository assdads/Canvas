# Ticket-maintenance candidate reference

This file preserves the original standalone hunk. Its applied counterpart is committed in `canvas-server/minecraft-patches/sources/ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkHolderManager.java.patch`.
Full baseline/candidate builds and isolated two-boot force-load/save smoke runs passed. There is
no production deployment or benchmark result yet; timed-ticket expiry, unload completion, player
teleports and production integrations still need checking. See [current status](../ORIGIN.md).

The sections below record the earlier fixture work and original integration checklist.

`no-expiry-postwork.patch` skips post-expiration bookkeeping for one chunk when
`TicketSet.expireAndRemoveInto` returns zero. It does not skip timer advancement, section-level
pending update propagation, locks, or final ticket updates. No view/simulation distance, delivery
rate or unload lifetime changes are intended.

## Status

**Candidate only. Not wired into this fork's build and not deployed.**

Previously compiled and fixture-tested against the exact Canvas 26.2 build-835 source chain:

- Canvas `b276251774e34725aa900b810e34b3c4a72c3b68`
- Folia `bbbce7d375258e26de216c7ed1abfd64fac0c95c`
- Paper `a1e989c03643f812772d2213b087d34f6d917d49`
- Runtime JAR SHA-256 `0f29a7320a7e2163486fdae6c2057f9ee93162a506177b54294eb1d5a6fefc0b`

That verification compiled the full unchanged/candidate class, compared the baseline tick methods'
bytecode with the runtime, and passed 30,038 fixture assertions including 10,000 seeded transitions.
It did not build or boot a full server. Isolated no-expiration fixture loops showed modest savings;
expiration-heavy cases were mixed. No whole-server speedup or allocation saving is established.

The complete old-source verification workspace remains at `hosting/scratch/ticket-maintenance/`
on the maintainer's workstation; it relies on local server dependency JARs and is not a portable
fork CI test. Nothing from production configs or those JARs is copied here.

## Latest-source compatibility

At fork creation, upstream main was `ddc374bb25e0f1e3cb6836aff89b538cbc10a919`, with Paper base
`a2a42c5b12249aaba42a347327fd930a1f94af06`. Its Moonrise patch still contains the same target
sequence after `expireAndRemoveInto`, and the exact TicketSet source matches the earlier base.
This establishes that the candidate is still relevant, **not** that it is safe or built on the
newer full source tree.

Before integration:

1. Build the generated source with `gradlew applyAllPatches` on the pinned branch.
2. Inspect the generated `ChunkHolderManager.tick()` and every relevant upstream delta.
3. Apply the candidate in that generated source repository only after `git apply --check` succeeds.
   The patch paths are relative to the generated Java source root, not this repository root.
4. Rebuild the appropriate Canvas source patches using its documented patch workflow. Keep a
   separate OriginWorks commit; do not regenerate unrelated patches or commit Minecraft sources.
5. Re-run differential tests against the new baseline and build `createPaperclipJar`.
6. Test actual renewal/expiry, unload/save, teleport, async completion, and region merge/split
   behavior on an isolated server before a guarded benchmark. Do not deploy on compile evidence alone.

See [ORIGIN.md](../ORIGIN.md) for the non-force-push upstream update workflow.
