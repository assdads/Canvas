# OriginWorks Canvas fork

Upstream: https://github.com/CraftCanvasMC/Canvas
Fork: https://github.com/assdads/Canvas

This fork tracks **Minecraft 26.2 / Java 25**. Fetching or merging source never deploys to a server.
Retain upstream licensing and attribution. Do not commit server worlds, production configuration,
credentials, generated Minecraft sources, or build artifacts to this public repository.

## Branches

- `main`: clean upstream mirror. Do not put OriginWorks edits here; GitHub's **Sync fork** targets this branch.
- `origin-patches`: shared integration branch, initially based on upstream `main`
  `ddc374bb25e0f1e3cb6836aff89b538cbc10a919`. Keep small, separate commits for our changes.
- `origin/live-baseline-26.2-835`: annotated tag at
  `b276251774e34725aa900b810e34b3c4a72c3b68`, the previously verified Survival source base.
  This is a historical reference, not a newly built release or proof of current deployment.

The initial upstream main declares `mcVersion = 26.2`, `apiVersion = 26.2`, `channel = STABLE`
and `paperRef = a2a42c5b12249aaba42a347327fd930a1f94af06`. The live-base tag used an older
Canvas-to-Folia-to-Paper build layout. Same Minecraft version does **not** imply identical
scheduler, config, API, or plugin behavior. Treat the upgrade as separate from our ticket patch.

## Current patch status

### Stacked comparison, fork vs clean upstream (2026-09-18)

Fork `e9e038ae` (every retained patch, old Paper base) against the unmodified upstream build
`366dd16e`, four ABBA legs per workload on the clone. Scattered 100 bots: hottest-region MSPT
**-12.8%**, container CPU **-6.5%**, live heap **-14.1% (-761 MB)**, every fork leg below every
upstream leg. Mixed crowd (64 bots + 1,280 mobs, one region): **no measurable change** in four
legs (-1.2% MSPT, -0.6% CPU, legs overlap). See
[stacked-fork-vs-upstream-results.md](origin-candidates/stacked-fork-vs-upstream-results.md).

### Upstream sync (2026-09-18)

`main` fast-forwarded to upstream `4a0ed14a` ("Update Upstream (Paper)", `paperRef`
`a2a42c5b` -> `e5fe71723e2ffde7cc9fafc085ac3bb73e63175e`) and merged into `origin-patches` as
`42bee392`. `applyAllPatches` re-derived the sources on the new Paper base with every fork patch
applying cleanly; full build passed (canvas-api 519, canvas-server 9,319 tests, 0 failures; jar
`1049eddb...`). That merged jar has **not** been benchmarked: every measurement below, including
the stacked fork-vs-upstream comparison, was taken on builds from the previous Paper base.

### Grass light read through the held chunk (2026-09-18)

`SpreadingSnowyBlock.randomTick` now reads the light check through the `LevelChunk` it already
looked up, via a new `StarLightInterface.getRawBrightness(BlockPos, int, ChunkAccess)` overload,
instead of a second concurrent chunk-table lookup for the same chunk. Eight reset-isolated legs
(ABBA then BAAB, 100 scattered bots): container CPU -7.5% with every candidate leg below every
baseline leg; hottest-region MSPT -4.9% but with overlapping legs; heap flat. Profile:
`getRawBrightness` share 4.26% -> 1.14%, grass `randomTick` 8.59% -> 6.11%, unrelated paths flat.
Same light values by construction (the held chunk is the chunk the lookup returns, or a wrapper
that delegates every read to it). Experimental. See
[grass-light-held-chunk-results.md](origin-candidates/grass-light-held-chunk-results.md).

### Random-tick section bitmap (2026-09-18)

Each `LevelChunk` keeps a `long[]` of sections that may hold random-ticking blocks, updated by
`LevelChunk.setBlockState` and rebuilt every 20 passes; `optimiseRandomTick` visits only flagged
sections, still checked live and in ascending order. Eight reset-isolated legs (ABBA then BAAB,
100 scattered bots): hottest-region MSPT -5.7%, container CPU -6.0%, every candidate leg below
every baseline leg; the scan's share of region samples 30.2% -> 23.9% with block callbacks flat.
A section activated behind the chunk (direct section write or array replacement) can miss up to
20 passes before the rescan sees it; it is never ticked when inactive. Experimental. See
[random-tick-section-mask-results.md](origin-candidates/random-tick-section-mask-results.md).

### Ticket expiry bookkeeping (2026-09-17)

`ChunkHolderManager.tick()` no longer re-looks-up each expiring chunk's `TicketSet` in the
concurrent ticket table; the section index now holds the set and the set carries its own
expiring-ticket count. Full build/tests pass and eight reset-isolated legs (ABBA then BAAB, 100
scattered bots) cut the ticket-maintenance share of region samples from 16.4% to 13.8%, but
whole-process CPU (-4.0% then +0.7%) and region MSPT are within noise. Experimental. See
[ticket-lookup-results.md](origin-candidates/ticket-lookup-results.md).

### Vine traversal experiment (2026-09-16)

A separate `VineBlock.canSpread` candidate removes iterable/iterator overhead while preserving
all block reads and their order. Full build/tests, a 300-case traversal regression, an opt-in
allocation probe and four reset-isolated ABBA runs passed. Method-level savings are measured;
a server-wide gain is not established. See [evidence and limitations](origin-candidates/vine-spread-results.md).
The offline clone was restored and left stopped; production was not changed.

### Earlier ticket candidate

The five-line ticket candidate is now applied locally through
`canvas-server/minecraft-patches/sources/ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkHolderManager.java.patch`.
The source change and full `test createPaperclipJar` build passed; the server test XML contains
9,278 entries with no failures/errors. `origin-candidates/no-expiry-postwork.patch` remains the
original reference hunk. The applied patch is committed as an experimental change: every
baseline and candidate build measured for the later retained optimizations (vine traversal,
deferred collision boxes, random-tick layout, lazy tick lists/maps, activation exclusions,
shared zero storage) contained this hunk in BOTH legs, so it is part of the tested source state.

Matched baseline and candidate distributions each passed two fresh-world boots, explicit forced
chunk-ticket add/remove commands, graceful saves and marker-block persistence across restart in
an isolated QA directory on the Survival host. Additional isolated probes observed both ticket
and chunk-holder records disappear after release, then reloaded the saved marker successfully.
Exact expiry-tick timing and real-player teleport behavior were not measured.

Both distributions then completed a guarded 500-bot/10,000-spawned-mob comparison on Survival.
Plugin JAR and recorded configuration fingerprints matched between legs; only the launcher and
extracted runtime differed. Hottest-region two-snapshot means were 32.990 ms baseline and
30.725 ms candidate, but the ticket-maintenance inclusive sample share rose from 6.762% to
7.367%. With only one sequential pair, varying entities/chunks and one additional player, this
is NOT a demonstrated ticket-CPU saving. No new linkage or chunk load/save exceptions were found;
existing plugin configuration errors remain separate. Survival was restored to its pre-test
26.2-835 launcher/runtime, with density enabled and distances unchanged. No production worlds
were rolled back. The candidate stays experimental; no production deployment is authorized by it.

The applied OriginWorks test patch routes custom-ingredient cases through the Bukkit registration
constructor and adds two branch-routing regressions. It changes no runtime code.

## Initial remote setup on another machine

```sh
git clone https://github.com/assdads/Canvas.git
cd Canvas
git remote add upstream https://github.com/CraftCanvasMC/Canvas.git
git remote set-url --push upstream DISABLED
git config remote.pushDefault origin
git config pull.ff only
git config fetch.prune true
git switch --track origin/origin-patches
```

`origin` is our fork; `upstream` is read-only by convention. The invalid push URL prevents an
accidental push to CanvasMC. Use your normal GitHub credential helper; do not put tokens in URLs,
scripts, commits, or shell history.

## Bring in upstream updates

Run from a clean worktree. Stop on any failed command or unexpected local changes.

```sh
git status --short
git fetch origin
git fetch upstream
git show upstream/main:gradle.properties
git log --oneline main..upstream/main
git diff main..upstream/main -- gradle.properties build.gradle.kts canvas-server/build.gradle.kts.patch
```

Confirm upstream is still **26.2** before proceeding. If it moves to a different Minecraft version,
stop and select a verified 26.2 branch or commit; do not merge the version jump. Review upstream
changelog/configuration/threading changes before building or running it.

```sh
git switch main
git merge --ff-only origin/main
git merge --ff-only upstream/main
git push origin main
git switch origin-patches
git pull --ff-only origin origin-patches
git merge --no-ff --no-commit main
```

Inspect the staged merge and any conflicts. If there is nothing new, Git reports already up to date.
If there are conflicts, resolve them deliberately or use `git merge --abort`. Do not force-push,
reset the shared branch, or blindly choose one side. Merge commits preserve both upstream history
and our local changes without rewriting collaborators' commits.

Before committing an update, run the source/build gates below, inspect `git diff --cached`, then:

```sh
git commit -m "Merge Canvas 26.2 upstream updates"
git push origin origin-patches
```

This is a reviewed workflow, not a scheduled auto-merge or auto-deploy. Reapply or retire individual
OriginWorks patches when upstream changes or independently fixes the same code.

## Source and build gates

Use the wrapper with **Java 25** and a configured Git author. The task names below are from the
pinned upstream README/workflow; recheck them if upstream changes its build system.

```sh
./gradlew applyAllPatches
./gradlew test
./gradlew createPaperclipJar
```

Windows: use `.\gradlew.bat` instead of `./gradlew`. Patches to generated Minecraft/Paper code must
be rebuilt through Canvas's patch workflow, not committed as generated source trees. Consult
[upstream's contributing guide](https://docs.canvasmc.io/canvas/developers/contributing/canvas/).
The checked-in `.github/workflows/test-pr.yml` also documents upstream's patch-rebuild checks.

A successful merge is not a successful build; a successful build is not a safe deployment.

### Baseline verification and test-fixture correction (2026-09-15)

At fork HEAD `e541e8e4`, the unmodified server suite failed
`ShapelessRecipeMatchTest.predicate_plusRegular_renamedSatisfiesPredicateOnly` twice. The inherited
Paper fixture used Canvas's four-argument `ShapelessRecipe` constructor, which sets `isBukkit=false`
and selects the Pufferfish greedy `Ingredient.test` matcher. The custom-recipe registration path in
`CraftShapelessRecipe.addToRecipeManager` passes `true`, selecting the Paper exact/predicate matcher
that this test describes. The test was exercising the wrong branch, not a ticket-patch regression.

The narrow test patch makes the fixture use `isBukkit=true`. The original rejection assertion is
unchanged. Two new tests independently cover default-constructor renamed regular items and Bukkit
predicate/exact assignment in both input orders. All 19 shapeless cases pass; the targeted
`VanillaFeatureTestSuite` and full `test createPaperclipJar` command pass. The patch survives
`applyAllPatches`; regeneration changes only its one test patch. Runtime Minecraft sources remain
unchanged and the baseline paperclip SHA-256 is unchanged:
`dc7b4c85c6f5ea9080eece86318aea1fafcd020b8c295995c1987751aa662d50`.

The ticket candidate remains inactive. No deployment or live benchmark followed these local gates.
On Windows, use process-scoped Git author variables and `core.longpaths=true` for Weaver's generated
repositories. Run fixup and rebuild tasks as separate invocations: scheduling both together can race
the generated repository's `file` tag. Do not skip failing tests or change expected results merely
to publish a build.

Before any server upgrade, test an isolated copy for boot, plugin integrations, chunk loading,
teleports, ticket renewal/expiry, region merges/splits, saves and unloads. Benchmark the upstream
upgrade separately from the ticket candidate with unchanged distances and tick behavior. Keep a
known-good server/world backup and a verified rollback. Require an explicitly selected deployment
server and a maintenance window; this repository has no deployment automation.

Upstream PRs are a separate action: follow `CONTRIBUTING.md` and `AI_POLICY.md`, obtain human review,
and include reproducible evidence. Creating this fork does not submit anything upstream.
