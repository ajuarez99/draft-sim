# Feature Specification: Codebase Cleanup — Refactor & Removal Plan

**Feature Branch**: `021-codebase-cleanup`

**Created**: 2026-10-07

**Status**: Draft

**Input**: User description: "ok you are a lead engineer skimming through our codebase. just to see what pops out that needs a refactor or whatever files are necessary. come up with a refactor/removal plan"

> **Basis.** This came from a skim of `origin/main` at `7b7238e`, not a full audit.
> Everything in the Inventory below was **measured** by a command run against that
> tree, such as line counts, grep hits or importer counts. Where an item says
> "candidate", nobody has confirmed it is actually unused at runtime; it only had no
> caller in `web/src`. The original checkout was on the stale `017-nba-schedule-grid`
> branch, three specs behind main, so this skim deliberately ignored it.

## User Scenarios & Testing *(mandatory)*

The "users" of this feature are the people and agents who maintain Ball Knowers.
League members must not notice anything. That is the main acceptance bar.

### User Story 1 - Remove what nothing needs (Priority: P1)

A maintainer opens the repo and every committed file either ships, tests something
that ships, or documents a decision. Dev-only harnesses, reference screenshots served
to production visitors, and stale local branches and worktrees are gone. The tree
reads as "what the product is" and not "everything any session ever tried".

**Why this priority**: This is the cheapest work, the safest, and the most visible.
It also shrinks the surface every later refactor has to reason about.

**Independent Test**: Delete the P1 items, then confirm the full backend suite, the
frontend tests and the production build all still pass, with the same skip count as
before. Every user-facing route must still render the same page.

**Acceptance Scenarios**:

1. **Given** the production frontend bundle, **When** someone requests
   `/pr-reference/*.png`, **Then** the response is not an image (content-type is not
   `image/png`). Those mockups are no longer published to the public site.
   *Amended after review (finding 11): the first draft said "returns not-found", but
   production's SPA fallback answers any missing path with `200 text/html` (measured), so
   a 404 never comes. The check is on content-type.*
2. **Given** a dev build, **When** someone navigates to `/leagues/:id/power/verify`,
   **Then** there is no such route, and the real Power Rankings page is unchanged.
3. **Given** the local machine, **When** a maintainer runs `git worktree list` and
   `git branch --merged main`, **Then** only active work remains, and nothing that
   held unmerged commits was deleted.

---

### User Story 2 - One rule, one implementation (Priority: P2)

A maintainer changes how a number is rounded or how a place is written ("1st",
"2nd"), and makes that change in exactly one file. Today the same small rules have
been reimplemented across many files. This repo's own lessons name "two
implementations of one rule" as a recurring bug class.

**Why this priority**: The duplication is not a bug today. But it is exactly the
shape that has produced real bugs here before, and the fix is mechanical.

**Independent Test**: Grep for each consolidated helper and find one definition. Every
API response and every rendered label must come out byte-identical to before the
change.

**Acceptance Scenarios**:

1. **Given** every backend response that rounds to 2, 3 or 4 decimal places,
   **When** the shared helper replaces the 17 private copies, **Then** every
   response body is unchanged for the same stored data.
2. **Given** the four frontend `ordinal()` copies, **When** they become one, **Then**
   every place label renders the same, including 11th, 12th and 13th.

---

### User Story 3 - Responses have a declared shape (Priority: P2)

A maintainer adds a field to an API response by editing one typed record. The Java
side of the "api.ts mirrors the Java records" rule then has a record to mirror.
Today about 141 response bodies are built by hand as `Map<String,Object>` inside
controllers. That leaves no record to mirror, and it carries the documented
`Map.of`-throws-on-null footgun.

**Why this priority**: It is the highest-value refactor, and also the riskiest,
because a renamed key or a dropped null breaks the UI silently. It needs to be done
one controller at a time, each with a JSON-equality check.

**Independent Test**: For each converted endpoint, capture its JSON against the same
seeded data before and after the change, and confirm the two are the same after
normalizing key order.

**Acceptance Scenarios**:

1. **Given** `LeagueHistoryController`, which has 43 map builds, **When** it is
   converted to response records, **Then** the history, manager-history, power
   rankings, ballot and record-book payloads are unchanged, null fields included.
2. **Given** a response field that can legitimately be null, such as a free agent's
   team, **When** it is null, **Then** the endpoint still returns 200 with an
   explicit null, not a 500.

---

### User Story 4 - Large files split along their seams (Priority: P3)

A maintainer can find the superlative they're editing without scrolling through all
13. The few multi-thousand-line files are split by the concerns already inside them.

**Why this priority**: It's useful, but it's churn. Each split conflicts with any
concurrent session editing the same file, so it goes last, one file per change.

**Independent Test**: For each split, confirm behavior is unchanged: tests pass and
the page renders identically. The original file's line count should drop
substantially.

**Acceptance Scenarios**:

1. **Given** `styles.css` at 5,568 lines, **When** it is split into per-page and
   per-component sheets plus a shared token sheet, **Then** every page renders the
   same at desktop and mobile widths.
2. **Given** `SeasonSuperlativesService` at 1,586 lines, **When** each superlative
   kind moves to its own unit, **Then** the superlatives payload is unchanged.

---

### Edge Cases

- A file looks unused but is reached by an operator rather than the UI. For example,
  `POST /api/ingest/projections` and `POST /drafts/{id}/picks` have no `web/src`
  caller but are documented in README/DEPLOY. These must be **kept** unless an owner
  confirms otherwise. "No UI caller" is not "unused" (lessons #6).
- A worktree or branch holds commits that are not on main. It must not be removed. A
  merged-only check has to be run first.
- A concurrent session is editing a file scheduled for a split. Splits must land as
  small, separate commits so a conflict costs one file.
- A converted response used to emit a key with a null value, and the new record
  omits it. That counts as a change, and the endpoint's check must catch it.
- A Flyway migration looks obsolete. It must **never** be removed or edited:
  migrations are append-only.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The cleanup MUST NOT change any user-visible behavior. The one named
  exception is the `PowerRankings.tsx` ordinal fix ("21th" becomes "21st"). It lands
  as its own commit labelled as a fix, so it can be reverted or vetoed on its own. Every route
  renders the same, every API response body is identical for the same data, and
  every scheduled job does the same work.
- **FR-002**: The production site MUST NOT serve development-only reference assets.
- **FR-003**: Development-only verification harnesses MUST be removed from the
  shipped route table. If a harness is still wanted, it MUST be converted to an
  automated test first and only then removed.
- **FR-004**: Each small formatting or rounding rule MUST have exactly one
  implementation per language (one in the backend, one in the frontend).
- **FR-005**: Each converted API response MUST have a declared, typed shape on the
  backend, and its frontend type MUST be updated in the same change.
- **FR-006**: Each converted endpoint MUST be shown, with a captured before/after
  comparison, to produce identical JSON, null fields included.
- **FR-007**: Removing a file MUST be preceded by evidence that nothing references it:
  no importers, no route, no script, no doc instructing an operator to call it. The
  evidence MUST be recorded in the change.
- **FR-008**: Removing local branches and worktrees MUST be limited to ones whose
  commits are all reachable from main. The list MUST be shown to the owner and
  confirmed before anything is deleted.
- **FR-009**: Schema migration files MUST NOT be edited, renamed or removed.
- **FR-010**: Each phase (P1–P3) MUST be independently mergeable. A later phase must
  never be required to make an earlier one correct.
- **FR-011**: The project constitution (`.specify/memory/constitution.md`) MUST either
  be filled in from AGENTS.md's hard rules or be removed. Today it is the unfilled
  template, and every speckit run loads it as if it were policy.

### Key Entities

- **Removal candidate**: a file, route, branch or worktree, together with the
  evidence that it is unreferenced and a verdict (remove / keep / confirm with
  owner).
- **Refactor target**: a file or a rule, with its measured size or duplicate count,
  the seam it would be split along, and the check that proves the change preserved
  behavior.

## Inventory (measured on `origin/main` @ `7b7238e`, 2026-10-07)

### Remove (P1)

| Item | Evidence | Verdict |
|------|----------|---------|
| `web/src/pages/PowerRankings.verify.tsx` (379 lines) and its route at `App.tsx:149` | Header says "dev-only self-check harness"; it is the only consumer of the PNGs below | Remove. Any check still wanted moves to `PowerRankings.test.tsx` |
| `web/public/pr-reference/*.png` (~860 KB) | Only referenced by the verify page. Anything in `public/` ships to the production site | Remove, or move to `specs/013-*/` if the mockup is worth keeping |

> **Amended after planning (2026-10-07, measured against production).** The first draft
> implied that both rows above ship to production. Only the PNGs do:
> `GET https://www.ballknowers.co/pr-reference/2a-front-page-desktop.png` returned
> `200 image/png 626914B`. The verify page is **already** gated on `import.meta.env.DEV`
> (`App.tsx:148`), and the production JS bundle (513 KB) contains 0 occurrences of its
> markers (`BANNED_TERMS`, `pr-reference`), so Vite tree-shakes it out. Removing it is
> still worthwhile, because it is 379 lines that import the private builders of
> `PowerRankings.tsx` and pin that file's exports. But it is source hygiene, not a
> production fix. FR-002 and SC-002 apply to the PNGs only.
| ~25 `.claude/worktrees/agent-*` worktrees plus finished feature worktrees (37 total) | `git worktree list` | Prune the merged ones after owner confirmation (FR-008) |
| 54 local branches already merged into `origin/main` | `git branch --merged origin/main` | Delete after owner confirmation (FR-008) |
| `.specify/memory/constitution.md` | Still `[PROJECT_NAME]` placeholders | Fill from AGENTS.md hard rules, or delete (FR-011) |

### Keep, despite looking unused

| Item | Why |
|------|-----|
| `POST /api/ingest/projections`, `POST /drafts/{id}/picks`, `GET /api/health` | Operator / deploy endpoints documented in README/DEPLOY; no UI caller by design |
| `scripts/*.sh`, `scripts/*.py`, `scripts/*.sql` | One-off repair and audit tools tied to specs 009/fixes; low cost, high value if the bug recurs. Candidate: move under `scripts/oneoff/` with a README line each |
| `claude/*.md` (53 docs) | The repo's convention is visible history ("corrections shown, not hidden"). Candidate: an index in `claude/README.md` that marks each doc as shipped / idea / superseded, rather than deleting any |

### Consolidate (P2)

| Rule | Copies today | Target |
|------|--------------|--------|
| `round2` / `round3` / `round4` / `round(v, places)` | 17 backend files | One backend numeric helper |
| `ordinal(n)` | ~~4~~ **5** frontend files: `draftGrades.ts`, `rankOrder.ts`, `ExpectedWins.tsx`, `Superlatives.tsx` (agree everywhere, measured) and `PowerRankings.tsx:139` (**disagrees on 267 of 0..1000**: prints "21th") | One frontend formatter module. The PowerRankings copy merges in its own commit, labelled as a deliberate fix (see FR-001 exception) |
| Hand-built `Map<String,Object>` responses | ~141 across `api/*.java`; worst offenders `LeagueHistoryController` (43), `SuperlativesController` (17), `LeagueController` (17) | Response records, one controller per change, each with a JSON-equality check |

### Split (P3), one file per change and largest first

| File | Lines | Natural seam |
|------|-------|--------------|
| `web/src/styles.css` | 5,568 | Tokens / shell / per-page sheets |
| `web/src/api.ts` | 2,568 | Per-domain type and fetch modules re-exported from `api.ts`, so no import sites change |
| `engine/SeasonSuperlativesService.java` | 1,586 | One unit per superlative kind (13) behind the existing service |
| `web/src/pages/PowerRankings.tsx` | 1,575 | Story/headline builders (already exported for the verify page) vs view |
| `web/src/pages/LeagueAnalysis.tsx` | 1,346 | Per-tab components |
| `api/LeagueHistoryController.java` | 1,022 | History / manager-history / power-rankings+ballots: three controllers |
| `engine/` package | 49 files | Draft-sim core vs season analytics sub-packages (only if done alongside a split above; not on its own) |

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: League members see no difference. Every page renders the same and every
  converted endpoint returns identical JSON against the same data. This is verified
  by a captured before/after comparison, not assumed.
- **SC-002**: The production site serves 0 development-only assets. Today it serves 2
  PNGs, about 860 KB; the first was measured live at 626,914 bytes.
- **SC-003**: Every consolidated rule has exactly 1 definition per language, down from
  17 backend rounding helper files and 5 frontend ordinal copies. Inline rounding is
  replaced only where the expression is literally `round_k(expr)`;
  `PlayoffOddsService:422` (`Math.round(v*1000/total)/1000`) is **excluded**, because
  it is not the same function (24 measured differences).
- **SC-004**: Hand-built map responses in `api/*.java` **success bodies** drop by at
  least 50% in P2, and the three worst controllers' success bodies reach 0. Error
  bodies (`error`/`message` keys) and SSE event payloads stay maps and are out of
  scope. So do controllers outside `api/` (`recap/*`, `refresh/*`).
- **SC-005**: No source file exceeds 1,000 lines after P3. Today 6 do.
- **SC-006**: The backend suite, the frontend tests and the production build pass
  after every phase, with a skip count no higher than before (see the "suite skips
  ITs silently" lesson). **P2b and the P3 backend splits need 0 IT skips**, with
  Postgres up.
- **SC-007**: 0 branches or worktrees holding unmerged commits are deleted.

## Assumptions

- The skim covered size, duplication and reachability. It did **not** read every file
  for correctness, so this is a refactor plan, not a bug audit (`/code-review` is the
  tool for that).
- "Unused" was checked statically (imports, route table, grep of README/DEPLOY/
  scripts). Runtime-only callers such as the GitHub Actions daily refresh or Railway
  health checks were checked for `/health` only. Other candidates need owner
  confirmation.
- Concurrent Claude sessions share this machine. All work happens in the
  `../draft-sim-021` worktree, and the main checkout is left alone.
- Deleting local branches and worktrees is local-only and reversible through the
  reflog for a while, but it still requires explicit confirmation. Nothing in this
  plan touches the remote, Railway, or the database.
- The `engine/` package split is opportunistic. It is not worth a standalone change,
  given how much it would conflict with the concurrent sessions.

## Amended after review (2026-10-07)

The adversarial plan review ([plan-review.md](plan-review.md)) changed this spec in
these places, each marked inline:
- AS-1: content-type, not 404 (finding 11).
- Ordinal copies: 5, not 4 (finding 9).
- SC-003: the rounding exclusion (finding 10).
- SC-004: error, SSE and out-of-`api/` bodies scoped out (findings 4, 8 and 20).
- SC-006: zero IT skips for P2b (finding 3).
- FR-001: the ordinal exception.
