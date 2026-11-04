# Triage Report — SVY-21626

**Verdict:** NEEDS_INPUT

## Reported problem
The shipped Servoy AI skill pack (repo `skill4servoy`, under
`.opencode-shippable/skills/servoy-agent-shared/context/`) documents only the
**project-centric** way of calling the `servoy-git` MCP tools: every git tool is
described as taking a `projectName`, the rails insist that `projectName` must be a
**solution name and never a module subdirectory**, and they warn about the
empty-commit pitfall that follows from passing a module folder.

SVY-21524 (this repo, commit `948be97`, 2026-10-15) changed the MCP: every
`servoy-git` tool now also accepts an optional **`repo`** parameter (resolve a
repository directly by name or working-tree path, independent of any Eclipse
project) and a new discovery tool **`gitListRepositories`** was added.
`projectName` stayed optional and backward-compatible. The empty-commit guard
also landed as `334ef86` (SVY-21524).

So the symptom is purely a **documentation drift**: the skill pack teaches an
older, narrower calling contract than the MCP now supports. There is no runtime
defect in either repo — this is a doc-update task that the ticket has already
scoped.

## Root-cause assessment
This is not a code bug; it is a planned follow-up to SVY-21524. The evidence:

- **The new MCP contract exists and is on `master`.** `git show 948be97 --`
  `ServoyGitServer.java` shows a shared `REPO_PARAM_DESC` added to every tool
  (`gitStatus`, `gitLog`, `gitAdd`, `gitCommit`, `gitDiff`, `gitBranch`,
  `gitCreateBranch`, `gitDeleteBranch`, `gitCheckout`, `gitReset`, `gitStash`,
  `gitStashPop`, `gitStashList`, `gitStagePatch`) with `projectName` relaxed to
  `required = false`, plus the new `gitListRepositories` tool. `GitService.java`
  (+298 lines) and the plainjunit test (`5f03f0e`) were updated in lock-step.
  These three commits (`334ef86`, `948be97`, `5f03f0e`) sit on `master` /
  `origin/master`; there are no tags in this repo to tell me which build they
  shipped in.

- **The skill files still document the old contract** (repo `skill4servoy`,
  branch `master`, HEAD `fec3fd1`):
  - `context/mcp-tooling.md` §"Git — servoy-git is the only guaranteed git path"
    (lines ~649-682): the git tool table lists `gitStatus`/`gitLog`/`gitBranch`/
    `gitStashList`/`gitAdd`/`gitStagePatch`/`gitReset`/`gitCommit`/`gitInit`, all
    described as taking the **Eclipse `projectName`**. No `repo` param, no
    `gitListRepositories`. Lines ~661-664 carry the "`projectName` is a SOLUTION
    name, never a module subdirectory … a module subdirectory … produces an empty
    commit" rail.
  - `context/tool-addressing.md` §"Which path goes in which argument" (lines
    ~99-131): "`servoy-git_*` is the exception — that one wants the solution
    name" (line 102) and the dedicated rail "`servoy-git_*` wants a SOLUTION name,
    and the workspace folder name is never one" (lines 125-131), including the two
    refusal-message meanings (`Project is not mapped to a Git repository` vs
    `Project not found`, mcp-tooling.md lines 666-672).

  These are exactly the three edit targets the ticket enumerates.

The ticket's description matches the code state precisely, and its own bullet list
is an accurate work plan. There is nothing to challenge in the *content* of the
change.

## Ticket premise check
The ticket proposes a concrete, correct update (teach `repo` +
`gitListRepositories`; relax the solution-name/empty-commit rails in both files;
extend the git tool tables). That premise holds — the MCP really does support the
new contract, and the skill files really are behind it. I found no simpler or more
correct alternative to the described edits themselves.

**But the ticket also states its own hard blocker, and that blocker is the real
subject of this triage.** The ticket says, verbatim:

> The new servoy-git MCP is in a shipped Servoy Developer build. The same skill
> files ship in builds that still carry the OLD git MCP (no repo param / no
> gitListRepositories); updating the skills before the new MCP ships would break
> those builds. Keep the current prose rails until then.

The skill pack ships *inside* Servoy Developer (`SERVOY_SKILLS_ZIP`, extracted to
`~/.servoy/opencode/skills/**` — see Servoy-Copilot `AGENTS.md`, the
`SkillsZipExtractor` row). A given skill-pack version is consumed by whatever MCP
the surrounding Developer build carries. If the pack starts telling agents to pass
`repo` / call `gitListRepositories` while running against a Developer build whose
MCP predates `948be97`, those calls fail (unknown tool / unknown argument). That
is a legitimate ordering constraint, not a stylistic preference.

This triage **cannot determine from the repositories whether the gate is open.**
Whether SVY-21524's MCP is in a *shipped* (customer-reachable) Servoy Developer
build is a release-state fact: there are no tags in Servoy-Copilot, the skill pack
versions independently, and "shipped" depends on which Developer release train
(`2026.03` / `2026.06` / the `2026.12` fixVersion on this ticket) actually carries
commit `948be97`. That is a human release-coordination decision, and attempting to
infer it from code would be guessing.

## Approaches considered
1. **Do the edits now (as the ticket describes), unconditionally** — pros: the
   work is well-scoped and low-risk in isolation; convergent, no ambiguity about
   *what* to write. Cons: directly violates the ticket's stated blocker — ships a
   pack that breaks every in-field Developer build still on the old MCP. Not
   acceptable until the gate is confirmed open.

2. **Do the edits additively / backward-compatibly** (teach `repo` +
   `gitListRepositories` as *optional* while keeping `projectName` working, and
   keep a short solution-name note rather than deleting the rail) — pros: because
   `projectName` stayed `required = false` and fully backward-compatible, prose
   that merely *adds* the `repo` option would not itself break old-MCP builds, as
   long as it does not instruct agents to *call* `gitListRepositories` (which does
   not exist on the old MCP). Cons: still risky — the moment the doc tells an agent
   to prefer `gitListRepositories`, an old-MCP build fails; getting the "mention
   but don't rely on it" line exactly right is fragile, and the ticket explicitly
   says "keep the current prose rails until then", i.e. it does *not* want a
   half-measure now. This is a candidate *once the gate is confirmed*, not a way
   around the gate.

3. **No code change (yet) — hold until the gate opens** — pros: honours the
   ticket's own blocker; zero risk to shipped builds; the work is trivial to do
   later and the plan is already written. Cons: the ticket stays open; someone has
   to track the release and re-trigger. This is the correct holding state, but it
   is not a *verdict* the agent should pick unilaterally, because only a human
   knows whether the gate is already open.

## Recommendation
**NEEDS_INPUT.** The *content* of the change is convergent and already correctly
specified by the ticket — there is no divergence in what to edit. The single thing
that cannot be settled by investigation is the **release gate the ticket itself
raises**: has SVY-21524's `repo` / `gitListRepositories` MCP reached a shipped
Servoy Developer build that the `skill4servoy` pack will run against?

- If **yes, it has shipped** (and all supported Developer builds the pack targets
  carry it) → this becomes **PROCEED** with Approach 1: make the three edits the
  ticket lists in both files. The edits live in `skill4servoy`
  (`.opencode-shippable/skills/servoy-agent-shared/context/mcp-tooling.md` and
  `tool-addressing.md`), **not** in this Servoy-Copilot repo — note the triage
  report is written here only because this is the repo the SDD run is anchored in.
- If **not yet** → **hold** (Approach 3): keep the current prose rails exactly as
  they are, per the ticket's instruction, and leave the ticket blocked until the
  release lands.

The agent should not guess the release state, so the decision is handed to the
reporter.

## Git history findings
- `948be97` *SVY-21524 servoy-git tools can target a repository directly, add
  gitListRepositories [ai]* — 2026-10-15, on `master`/`origin/master`. Adds the
  `repo` param to all 14 git tools and the `gitListRepositories` tool; 3 files,
  +415/-100. No tag contains it (repo has no tags), so the shipping build cannot
  be read from git.
- `334ef86` *SVY-21524 servoy-git gitCommit no longer writes an empty commit when
  nothing is staged [ai]* — the empty-commit guard the skill-pack pitfall warns
  about.
- `5f03f0e` *SVY-21524 update plainjunit ServoyGitServerTest for the new repo
  parameter [ai]* — tests updated for the new signature.
- `skill4servoy` HEAD `fec3fd1`: the two target skill files still carry the
  project-centric prose unchanged — confirming the drift the ticket reports.
- No `docs/` spec exists for SVY-21626 or SVY-21524 in either repo.

## Questions for the reporter
1. Has SVY-21524's new `servoy-git` MCP (the `repo` parameter + `gitListRepositories`,
   commit `948be97`) been released in a **shipped Servoy Developer build** yet — and
   if so, in which release train (`2026.03` backport / `2026.06` / `2026.12`)?
2. Do **all** Servoy Developer builds that the `skill4servoy` pack will run against
   now carry that MCP, or are there still in-field builds on the old git MCP (no
   `repo` param, no `gitListRepositories`) that this same pack version must not
   break?
3. Once the gate is confirmed open, do you want the **full** update the ticket
   describes (teach `repo` + `gitListRepositories`, and relax/replace the
   "solution name, never a module subdirectory" and empty-commit rails), or a
   **backward-compatible additive** pass that introduces the `repo` option while
   keeping the existing `projectName` guidance (Approach 2)?
