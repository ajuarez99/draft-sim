# Project subagents

Custom Claude Code subagents for this repo. Claude Code loads every `*.md` under
`.claude/agents/` (subfolders included) and picks one by its `description` when a
task matches, or when you name it ("use the bug-hunter on spec 058"). Each file is
YAML frontmatter (`name`, `description`, `tools`, `model`, `color`) followed by the
agent's system prompt. A subagent starts with a fresh context: it gets its prompt,
your request, `AGENTS.md` and a git status snapshot, nothing from the
conversation. That is why each prompt restates the repo rules it depends on.

Copied from the Gauntlet repo's `.claude/agents/` on 2026-11-10 and rewritten the
same day for this repo's stack and rules, from `AGENTS.md`, `claude/lessons.md`,
`claude/design/README.md` and the comment block at the top of `web/src/styles.css`.
They were written from a checkout of branch `017-nba-schedule-grid`, so file and
component names were checked against that tree, not against `main`.

## pipeline/ — the build pipeline from AGENTS.md

| Agent | Model | Writes | Use |
|---|---|---|---|
| `plan-reviewer` | inherit | `specs/<f>/plan-review.md` | Adversarial review of a plan, cold, against the real code, before tasks |
| `bug-hunter` | inherit | `specs/<f>/code-review.md` | Bug-hunting code review after implement; runs suites, reads the skipped count, reproduces |
| `live-verifier` | inherit | `specs/<f>/verification.md` | Drives bootRun and the web UI in the browser pane, both sports, odd-state leagues |
| `builder` | sonnet | code | Any coding task (AGENTS.md: coding subagents run on Sonnet) |
| `ordering-tester` | sonnet | tests | Preference-ordering and exact-output tests for scoring, ranking and aggregation (bug class 1) |
| `repo-rules-auditor` | sonnet | nothing | Mechanical check of the diff against the hard rules before a commit |
| `handoff-scribe` | sonnet | `HANDOFF.md`, `claude/lessons.md`, dated amendments | End-of-stage documentation in the repo's own format |

## design/

| Agent | Model | Writes | Use |
|---|---|---|---|
| `design-reviewer` | sonnet | nothing | Critique of a page against the chosen direction, the `styles.css` rules and Nielsen's heuristics |
| `ui-designer` | opus | nothing | A screen design proposal (layout, numbers and labels, states, both sports, color, a11y) before coding |
| `accessibility-auditor` | haiku | nothing | Static WCAG 2.2 AA checklist for the web UI; names what needs a browser |
| `copy-editor` | sonnet | nothing | User-facing text: backed claims, labels that name the number, naming, fan words, text that reads generated |

## security/ (read-only by tool allowlist)

| Agent | Model | Use |
|---|---|---|
| `security-diff-reviewer` | sonnet | Security review of a diff or the whole codebase: access control, fail-open identity, injection, secrets, CORS, costed abuse, supply chain |

## Not carried over from Gauntlet

`aso-listing-writer`, `store-compliance-checker` and `growth-strategist` (app-store
listings and a share-grid growth loop this site does not have),
`auth-protocol-auditor` (JWT, Apple and Google sign-in; this app has none),
`api-data-reviewer` (Go, sqlc, OpenAPI) and `supply-chain-auditor` (govulncheck,
Expo). The parts that apply here were folded into `security-diff-reviewer`.
`fairness-tester` became `ordering-tester`.

## Loading

Agent files are read when a session starts. A file added or renamed during a
session is not a selectable agent type until the session is restarted. Until
then, the equivalent is a general-purpose agent on the file's `model`, told to
read the file and follow it.

## Conventions

- Reviewers and auditors never edit project code. They report with `file:line`,
  mark findings VERIFIED or REASONED, and leave fixes to the parent session.
- Only `builder` and `ordering-tester` write code, and both run on Sonnet.
- Models: the plan review, bug hunt and live verification use the session's
  model (`inherit`), as `AGENTS.md` § Subagents says; coding is on Sonnet. The
  other pins (`ui-designer` on Opus, the checklists on Sonnet or Haiku) are
  carried over from Gauntlet and are Allan's to change.
- Every agent that runs the backend suite reads the skipped count, and none runs
  it on a branch with a destructive migration without asking: the integration
  tests apply Flyway to the shared `localhost:5433/draftsim`.
- To add an agent: one job per file, a one-line trigger `description`, the rules
  it needs restated in the body, an explicit `tools` list, and an output template.
