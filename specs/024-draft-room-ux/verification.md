# Verification: spec 024

Verified and assumed/not run are kept apart below. Measured numbers are given as measured.

## Pre-build measurement (T002 follow-up, 2026-10-09) — VERIFIED

Measured in a 1440×900 viewport, with backend `draft-sim-024-api-8094` and web `5195` running main's code (`d145f38`) from this worktree, signed in as popsharky (public Sleeper id, local dev):

| Thing | Measured |
|---|---|
| Filled board cell (12-team NBA 2025, `1229352720230514688/live`) | 70 px, row gap 4 px |
| `.col-head` | 72 px |
| Empty pre-draft cell (NBA 2026, `1339351318128517120/live`) | 28 px |
| Tiers list row / thead | 27 px / 28 px |
| Live chrome above the board stage (status, feed 98, team 59, meters 93, panel head) | board stage top at **438 px** |
| What a user sees today on a filled 12-team board | about 2 rounds visible before the floating sheet covers the board |

These numbers drove the user's two decisions: compact cells (FR-001b) and a compact row above the board (FR-001c).

## Backend over HTTP (2026-10-09) — VERIFIED

Against `draft-sim-024-api-8094` (this worktree, V30 applied), as popsharky:

- **Setup / ingest:** `POST /api/setup/league/1414306784801239040` → `{"draftsIngested":1}`. Seats for `1414306786223153152` → `teams 4, rounds 14, status pre_draft, draftType "snake", pickTimerSeconds 120`, 0 seats mapped. This exercises the ingest write and the format query end to end. A2 is verified.
- **Targets:**
  - PUT as the owner → 200 with `players` and `missing: []`;
  - GET as a non-member (`999`) → **404**;
  - anonymous PUT → **401**;
  - a duplicate id → **400**.
- **Mock auto** (a new 12-team NBA mock, user slot 5):
  - `PICK` → AUTO pick 5, Luka Dončić, in **0.080 s**.
  - `FINISH` → `COMPLETE` in **0.200 s**. 168 picks: 14 AUTO, all in slot 5, and 154 BOT.
  - `FINISH` again → **409**.
  - SC-006: one interaction, and the completed board came back in 0.2 s, measured.
- **Preference tests (build agent, parent read the rule):** flipping the need test to `rosterNeed > 0` turned exactly the NBA tenth-pick test red. Commenting out the advisory lock turned the two-concurrent-PUT IT red with `DuplicateKeyException`. Both were restored and pass. Suite: 1543 tests, 0 failed, 0 skipped.

## US3 + US5 live (2026-10-09) — VERIFIED (1440×900, web 5195 → backend 8094)

**Targets, live room `1414306786223153152/live?slot=3`:**
- The strip loaded Wembanyama, saved earlier by curl. That proves it reads from the server: **82%** survival with the seat known, plus "Picks are made on Sleeper — this list only watches."
- The star buttons are labelled "Add/Remove X to/from targets". After adding Jokić, Edwards and Dončić and removing Wembanyama, the strip read `N. Jokić 85% | A. Edwards 87% | L. Dončić 85%`.
- **After a full reload,** the same three came back in the same order (84/89/84%). The small change is a fresh Monte Carlo projection, not a save problem. FR-014 ✓
- **SC-002:** typing "jokic" (no accent) leaves exactly one row, "Nikola Jokić". ✓

**Mock auto, mock 3722 (12-team NBA, slot 7) via the UI buttons:**
- The controls read "Auto-pick | Auto-finish the draft".
- Auto-pick moved 1.07 → my next turn at 2.06.
- Auto-finish → "MOCK DRAFT COMPLETE": 168 cells, all `real`. The list showed "Draft complete", auto-finish was disabled and auto-pick hidden.
- **Mock 3723 (slot 1, so my pick is the last one):** the feed's latest pick, "14.12 … You", carries the **AUTO** tag (`pick-feed-auto`). ✓
- **Observed A11 instance, recorded and not fixed:** after 14 auto picks, "Your team" read **8 of 9 starters**. Auto-pick's backend rule reads every position a player lists, but the team strip (`teamNeeds.ts`) reads only the first. That is the two-implementations disagreement A11 names. The fix is the follow-up task (`positions[]` on `PlayerRef`).

**Not run in a browser:** the targets popover reorder and remove (jsdom tests only); the save-error path; a second device or browser profile; anything under 1280 px (T045 still owes 375 px and 1024 px).

## Split default and measurement bug (T017, 2026-10-09)

- **Pre-draft 12-team live at default 0.5:** board 299, list 299, **7 rounds, 7 rows**. That is one row short of SC-001.
- **Projection room after "Start the mock draft":** grid rows `355px 8px 30px 201.6px`, so only 4 list rows. `aria-valuemax` = 502 means the divider believed the space was 709 px, but `.room-split` was really 595 px. **A stale measurement after the room's top stack grew (the PickPrompt appeared).** Sent to a fix agent, along with a better default policy: "enough for 7 compact rounds, the rest to the list" instead of a fixed fraction. *Re-measure after the fix.*

## Final pass (T045, 2026-10-09) — VERIFIED on the final code

Backend `8094` restarted with the poller and SQL follow-ups; web `5195` hard-navigated.

- **SC-001 re-check, live pre-draft 12-team at 1440×900:** **8 rounds, 8 rows**, 0 overlap, no page scroll, a single status line. ✓ The mock (7 rounds, 8 rows) and the live filled board (7 rounds, compact row 64) were measured above on the same layout code.
- **Frontend bug-hunt review** (`code-review-frontend.md`): 4 bugs and 8 risks. B1–B4 and R1–R5 were fixed by a Sonnet agent (1,414 tests). R6–R8 are recorded, not fixed:
  - R6: navigating live → projection mid-save can show the list without the last edit;
  - R7: a "linear" draft type label over a board that always draws snake;
  - R8: stars fail with no identity, which the sign-in gate makes unreachable.
- **Found by driving the fixes, and fixed by the parent:**
  - **The projection room's list still showed 240 survival tiles for an assumed seat.** The live room holds them back. Now both rooms do: "Availability appears once your seat is known. Claim your seat on the board."
  - **After a Claim, the projection room said "You — 1.01" and "No picks left" with no explanation.** That was B2's gate emptying the picks list. It now shows "This simulation ran from slot 1; you're now slot 2…" with a "Simulate again" button, plus a matching list note.
  - Checked live, the flow is: assumed → no numbers + note; claim → banner + note; Simulate again → "You — 1.02", targets **Jokić TAKEN · Edwards 94% · Dončić 92%**, 240 tiles. ✓
  - During this, HMR loaded a half-applied edit (a variable used before its declaring edit landed), and the error boundary latched until a reload. That is a dev-server artefact, not a code bug. tsc was clean throughout.
- **R3/A12 at 1024×800 with Players selected:**
  - the hidden board pane has `inert` on its content and 0 focusable elements outside the card;
  - the pick card isn't inert;
  - its close button is hit-testable and focusable, and clicking it closes the card. ✓
- **R4 on a 375 px phone:** tapping ⓘ in the mock list opens a 260 px popover with the full caveat, fully on screen. ✓
- **Phone (375):** no horizontal page scroll in the live room, the mock or the projection room; Board | Players switches with one tap. ✓
- **One HTTP 500 in the console:** seen once during the backend restart window, with no server-side error logged since the restart. *Inferred* to be Vite's proxy answering while the backend was down; not reproduced.
- **Final suites:**
  - web: tsc clean, **1414/1414**, production build OK;
  - backend: **1547 tests, 0 failures, 0 errors, 0 skipped** (Gradle up to date with the follow-up agent's run on this code).

**Not verified, stated plainly:**
- a live draft *in progress* (none locally), so live mid-draft list rows are arithmetic;
- a second device or browser profile for targets;
- the targets popover's reorder and remove in a browser (jsdom only);
- the targets save-error path in a browser (jsdom only).

### Backend review follow-ups (2026-10-09) — VERIFIED

- **Backend bug-hunt review** (`code-review-backend.md`): **no confirmed bugs**. Targeted suites passed: `MockDraftServiceTest` 43/43, `TargetControllerIT` 8/8, `AccessControlMvcIT` 22/22.
- **Two risks, acted on by a Sonnet fix agent** (parent read the report):
  - `LiveDraftPoller` now writes the timer only when `raw.settings` is a map, and `updatePickTimer` skips unchanged values with `is distinct from`. Three poller tests were added.
  - An auto-pick test with `reversalRound 3` was added: 8 teams, user on slot 1, user picks 1/16/24/25…; FINISH gives exactly the 14 user-seat picks, 13 AUTO + 1 USER.
  - **Suite: 1547 tests, 0 failed, 0 skipped.**
- **The new SQL, run by hand** (no test executes it; lessons #2), as a prepared statement with `int` parameters against 5433, rolled back:
  - same value (120 → 120) → `UPDATE 0`;
  - 120 → 90 → `UPDATE 1`;
  - 90 → null → `UPDATE 1`;
  - null → null → `UPDATE 0`. ✓
- **Left as recorded risks:**
  - the 10-arg `upsert` overload (it writes a null timer on conflict; it's commented as test-only);
  - nits listed in the review.

### T017 final measurement (2026-10-09): SC-001 MET in live and mock rooms

Default split, no stored fraction, viewport 1440×900, DOM rects:

| Room / state | Board | List | Rounds | Rows | Overlap | Page scroll |
|---|---|---|---|---|---|---|
| Mock, mid-draft on your turn (3724) | 329 | 338 | **7** | **8** | 0 | none |
| Live, pre-draft 12-team (`1339351318128517120`) | 329 | 319 | **8** | **8** | 0 | none |
| Live, filled 12-team (`1229352720230514688`, complete) | 329 | 310 | **7** | n/a (no rows left) | 0 | none |
| Projection room after Start (`1339351318128517120`) | 329 | 263 | 8 | **6** | 0 | none |

**Notes:**
- **Live mid-draft rows, computed not measured.** The live filled board has no list rows (the draft is complete), so mid-draft rows are arithmetic: (310 − 88) / 27 = 8.2 → **8**. That's using the measured 88 px head and 27 px rows. No drafting draft was available locally to count them.
- **Projection room shortfall, recorded not hidden.** It is outside SC-001, which names the live and mock rooms. Its pick-prompt row (41 px) costs two rows. Dragging the divider recovers them.
- **What got it here,** all measured before and after:
  - mock "Pick from full list" moved into the controls row (prompt 50 → none);
  - mock status 71 → 36;
  - the list's caveat notes became ⓘ markers with the full text in `title`/`aria-label` (mock head 112 → 88);
  - the team half of the compact row went to one line with a thin scrollbar (live filled compact row 77 → **64**);
  - the board default became "77 + 7 × 36 = 329" instead of a fraction.

### Pick card below 1280 px (A12): bug found and fixed (2026-10-09)

At 1024×800 with Players selected, opening a feed pick showed the card (`position: fixed`, on screen, opacity 1). But `elementFromPoint` at its centre returned the list: the card inherited `pointer-events: none` from the clipped board pane, so close and pause clicks fell through. The fix is `.room-pane-hidden .pick-card { pointer-events: auto; }`. Re-checked: the hit test lands on the card, the close button is hit, and clicking it closes the card. ✓ (Lesson #35's check, applied again.)

### Incident: AvailabilityPanel.tsx emptied, then rebuilt (2026-10-09)

- **What happened.** A fix agent's Python edit truncated `web/src/components/AvailabilityPanel.tsx` to 0 bytes (a cp1252 encode error after `open(p,'w')`). The agent's own recovery was blocked by the safety classifier. The supervising session stopped the agent and reported to the user, who said "keep going".
- **The rebuild.** Main's version (`git show HEAD:…`) was replayed, into a scratch copy, through the four recorded edit scripts that had succeeded: US1 #87 and #94, US2/US4 #136 (the component half only), and the US3/US5 `edit_avail.py` as fixed. The result was checked for the expected markers (`avail-region`, `matchesSearch`, "Hide drafted", `onAddTarget`, and no `avail-sheet`), then copied over the empty file.
- **Verified.** `npx tsc -b` is clean and `vitest` gives **1408/1408**, identical to the last count before the incident. Lesson #37 recorded.
- **Not done by that agent:** the mock chrome tightening (button into the controls row, a one-line status, caveat notes on one line) and the compact row at ≤68 px. Its constants change (header 77, compact pitch 36) did land.

### T017 after the measurement fix (2026-10-09)

- **Pre-draft 12-team live at the new default:** board **303**, list 341, **7 rounds, 9 rows**, 0 overlap, no page scroll. ✓
- **Mock mid-draft (3724, user's turn, round 6):** **6 rounds, 5 rows.** ✗ Measured:
  - compact round **pitch 36 px** (not 33);
  - the first cell is 77 px below the board top;
  - mock status 71, prompt 50, compact row 44, controls 25, targets 30;
  - list head 112 px before the first row (two caveat lines).
- **Wrong guess, named as one:** the supervising session told the fix agent the compact pitch was 33 px ("29 px cell + 4 px gap"). That was a derivation, never a measured pitch, and it was wrong: the measured pitch is 36, which is what the US1 agent originally used. The constant is corrected to 36 and the header to 77 in a follow-up fix. The mock's chrome is tightened in the same fix (button moved into the controls row, one-line status, caveat notes kept but as one line or tooltips). Re-measure after.

## US2 + US4 live (2026-10-09) — VERIFIED, with one bug found and fixed

Web `5195` on backend `8094`, 1440×900 viewport, DOM measurement.

- **Filled 12-team (`1229352720230514688/live`):**
  - compact cells 29 px, **8 rounds** fully visible;
  - compact row **77 px** (≤80 ✓);
  - 0 px overlap, no page scroll;
  - labels "1.01"…;
  - one status line: "DRAFT COMPLETE | 168 picks · 14 rounds | 12 teams · 14 rounds · snake" (no timer: Sleeper sent none for that draft).
- **SC-004 on the real league draft `1414306786223153152` (pre-draft, no order):**
  - status: "DRAFT HAS NOT STARTED | … | Waiting for the commissioner to set the draft order. | **4 teams · 14 rounds · 2 min · snake**", one statement;
  - no "0/0", but "SG, SF: no starter-pool players list these first";
  - 4 headers "Unclaimed · Claim", and column 1 "assumed";
  - 0 per-cell `mine` outlines;
  - "Continue as a mock →" disabled, with its reason in the title. ✓
- **Bug found by driving it, and fixed by the parent:** clicking Claim on slot 3 set `?slot=3`, and the room treated the seat as known (survival appeared). But the header still read "Unclaimed · Claim / assumed", because the vacancy branch of `DraftBoard` ignored `mySlotAssumed`. Fixed: it now reads "Your seat" with the `mine` class. Two tests were added to `DraftBoard.test.tsx` (18/18), and the fix was re-checked live: `col-head unclaimed mine :: 3 Your seat`.
- **SC-001, still open:** the pre-draft list shows only **5** rows. Player rows are 35–55 px, because `.player-col` (260 px) wraps the name, team and "Fills C" while `.strip-cell` is 648 px. The fix (single-line rows) is in the US3 frontend build. The default split will be re-measured after it.

## US1 layout, first live measurement (T017 partial, 2026-10-09) — VERIFIED numbers; SC-001 NOT YET MET

**Setup:** web `5196` (this worktree, US1 built) proxied to the `draft-sim-023` backend on 8095. That backend is the same `d145f38` main code. It was used because the 024 backend's build directory was busy with a concurrent Gradle build. **Measurement:** a 1440×900 viewport, DOM rectangles via `javascript_tool`. The pane was hidden, so no screenshot was taken.

| | Filled 12-team (`1229352720230514688/live`) | Pre-draft 12-team (`1339351318128517120/live`) |
|---|---|---|
| Board/list overlap | **0 px** ✓ | **0 px** ✓ |
| Page scroll height | 900 (none) ✓ | 900 (none) ✓ |
| Status / notices / controls / compact row | 43 / – / 25 / **126** | 43 / 35 / 25 / 49 |
| Board region | 372 px, **4** full rounds (63 px cells; compact cells not built yet, T019a) | 372 px, 9 rounds (28 px cells) |
| List region | 235 px | 267 px; the head takes 119 px before the first row, so only **3** rows are fully visible |

**Gap to SC-001** (≥7 compact rounds and ≥8 rows), about 70 px. It closes through:
- (a) US2 compact cells (T019a);
- (b) US4's single status statement (the 35 px notices line merges into status);
- (c) a compact row of ≤80 px on a filled board (it is 126 px today, which the feed ticker makes too tall);
- (d) a tighter list head (A4: the head is compressed before the bar is lowered).

The default split is re-measured after US2 and US4.

## V30 by hand (T004, 2026-10-09) — VERIFIED

Run against 5433 after Flyway applied V30 (`flyway_schema_history` top = 30, success = t). Everything ran inside one transaction that was rolled back:

- **One row per scope:** inserted, 2 rows. ✓
- **Both scopes set:** `violates check constraint "draft_target_check"`. ✓
- **Neither scope set:** the same check rejects it. ✓
- **The same owner, draft and player twice:** `duplicate key … "draft_target_live_uq"`. ✓
- **A `mock_draft_pick` with `source='AUTO'`:** inserted and read back as `AUTO`. ✓
- **`draft.pick_timer_seconds`:** exists, type `integer`. ✓

**Not run yet:** the two-concurrent-PUT race (A8). It's covered in `TargetControllerIT` (T023) once the controller exists.

## Phase 2 build (T003, T005–T007) — suite VERIFIED, two paths UNEXECUTED

- **Backend suite (Sonnet build agent's run, parent read the diff):** `gradlew test` → 1523 tests, 0 failures, 0 errors, **0 skipped**, with Postgres reachable.
- **`npx tsc -b`:** clean.
- **Parent review change:** added a warning comment on the leftover 10-arg `DraftRepository.upsert` overload. On conflict it writes `pick_timer_seconds = NULL`, so it is a test-fixture convenience only. The one production caller (ingest) uses the 11-arg form.
- **Unexecuted so far:** the poller's `updatePickTimer` path, and the ingest's on-conflict refresh. These get exercised at T045 by ingesting league `1414306784801239040` and reading the seats response.

## Environment (T001, 2026-10-09)

- **JDK:** `java -version` → 24.0.2. Gradle's foojay toolchain provisions 21 for the build.
- **Postgres:** 5433 is listening, the throwaway cluster `draftsim`. 5432 is also listening, but it's the unrelated server and was left alone.
- **Ports:** nothing listening on 8080 or 5173, so there's no stale backend to confuse a live check.
- **Previews:** `preview_start` reads the **main checkout's** `.claude/launch.json`, not this worktree's (memory: "worktree preview serves main"). Peer sessions already add `cd /d C:\Users\allan\source\draft-sim-0NN\backend` entries there for their worktrees. Spec 024 will add `draft-sim-024-api-8094` / `draft-sim-024-web-5194` the same way before its live checks, and confirm the bootRun classpath points at `draft-sim-024`.
