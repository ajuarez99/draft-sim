# Live verification — A. Auto-detect which slot is you

Live verification stage of the plan → plan-review → developer → code-review →
**live-verification** pipeline (`AGENTS.md`), done on `feature/auto-detect-slot`
against worktree `C:\Users\allan\source\draft-sim-plan-a`, 2026-09-01. Backend run
on port 8084, frontend dev server on port 5178 (both non-default, to avoid
colliding with the main tree on 8080/5173 and the parallel `draft-sim-plan-b`
worktree's own dev servers), against the shared throwaway Postgres 14 cluster on
`localhost:5433`. Per `AGENTS.md`'s convention, this stage actually ran the real
server and clicked the real UI — a passing test suite alone was not treated as
"verified."

## Environment

- Postgres 5433: already-provisioned throwaway cluster at
  `C:\Users\allan\AppData\Local\Temp\claude\draftsim-pgdata` was found cleanly
  shut down (not currently listening) at session start — started it with
  `pg_ctl` rather than re-`initdb`, per AGENTS.md's "another agent may already
  have it running, check first" guidance. `select 1` confirmed connectivity
  before proceeding.
- Backend: `bootRun` with `PORT=8084`, `CORS_ORIGINS=http://localhost:5178`, and
  (for AC1/AC2) `APP_OWNER_SLEEPER_USER_ID=1122386008709910528`. `curl
  localhost:8084/api/health` → `weightsLoaded: true` confirmed before every test
  pass. Flyway reported schema version 2, no migration needed.
- Data: `GET /api/board` and `/api/drafts/.../seats` for fantasy(heart)
  (league `1391509063170293760`, draft `1391509064357273600`) already returned
  real board/seat data — no re-ingest needed.
- Frontend: `vite` dev server temporarily reconfigured to port 5178 / proxy
  target 8084 in `web/vite.config.ts`, and matching entries added to
  `.claude/launch.json`, for the duration of this verification pass only.
  **Both reverted to their committed defaults (port 5173, proxy to 8080) before
  committing** — confirmed via `git diff --stat` showing only
  `web/src/pages/DraftView.tsx` changed at commit time.
- Driven with the Browser tool (`navigate`/`computer`/`read_page`/
  `get_page_text`/`find`/`javascript_tool`/`read_console_messages`/
  `read_network_requests`), not just curl.

## Acceptance criteria

### AC1 — real detection, no flash. **PASS**

With the owner id configured and fantasy(heart) opened with no `?slot=` in the
URL: the full-screen pre-start board's slot input showed `11` immediately, and
the CTA read "We found your seat — you're slot 11. Start the mock draft."
Confirmed via `GET /api/drafts/.../seats` directly too:
`mySlot: 11`, matching seat 11 → manager `popsharky`.

Flash check, done two ways:
1. **Empirical**: a hard reload (`navigate ... force: true`) followed
   immediately by a screenshot caught the true pre-seats-loaded frame — the
   slot input showed the disabled `...` placeholder, *not* `1`. A second
   screenshot ~1s later showed `11`. At no point did `1` ever render.
2. **Source-level re-verification** (independent of code-review's own trace,
   re-derived by reading the current code, not just trusting the prior
   review): `slotKnown = slotParam != null || seats != null`
   (`DraftView.tsx:298`) gates the input's displayed value — it renders blank/
   disabled until `seats` exists, structurally preventing `DEFAULT_SLOT` (`1`)
   from ever painting. The `useLayoutEffect` keyed on `[seats]`
   (`DraftView.tsx:123-136`) commits the corrected value before paint once
   seats arrive. Together these make a `1`-flash structurally impossible, not
   just unlikely — confirmed by reading the actual shipped code, not the
   review's description of it.

No console errors, no backend errors in the bootRun log during any of this.

One forward-looking (non-blocking) observation: the console shows React
Router's own "will begin wrapping state updates in `React.startTransition` in
v7" warning. The zero-flash guarantee depends on `setSearchParams` being
synchronous under the current `BrowserRouter` behavior — if this repo ever
opts into that v7 flag (or upgrades to a version where it's default), this
guarantee should be re-verified. Not a bug today; flagging so it isn't
forgotten.

### AC2 — explicit `?slot=5` is respected. **PASS**

Opened `.../drafts/1391509064357273600?slot=5` — slot input showed `5`, stayed
`5`, URL stayed `?slot=5`, never overwritten to `11`.

**Live bug found here, not caught by stages 1-3 (fixed in this stage — see
"Bug found and fixed" below):** the full-screen CTA's copy still read "We
found your seat — you're slot 11. Start the mock draft." even though the
input correctly showed `5` and `5` is what `run()` would actually use. The
*value* used by the app was correct (satisfying AC2's literal wording), but
the on-screen message was actively wrong about which slot the user was about
to draft as — exactly the kind of thing a real user would notice and be
confused by. Fixed; re-verified below.

### AC3 — unset config / fallback. **PASS**

Restarted the backend without `APP_OWNER_SLEEPER_USER_ID`. Confirmed via API
first: `GET /api/drafts/.../seats` returned `"mySlot": null` (key present,
serializes fine — the `Map.of` NPE class of bug plan-review flagged does not
recur). Live in the browser: slot input showed `DEFAULT_SLOT` (`1`), URL
stayed clean (no `?slot=` added), CTA correctly read "Set your slot above if
you know it, then start the mock draft." Manually typing a new value (`9`)
worked normally — URL updated to `?slot=9`, no console errors, no broken UI.

### AC4 — backend tests green. **PASS**

`cd backend && ./gradlew.bat test --rerun` (forced a real re-execution, not an
up-to-date short-circuit) against the live Postgres 5433 cluster —
**BUILD SUCCESSFUL**, all suites including both `LeagueControllerSeats*IT`
classes from the developer/code-review stages.

## Additional sanity check (feature C interaction) — PASS

With the owner id configured and no `?slot=`, the full-screen pre-start board
showed slot `11`. Clicked "Start the mock draft" — the simulation ran
(progress overlay showing `simulating NN%`, matching feature C's existing
behavior), and on completion the normal three-band layout returned with the
slot input still showing `11` and the "Availability at your picks" panel
correctly labeled "Pick 11 of...". Auto-detected slot survives the full
start → simulate → reveal flow, not just the pre-start screen. No console or
backend errors during the run.

## Bug found and fixed

**Symptom**: the full-screen start-overlay's "We found your seat — you're
slot N" copy used `seats?.mySlot != null` as its only condition
(`DraftView.tsx`, pre-fix). `seats.mySlot` is the *auto-detected* value from
the backend and doesn't know or care whether the user's `mySlot` (the value
actually used by `run()`) came from that auto-detection or from an explicit
`?slot=` override. Result: opening the draft with `?slot=5` still showed "you're
slot 11" — actively wrong messaging on the one screen this feature was built
to streamline.

A same-shaped but more subtle version exists purely at the `slotParam == null`
level too: since auto-detection *itself* adopts its value via
`setSearchParams`, the instant it fires, `slotParam` stops being `null` —
so a naive `slotParam == null` check (my first fix attempt) breaks the correct
case (auto-detected slot 11) the moment the effect that's supposed to display
it also happens to falsify its own guard condition. Caught by re-testing the
no-override path after the first fix, not assumed correct.

**Root cause**: neither `seats.mySlot` nor `slotParam` alone can distinguish
"the value on screen right now is here *because* auto-detection put it there"
from "the value on screen right now happens to be present in the URL for some
other reason" (explicit user param, or the URL having already been rewritten
by auto-detection itself).

**Fix** (`web/src/pages/DraftView.tsx`): added `autoAdoptedSlotRef`, a ref set
`true` only inside the auto-detect `useLayoutEffect` at the moment it adopts
`seats.mySlot`, and explicitly cleared to `false` (a) when the effect sees an
already-present `slotParam` (explicit override at load), (b) when the effect
finds no match (`seats.mySlot == null`), and (c) on any manual edit of the
slot input. The CTA condition changed from `seats?.mySlot != null` to
`autoAdoptedSlotRef.current && seats?.mySlot != null`.

**Re-verified live after the fix**, all via the running app, not just re-reading
the diff:
- No `?slot=` + match → "We found your seat — you're slot 11." (unchanged,
  still correct)
- Explicit `?slot=5` (differs from the match) → generic "Set your slot
  above..." text, slot input stays `5` (bug fixed)
- No `?slot=` + match, then **manually retyped** to `7` after auto-adopt fired
  → CTA correctly switches to the generic text, URL updates to `?slot=7`, no
  console errors (this exact edge case — editing after auto-adopt — was not
  in the original acceptance criteria or either prior review; added as a
  regression case here since the fix specifically has to handle it)
- Unset config → generic text, as before (AC3 unaffected)

`npx tsc -b` clean and `npm run build` clean after the fix (191.21 kB JS /
14.38 kB CSS, materially unchanged from code-review's numbers). Backend
untouched by this fix — `./gradlew.bat test` result above already reflects a
clean tree apart from this one frontend file.

## Not re-litigated

- The deferred sub-16ms race on the top control-bar "start" button
  (`claude/code-review-A.md`'s "Deferred" section) — still present, still a
  product judgment call, not touched here. Confirmed it's unrelated to the bug
  found above (different code path: the top bar's button vs. the full-screen
  overlay's CTA text).
- AC4's IT test *content* — not re-read line-by-line since code review already
  verified them as non-vacuous; only re-ran them for a fresh pass/fail signal.

## Cleanup

- Backend (8084) and frontend (5178) dev servers stopped.
- `web/vite.config.ts` and `.claude/launch.json` reverted to their committed
  defaults (`git checkout --`) — confirmed via `git diff --stat` before
  committing that only `DraftView.tsx` and this report are part of the commit.
- Postgres 5433 **left running** — it's the shared throwaway cluster
  documented as potentially in use by the parallel `draft-sim-plan-b` flow;
  only one idle connection was open at the time of checking, but per
  AGENTS.md's own framing this is a shared resource and leaving a local
  throwaway Postgres running is low-risk. Stop it with `pg_ctl -D
  C:\Users\allan\AppData\Local\Temp\claude\draftsim-pgdata stop` once no
  session needs it.

## Go / no-go

**GO.** All four acceptance criteria pass against the real running app, not
just tests. One real bug was found live (the CTA copy not accounting for an
explicit slot override, plus a subtler version of the same mistake in my own
first-pass fix) and is now fixed and re-verified across all the states that
matter: auto-detected, explicit-override, post-auto-detect manual edit, and
unconfigured. No regressions found in feature C's full-screen empty-board
interaction. Safe to merge `feature/auto-detect-slot`.
