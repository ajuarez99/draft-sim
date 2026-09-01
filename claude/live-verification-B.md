# Live verification — B. Player picker: best available and team needs

Test stage, 2026-09-01. Per `AGENTS.md`'s pipeline convention, this is the live pass
that actually drives the real server and clicks the real UI — a passing test suite
and a clean code review (both already done, see `claude/code-review-B.md`) are not
this project's bar for "verified" on their own.

## Environment

Worked exclusively in this worktree (`C:\Users\allan\source\draft-sim-plan-b`,
branch `feature/player-picker-redesign`). Did not touch the main tree or
`draft-sim-plan-a`.

- **Postgres**: the throwaway cluster on `localhost:5433` was already running
  (belongs to a concurrent sibling session, per `AGENTS.md`'s "check before
  starting" guidance) — confirmed via `psql`, left it running rather than
  stopping it out from under that session.
- **Backend**: `bootRun` on port 8083 (`PORT=8083`), confirmed via
  `curl localhost:8083/api/health` → `{"weightsLoaded":true,...,"status":"up"}`.
- **Data**: the fantasy(heart) league (`1391509063170293760`, 14 teams) was
  already ingested in the shared DB — no ingest needed.
- **Frontend**: `vite --port 5177 --strictPort`, driven with the Browser tool
  (`navigate`/`computer`/`read_page`/`javascript_tool`), not curl.
- **Note on `.claude/launch.json`**: `preview_start` turned out to be bound to
  the *main tree's* `launch.json`, not this worktree's — confirmed by asking it
  to start `plan-b-api-8083` (an entry only present in this worktree's file) and
  getting "server not found; available: draft-sim-web, draft-sim-api" (the main
  tree's two entries). Added and then reverted worktree-local launch.json
  entries during the session; ran the actual dev servers via backgrounded
  shell instead, and drove them through the Browser tool as instructed
  otherwise (navigate/computer/read_page for every UI interaction below).
- **Bug found and fixed during setup (not a B defect, an environment one)**:
  the backend's default `CORS_ORIGINS` (`http://localhost:5173,http://127.0.0.1:5173`)
  doesn't include port 5177. The very first `POST /api/sims/stream` from the
  browser returned `403 Forbidden` / body `"Invalid CORS request"` — confirmed
  via `javascript_tool` fetch from the page (not just the network tab), and
  confirmed a direct `curl` to 8083 got 200, isolating it to the Origin check.
  Fixed by restarting the backend with `CORS_ORIGINS=http://localhost:5177,http://127.0.0.1:5177`
  (an env var for this run only, not a code change — `WebConfig`'s CORS list is
  already env-configurable, nothing to touch in source).
- **Temporary config for the alternate ports**: `web/vite.config.ts`'s proxy
  target was temporarily pointed at `8083` for this session's testing, then
  reverted with `git checkout --` before writing this report — confirmed clean
  via `git status` (no diff on `vite.config.ts` or `.claude/launch.json`).

## Acceptance criteria (`claude/ui-polish-roadmap.md` section B)

**1. Picker opens sorted best-ADP-first, survival %/bar gone — PASS.**
Opened at the real pause (round 1, pick 11, slot 11 = popsharky). Picker's
"Rank" column read `RB1, RB2, WR1, WR2, RB3, WR3, WR4, RB4, RB5...` — ADP order,
not survival order. No percentage/bar column anywhere in the row; replaced by
`Rank` and `Team need` columns. Confirmed the underlying `.team-strip`/table DOM
via `javascript_tool`, not just the screenshot.

**2. A very-low-survival player still doesn't appear — PASS.**
The picker rendered 147 rows at pick 11 while `GET /api/board?limit=1000`
confirms 1000+ players exist in the pool — the vast majority is excluded, i.e.
the `survivalByPick > 0.01` filter is still active (code review already traced
this line as untouched; this is the live confirmation the filter still fires
in the running app). Additionally observed live at pick 39: several players
still clearing the filter (Derrick Henry, Ashton Jeanty, Omarion Hampton,
Rashee Rice, Kenneth Walker, Chris Olave) were tagged "likely gone" in the row
— the plan's suggested lightweight signal, present and working.

**3. "Your team so far" strip, correct at every pick made, FLEX handled — PASS
(fully reachable, not just the 0-pick case).**
Verified at three states, reading the live `.team-strip` DOM directly:
- 0 picks: `QB RB RB WR WR TE FLEX FLEX K DEF`, all `team-slot open`.
- After picking Ja'Marr Chase (WR) at pick 11: first dedicated WR slot shows
  `team-slot filled` with `"WRJa'Marr Chase"`, everything else still open.
- After a second WR (CeeDee Lamb, pick 18) and a third WR (Nico Collins, pick
  39): both dedicated WR slots filled by name, and — the FLEX case explicitly
  called out as unreachable-if-necessary in the task brief — the third WR
  correctly landed in a `team-slot filled` **FLEX** slot (`"WRNico Collins"`),
  not a phantom third WR slot, with the second FLEX slot still open. This
  matches the FLEX tie-break plan-review-B/code-review-B traced in the source
  (ADP-order overflow into flex pool) and confirms it live, not just by code
  reading.

**4. Rows filling an open slot are visibly tagged — PASS.**
`Fills RB` / `Fills WR` / `Fills QB` / `Fills TE` tags present on every row's
`Team need` column throughout. Confirmed the tag switching live: before any
WR was drafted, "Nico Collins WR9" showed `Fills WR`; after both dedicated WR
slots were filled (2 WRs drafted), the same player class showed `Fills FLEX`
instead — the tag correctly reflects current roster state, not a static label.

**5. PickPrompt's second "best available" action, distinct when they differ —
PASS, observed 3 times.**
- Pick 11: "Take CeeDee Lamb" (model) vs. "Take Jahmyr Gibbs (best available)" —
  different, two buttons shown.
- Pick 18: "Take Omarion Hampton" vs. "Take James Cook (best available)" —
  different.
- Pick 39: "Take Zay Flowers" vs. "Take Trey McBride (best available)" —
  different.
Never had to specifically hunt for a divergent case — all three real pauses
this session happened to diverge, so the "same player, one button" collapse
path wasn't exercised live. Code review already traced that branch
(`PickPrompt`'s conditional single-button render) as present and correct;
not independently re-verified here since no live case arose to exercise it,
noted as a minor gap rather than silently treated as covered.

**6. No backend surprises beyond `rosterPositions` — PASS.**
`GET /api/drafts/1391509064357273600/seats` returned exactly
`{draftId, teams, rounds, status, seats, rosterPositions}` — one additive
field, confirmed via direct `curl`, not just trusting the review. Per-seat
shape unchanged (`slot, managerId, manager, provenance, reachBias,
unpredictability, positionalTilt, note, draftsObserved, picksScored`).
`rosterPositions` correctly includes the 5 `BN` bench entries Sleeper reports —
the frontend's team-strip correctly shows only the 10 starter slots
(`dedicatedStarters()`/`flexSlots()` scope), not 15.

**7. Regressions (dedup, filter chips, Escape/backdrop, resim trigger) — PASS,
all four exercised live.**
- **Dedup**: after picking Ja'Marr Chase at pick 11, reopened the picker at
  pick 18 — Ja'Marr Chase does not appear in the list (list starts at James
  Cook RB6, CeeDee Lamb WR5, ...).
- **Position filter chips**: clicked the `WR` chip — list narrowed to WR-only
  rows, all showing `Fills WR`/`Fills FLEX`.
- **Escape**: closed the open picker modal.
- **Backdrop click**: reopened the picker, clicked outside the card — closed.
- **Resim trigger**: picking a player each time showed
  "Recalculating the board past pick N... X%" and the board updated correctly
  (picked player appears in that slot's cell marked "yours"; the reveal then
  auto-advanced to the next of my picks). Exercised 3 times across the
  session with no stall, no stale board, no console/backend error.

No JS console errors beyond the two 403s from before the CORS env-var fix
(expected, self-resolved, not a code defect). No backend exceptions in the
bootRun log for the whole session (`grep -i "error\|exception"` on the log,
excluding the expected "API token auth DISABLED" startup warning, returned
nothing).

## Bugs found live that stages 1-3 missed

None in the feature code itself. The one live surprise (CORS rejecting port
5177) is an artifact of this test's nonstandard port assignment, not a defect
in the player-picker-redesign change — `CORS_ORIGINS` is already
env-configurable by design (`application.yml`), and the default's mismatch
with an ad-hoc test port isn't something the developer/reviewer stages could
reasonably have anticipated or should fix.

## Go/no-go

**GO.** All 7 acceptance criteria pass on the real running app, driven through
a real browser against a real backend and a real ingested league. AC5's
same-player-collapse branch wasn't independently forced live (noted above) but
was traced and confirmed correct in code review; everything else was directly
observed, not inferred. No regressions, no console errors, no backend
exceptions. Clear to merge.
