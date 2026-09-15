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

`origin-candidates/no-expiry-postwork.patch` is an **unapplied** five-line candidate for generated
`ChunkHolderManager.java`. It is deliberately outside Canvas's build patch directories. Ordinary
builds do not include it. See [the candidate record](origin-candidates/README.md) for verification
and limits. No custom Canvas release has been built or deployed from this fork yet.

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
Before any server upgrade, test an isolated copy for boot, plugin integrations, chunk loading,
teleports, ticket renewal/expiry, region merges/splits, saves and unloads. Benchmark the upstream
upgrade separately from the ticket candidate with unchanged distances and tick behavior. Keep a
known-good server/world backup and a verified rollback. Require an explicitly selected deployment
server and a maintenance window; this repository has no deployment automation.

Upstream PRs are a separate action: follow `CONTRIBUTING.md` and `AI_POLICY.md`, obtain human review,
and include reproducible evidence. Creating this fork does not submit anything upstream.
