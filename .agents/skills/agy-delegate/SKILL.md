---
name: agy-delegate
description: >
  Delegate research and investigation tasks to the local Antigravity CLI (`agy`)
  via a sync Python wrapper and return its stdout. Best for web research, docs
  lookup, codebase surveys, API/framework behavior checks, and similar
  open-ended fact-finding.
---

# agy-delegate

Thin, script-first bridge: **you load this skill → run the script → return the result**.
Do not reimplement agy, summarize its output by default, or call `agy` outside the script.

## Recommended usage

**Prefer this skill for research and investigation**, not as the default for
every coding change. Hand agy a **self-contained research brief** with clear
scope, preferred sources, and desired output shape; keep one topic per `ask`.

### Strong fit (use proactively)

| Kind                             | When to delegate                                                                 | Prompt tips                                                                                   |
|----------------------------------|----------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| **Web / news research**          | Latest releases, RFCs, changelogs, security advisories, product announcements    | Ask for dates, version numbers, and primary sources (official docs, GitHub releases)          |
| **Docs & API lookup**            | How a library/framework API actually behaves, migration notes, deprecations      | Name package + version if known; request concise facts over tutorials                         |
| **Upstream source verification** | Docs alone are thin/ambiguous; need ground truth from library source             | Allow clone into **OS temp**; read/grep tagged revision; **never `rm` the clone** (see below) |
| **Codebase survey**              | “Where is X handled?”, architecture map, call-chain tracing, dead-code suspicion | Point at repo via `--path`; name modules/symbols; ask for file paths as evidence              |
| **Comparative investigation**    | Option A vs B, library choice, approach tradeoffs                                | Fix evaluation criteria; ask for a short table or bullet decision factors                     |
| **Environment / tool recon**     | What CLI flags exist, what a dependency version implies, CI/script behavior      | Prefer “inspect and report” over “change config”                                              |
| **Bug isolation (read-first)**   | Reproduce steps, likely root-cause hypotheses, which files to read next          | Require evidence (logs, stack traces, symbols); defer large rewrites                          |

### Weak fit (usually keep in the parent agent)

- Tiny factual answers you already know or can answer in one step
- Multi-step product work that needs ongoing conversation context
- Large refactors or feature builds better done in the parent session with full tool loop
- Tasks that need the parent’s private context (secrets, uncommitted intent not in the repo)

### How to write the prompt

1. **Goal in one sentence** — what question must be answered.
2. **Scope** — in/out of bounds (e.g. “official sources only”, “this repo only”).
3. **Method hints** — web search, read docs, grep/trace the codebase under `--path`.
4. **Deliverable** — e.g. short brief, bullet findings, file:line references, open questions.
5. **Constraints** — no code edits unless explicitly requested; prefer evidence over speculation.

Good research prompts are **closed enough to finish in one print run**, open enough
that agy can search and read. Avoid dumping the entire parent transcript; extract
the research question.

### Example prompts

Web research:

```text
Research the latest React releases and ecosystem news. Prefer react.dev and
GitHub facebook/react releases. Summarize versions/dates, notable changes, and
open roadmap items. Concise bullets with sources.
```

Codebase survey (run with `--path` = target repo root):

```text
Survey this repository for how offline cache invalidation works. List entry
points, key types, and the read/write path with file paths. Do not modify files.
Flag anything unclear.
```

Docs / API behavior:

```text
From current AndroidX Compose Material3 docs and sources if available, explain
when to use SecondaryTabRow vs ScrollableTabRow. Note version-sensitive API
names and common pitfalls. Short factual answer.
```

### Upstream source clone (confirm API facts)

When docs, blogs, or second-hand snippets are not enough, instruct agy to **clone
the upstream repository into the system temporary directory**, check out a
specific tag/commit when known, then **read and grep that tree** to verify API
behavior (signatures, defaults, deprecations, call paths).

**Workspace split**

| Path                | Role                                                                                                            |
|---------------------|-----------------------------------------------------------------------------------------------------------------|
| `--path` (required) | Parent project / current workspace cwd for the agy process (e.g. this app repo). Not where the clone must live. |
| OS temp clone       | Disposable upstream checkout used only for investigation                                                        |

**Where to clone (agy should pick an existing temp root, not invent a permanent folder)**

| Platform            | Prefer (in order)                                                                 |
|---------------------|-----------------------------------------------------------------------------------|
| **Windows**         | `%TEMP%` or `%TMP%` (usually under the user profile), else `TMPDIR` if set        |
| **macOS / Linux**   | `$TMPDIR` if set, else `/tmp`                                                     |
| **Any with Python** | Directory reported by `python -c "import tempfile; print(tempfile.gettempdir())"` |

Example layout (agy chooses a unique subdir name):

```text
{temp}/agy-research-androidx-compose-material3-<tag-or-sha>/
```

Shallow clone is preferred when only history at a tag is needed, e.g.
`git clone --depth 1 --branch <tag> <url> <temp-subdir>`.

**Hard rule: do not delete the clone**

- Instruct agy: **do not `rm` / `rm -rf` / `Remove-Item -Recurse` the temp checkout**
  after the investigation.
- Reasons: (1) delete-tree commands often trip sandbox / safety policy; (2) OS
  temp cleanup already owns the lifecycle.
- Parent agents must **not** “helpfully” clean up either. Leave the directory.
- The written answer should still cite **file paths (and line ranges if useful)**
  under the temp clone so findings stay auditable until the OS reclaims space.

**What to put in the prompt**

1. Exact question (which API / behavior / version).
2. Upstream repo URL + version pin (Maven/npm coordinate, git tag, or commit).
3. Permission to clone under OS temp (list the temp options above if helpful).
4. Method: search/read source; prefer primary definitions over comments alone.
5. Deliverable: factual answer + evidence paths; open questions if source lacks proof.
6. Constraints: **no edits** to the user project unless asked; **no cleanup/`rm`** of temp clones.

**Example prompt**

```text
Confirm from upstream source (not blog posts) how Compose Material3 SecondaryTabRow
handles scrollable overflow vs PrimaryTabRow, for the version pinned in this app's
Gradle catalog if present, otherwise the latest stable tag you can resolve.

You MAY clone the relevant AndroidX/Compose repository into the system temporary
directory to inspect source. Prefer an existing temp root:
  - Windows: %TEMP% or %TMP%
  - macOS/Linux: $TMPDIR or /tmp
  - or: python -c "import tempfile; print(tempfile.gettempdir())"
Use a unique subdirectory name; shallow clone a specific tag/commit when possible.

Investigate by reading/grepping the checkout. Answer with concrete API facts and
cite file paths (and lines) under that clone.

Do NOT modify this application project.
Do NOT delete or rm the temporary clone afterward — leave it for OS temp cleanup
(avoids delete-related safety blocks; temp is ephemeral by design).
```

**Invocation**

```text
python /abs/path/.agents/skills/agy-delegate/scripts/agy_run.py ask --path /abs/path/to/app-repo "<prompt above as one argument>"
```

`--path` stays the **app/workspace** root. The clone lives under **temp**, not
under the app tree.

## Resolve paths

- Skill root: directory that contains this `SKILL.md`.
- Runner: `<skill-root>/scripts/agy_run.py`
- Python: try `python`, then `py -3`, then `python3`.

Always invoke the runner with an **absolute** path to `agy_run.py`.

## Happy path (one call)

```text
python <skill-root>/scripts/agy_run.py ask --path <project-dir> "<prompt>"
```

- `--path` is **required**. Must be an existing directory (absolute path preferred).
  For pure web research, still pass a real workspace root (e.g. the current repo);
  agy uses it as cwd even if the task is not about that tree.
- `<prompt>` is **required**. Pass as **one** shell argument (quote it).
- The script `chdir`s to `--path` (via subprocess cwd), then runs:
  `agy --dangerously-skip-permissions -p "<prompt>"`
- No `--model`, timeout, or sandbox flags — agy defaults only.
- **Stdout** is the result. On success, return it **verbatim** to the user/parent
  (only summarize if they explicitly ask).
- Script diagnostics go to **stderr**; do not mix them into the user-facing result.

### Invocation examples

```text
python /abs/path/.agents/skills/agy-delegate/scripts/agy_run.py ask --path /abs/path/to/repo "Research the latest React news; prefer official sources; concise bullets with versions and dates."

python /abs/path/.agents/skills/agy-delegate/scripts/agy_run.py ask --path /abs/path/to/repo "Map how navigation args are parsed in this app. Cite files. No edits."
```

## Optional: environment probe

Not required before every `ask`. Use when install/auth is unclear or `ask` failed:

```text
python <skill-root>/scripts/agy_run.py check
python <skill-root>/scripts/agy_run.py help
```

`check` prints one JSON object on stdout (`installed`, `path`, `version`, `auth`, `error`).

## Exit codes

| Code | Meaning                                  |
|------|------------------------------------------|
| 0    | Success (`ask` / `check` / `help`)       |
| 1    | agy found but auth/run failed            |
| 64   | Usage error (missing args, bad `--path`) |
| 127  | `agy` / `agy.exe` not found              |

On non-zero exit: report stderr (and exit code). Do not invent a substitute answer.
If 127 or auth-looking 1, suggest installing/logging into Antigravity CLI (`agy` once, or
`ANTIGRAVITY_API_KEY`).

## Rules of engagement

1. **One `ask` per user task** unless the user requests another round.
2. **Do not** pass extra agy flags; the wrapper owns non-interactive defaults.
3. **Do not** patch `~/.gemini/antigravity-cli/settings.json` or select models.
4. **Do not** skip `--path` or invent a default directory.
5. Prefer this skill over ad-hoc `agy` shell calls when delegating work to Antigravity.
6. For upstream clone research: put checkouts under **OS temp**, require evidence
   paths in the answer, and **never `rm` the clone** (parent or agy) — OS temp
   cleanup owns the lifecycle.

## What this skill does not do

- Background/async jobs
- Model selection
- Image generation / git-diff review helpers (use agy itself or other skills)
- Installing or authenticating agy for the user
