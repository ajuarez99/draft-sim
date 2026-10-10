# Adversarial plan review: spec 024 (draft room UX)

**Date**: 2026-10-09 · **Reviewer**: plan-review pass (T002), read cold before any code · **Tree**: `draft-sim-024` at `d145f38` (main), nothing built yet.

**How to read this.** Each finding has a severity, the evidence, and the amendment I recommend. **[verified]** means I read the code or ran the command named. **[inferred]** means a reasoned estimate I did not execute. I edited no spec, plan, research or task file; the parent session applies the amendments.

Severities:
- **BLOCKER**: the plan as written will build the wrong thing, or a stated acceptance check cannot pass. Fix before Phase 2.
- **SHOULD-FIX**: a real gap or bug the build will hit. Amend the tasks.
- **NOTE**: a smaller correction, or something worth recording.

**Tally: 4 BLOCKER · 11 SHOULD-FIX · 9 NOTE.**

---

## BLOCKERS

### 1. "Fits an open slot" = `rosterNeed > 0` is always true. Auto-pick's need rule collapses to plain ADP. — BLOCKER [verified]

**Evidence:**
- `SportRules.java:33-37` documents `rosterNeed` as returning a value "in [benchFloor, 1]".
- Both implementations return `cfg.benchFloor() + (1 - benchFloor) * captured`, and return `benchFloor` on the early exit: `BasketballRules.java:301-318`, `FootballRules.java:183-230`.
- `config/weights.yml:31,70` sets `benchFloor: 0.15` for both sports.
- So `rosterNeed(...) > 0` holds for **every** candidate. Research R5 step 2 never filters anything, and step 3 is dead code. MockAutoPickIT's "need over ADP" case (T040) can only pass if the test is written wrong.

**Second problem: semantics.** Even with the threshold fixed (`> benchFloor`, i.e. `captured > 0`), the predicate means "would start, possibly by displacing a weaker starter". It does not mean "fills an open slot". Two consequences:
- **NBA:** with two UTIL slots, almost any player is `captured > 0` until nine starters are kept. After that, `captured > 0` means "beats the cheapest starter in his eviction circuit" (`maskEvictValue`, `BasketballRules.java:250-265`), which is not an open slot.
- **NFL:** a K or DEF with an empty K/DEF slot gets `captured = 1`. Without the bots' hard gate `isDraftable` (`PickDecider.java:99`, `FootballRules.java:304`), auto-pick can take a kicker in round 9–10, which no bot would do.

**Amendment:**
- Spec FR-020 and US5 scenario 2 say "open roster slot" / "open slot". Pick one meaning and write it down:
  - **(a) "Would start" (recommended).** Filter candidates by `rules.isDraftable(e, lineup, round, rounds)` first, exactly as `PickDecider.choose` does. Then take the top by ADP with `rosterNeed(e, lineup) > benchFloor`, reading `benchFloor` from `ScoringProperties` for the session's sport. Then fall back to the top draftable by ADP.
  - **(b) "Open slot", literally.** Add an abstract `SportRules.fillsOpenStarterSlot(BoardEntry, Object lineup)` with **no default** (repo rule) and implement it in both sports. NBA reads `maskCanJoin`; NFL reads dedicated-slot and FLEX occupancy.
- Research R5's "Fits is defined as `rosterNeed > 0`" and the plan's risk bullet both need an "amended after review" note.
- T040 must include a case that **fails** under `> 0`: a roster whose top-ADP available player is bench-only must not be chosen. For NBA, it must include a 10th-pick case where all nine starter slots are filled.

### 2. `pickTimerSeconds` on the live state frame never reaches a pre-draft room. — BLOCKER [verified]

**Evidence:**
- There is no `LiveState` Java record. The frame is a `LinkedHashMap` built by `LeagueController.statePayload` (`LeagueController.java:730-755`) from a `LiveDraftPoller.LiveSnapshot` record (`LiveDraftPoller.java:163-164`).
- The **first** frame on every stream is synthesized from the DB (`LeagueController.java:500-505`), which has no `pick_timer` column. So it would carry `null`.
- Later frames are sent only when `changeKey` changes. That key is `status|picksMade|seatsMapped` (`LeagueController.java:717-718`, compared at `:536-538`).
- In `pre_draft` with no draft order (the screenshot state), none of the three moves. The timer would read null for the whole wait, which can be hours. US4 scenario 2 and quickstart step 4 ("4 teams · 14 rounds · 2 min · snake") would fail in exactly the state they were written for.
- Research R7 also has the projection room show "no timer", although that room is the same draft and FR-017 says the timer shows "when the draft has one".

**Amendment:**
- Store it. Add a nullable `pick_timer_seconds int` column to `draft` in V30.
- Write it in `LeagueIngestService.ingestDraft`, adding it to `DraftRepository.upsert`'s insert and its `on conflict do update` list.
- Refresh it in `LiveDraftPoller.pollOnce`, beside `drafts.updateStatus` (the `raw` draft is already in hand).
- Return it, with `draftType`, from `seats()` via a small dedicated query (see #12), not on the SSE frame.
- Drop the `LiveSnapshot` change from T005; T005 becomes the column, ingest and poller write.
- `SeatsResponse` gains `pickTimerSeconds?: number | null` (optional; see #17).
- Projection and live rooms then both show it. Only the mock has none.

### 3. The screenshot's draft (`1414361905279049728`) cannot be ingested or opened, and quickstart depends on it. — BLOCKER [verified]

**Evidence:**
- `curl https://api.sleeper.app/v1/draft/1414361905279049728` → `league_id: null`, `metadata.type: "league_mock"`, `metadata.league_id: "1414306784801239040"`.
- `curl …/league/1414306784801239040/drafts` returns **only** `1414306786223153152`, the league's real draft: also 4 teams, 14 rounds, `pick_timer: 120`, `pre_draft`, `draft_order: null`. The mock is not listed.
- Ingest discovers drafts only through `sleeper.drafts(leagueId)` → `/league/{id}/drafts` (`LeagueIngestService.java:127-130`, `SleeperClient.java:43-44`). Nothing else writes a `draft` row.
- `seats()` and `live-stream` both 404 through `membership.visibleDraft` (`LeagueMembership.java:247-251`) when the row is absent.
- Measured on the local 5433 DB: neither draft id, nor league `1414306784801239040`, is present.
- Conclusion: the room in the screenshot was almost certainly showing the league's real draft. The Sleeper URL the user pasted is a separate Sleeper mock. Research R7's "the screenshot's draft is a Sleeper mock attached to a league" conflates the two.

**Amendment:**
- Correct R7 visibly. The "0 of 4 managers identified" state comes from `draft_order: null` on a `pre_draft` draft. That is true of the real league draft too, so the unclaimed-header case stands, but not because of `league_mock`.
- Quickstart prerequisites and steps 1 and 4 should use `1414306786223153152`, after ingesting league `1414306784801239040` (`POST /api/ingest/all/1414306784801239040`).
- State plainly that a Sleeper `league_mock` draft is out of reach of this app's ingest. Don't add support for it in this spec.

### 4. SC-001 / FR-001a at 1440×900 is very likely infeasible during a draft at today's cell density. — BLOCKER [inferred from CSS, not measured]

The plan's arithmetic (R2: 7 × 32 px board rows, 8 × 48 px list rows ≈ 756 px) leaves out most of what the room puts on screen.

**The board.** From `styles.css`:
- A filled `.board .cell` is about 66 px tall: padding 6+7 (`:918`), the pos badge line, the name (`margin-top: 5px`, `:1008`), and the meta line with a 16 px face (`:1009`, `DraftBoard.tsx:240`). Plus the 4 px grid gap.
- The `.col-head` is about 70 px: padding 8+8, avatar, name, an 8 px meta row (`:872-901`).
- An empty cell (`pickno-open` only) is about 27 px. That is the only state where 32 px rows hold.
- So 7 filled rounds ≈ **560 px** for the board alone.

**The list.**
- `td` padding 6+6 with a face is about 33 px per row (`:817-818`). Eight rows plus a tier head and the thead ≈ 330 px.
- The list head (title, position chips, depth chips, stats toggle) ≈ 90 px, plus the new search/hide row ≈ 36 px.
- The target strip ≈ 44 px and the divider 8 px.

**The chrome R2 omits entirely:**
- `.app > .content` padding 16 (`:246`);
- `LiveStatusBar`;
- the new FormatSummary and RoomControls;
- the 16 px `.content` gaps (`:1043`);
- the browser's own chrome, since "1440×900" is a window, not a viewport.

**Rough total:** about 1,150–1,250 px needed against roughly 800 px usable. Pre-draft (empty cells) it is borderline (~950). These are estimates, not measurements, but the gap is wide enough that tuning a default constant in T017 cannot close it.

**Amendment:**
- Before T010, run a 15-minute browser spike on the current main room at 1440×900. Measure the filled-cell height, the col-head height, the list row height and the chrome. Record the numbers.
- Then decide one of the following and amend SC-001 visibly:
  - a compact cell density in split mode (for example, drop the meta line and face below a given board-region height);
  - lower the bar to what was measured (e.g. "≥ 4 filled rounds + ≥ 8 rows" or "≥ 7 rounds pre-draft");
  - require "8 rows visible", not "the whole of the player list", which is ill-defined for a scrolling table of 100+ rows.
- Also state whether "1440×900" means the window or `innerWidth × innerHeight`.
- Also decide where the "below" region goes (#8). If it sits under the list, it is either off-screen or eats this budget.

---

## SHOULD-FIX

### 5. The Claim pill can't use the "existing SeatPopover → make mine path" for an unclaimed seat. — SHOULD-FIX [verified]

**Evidence:**
- `seats()` builds `seats` only from `slotToManager` entries (`LeagueController.java:146-165`). An unmapped slot has **no** `Seat` object.
- `DraftBoard` renders that header `disabled={!seat}` and its `onClick` requires `seat` (`DraftBoard.tsx:121-122`).
- `LiveDraftView` renders `SeatPopover` only when `seats.seats.find(slot)` succeeds (`LiveDraftView.tsx:952-976`). `DraftView` follows the same pattern.
- In the screenshot state (no draft order) every header is unclaimed, so `onSeatClick` → popover would render nothing.

**Amendment:**
- Plan Constitution row 6 and R3 claim "no new seat mechanism". Correct that.
- The Claim pill needs a direct `onClaim(slot)` that does what `onMakeMine` does (sets `?slot=N`, `LiveDraftView.tsx:963-972`) without a `Seat` object.
- The claim must not show on a mock's BOT seats. The mock passes every seat with `type: BOT` and name "Bot N" (`MockDraftService.java:514-522`), so "unclaimed" must mean "real draft and no manager", not "no manager".
- T018/T020 need a case for a header with no `Seat`.

### 6. The 14 red outlines come from `myPicks`, not `mySlot`; the column mark must not be derived from `myPicks`. — SHOULD-FIX [verified]

**Evidence:**
- Per-cell `mine` comes from `myPicks` (`DraftBoard.tsx:94,186`).
- Live passes `myPicks={result?.myPicks ?? []}` regardless of `slotKnown` (`LiveDraftView.tsx:708`). That is the simulation's assumed seat 1 (`DEFAULT_SLOT`, `:153`), so the outline paints an assumed seat as yours. That is screenshot problem 4.

**Amendment:** in T020, derive the column tint and badge **only** from `mySlot` / `mySlotAssumed`, and remove the per-cell `mine` class. DraftView and the mock already pass `mySlot`, so nothing is lost; a seat's picks are always its column. T018 should assert that, with `myPicks` non-empty and `mySlot` undefined, no cell or column carries a "mine" class.

### 7. "Landed vs projected" has no current visual, and the live board hides projected cells entirely. FR-008 / SC-003 assume otherwise. — SHOULD-FIX [verified]

**Evidence:**
- Live passes `revealedThrough={live ? live.picksMade : undefined}` (`LiveDraftView.tsx:714`). `DraftBoard` blanks every cell with `pickNo > revealedThrough` (`DraftBoard.tsx:157-158`). So once a state frame exists, the live board shows **only** landed picks; projected picks appear nowhere on it.
- In DraftView every revealed cell is a projection. Its only "real" cells are the user's own `chosen` picks.
- No CSS marks a cell "projected": a grep of `styles.css` finds only `.uncertain` (`:985`), which means "non-modal", not "projected".
- So R3's "Today projected cells are lighter" is wrong. US2 scenario 6 and SC-003 ("landed or projected, for any cell") describe a board that doesn't exist in any room today.

**Amendment:** decide explicitly, and record the decision in spec and research with an amended note:
- **(a)** The live board starts drawing the projection past `picksMade`, styled as projected. This is a real product change, and it collides with lessons #5 (a modal board read as one coherent board) and the board-first doc's choices. **Or (b)** keep live cells past `picksMade` empty and redefine FR-008 per room:
  - live: landed vs empty;
  - projection room: projected vs your own pick;
  - mock: every cell is real.
- Either way, T020's "make landed and projected styling explicit via `revealedThrough`" is not the mechanism for the live room. T018's landed/projected case must name the room it models.

### 8. The `DraftRoomLayout` contract has no home for half of each page's content. — SHOULD-FIX [verified]

**LiveDraftView** puts `PickFeed`, "Your team" and `.live-meters` (ScarcityMeter + OnBrandPanel) **above** the board today (`LiveDraftView.tsx:765-810`). The contract sends them to "below". In a full-height board/divider/list grid, "below" is either under the fold (page scroll, which breaks SC-001's "both visible") or squeezed. The feed and the scarcity chips are draft-night-critical; FR-024 says they must keep working, which arguably means stay visible.

**DraftView** has things T015 doesn't map:
- the "Ready when you are / Start the mock draft" CTA overlay (`DraftView.tsx:603-627`), which needs `.start-overlay` CSS that T012 deletes;
- `PickPrompt` and the pause banner (`:535-552`), which are draft actions in the room header;
- the `seatsDirty` banner (`:488-495`);
- the re-run progress bar (`:478-482`);
- the settings gear rendered by a `createPortal` into `pageActionSlot` (`:~470`).

"controls: skip and re-run" is also inaccurate: re-run lives in the gear modal, not the room.

**Amendment:**
- Add regions, or state placement, for `prompt` (PickPrompt / pause banner) and `notices` (seatsDirty, errors, progress).
- Put "below" somewhere that is not under the list at desktop (a narrow side column, or folded into status), and count its height in #4's budget.
- T015 must list the start CTA as the board region's absence content and keep its CSS. T012's ".start-overlay delete" applies to live only.

### 9. The mock room's "always mounted, read-only between turns" question is moot, but `userPicks` must learn AUTO. — SHOULD-FIX [verified]

**Why the question is moot (verified):**
- The service invariant (`V3__mock_draft.sql` comment on `current_pick_no`; `MockDraftService.advanceAndPersist`) means that whenever a request returns, the mock is either on the user's turn or COMPLETE. "Between turns" exists only during an in-flight POST.
- `AvailabilityPanel` has no draft action at all. Its props (`AvailabilityPanel.tsx:51-108`) take no `onPick`; drafting happens in the `OnTheClockPickInput` modal.
- So "always mounted" is correct. "Read-only" is already true.
- Decide what the list shows on COMPLETE: probably an explained absence ("Draft complete").

**What breaks:**
- `MockDraftView.tsx:117-119` builds `userPicks` from `source === 'USER'` only. AUTO picks would then:
  - not render as `chosen` on the board;
  - be missing from `draftedByUser`, the roster strip the pick modal shows.
  - That breaks US5 scenario 4 ("look exactly as they would if you'd made those picks by hand").
- T044's "map source into the feed" is under-specified. `PickFeed` takes `FeedPick` (`PickFeed.tsx:9-22`), which has no source field.

**Amendment:**
- T043/T044: treat `USER | AUTO` as the user's picks everywhere `'USER'` is tested (`MockDraftView.tsx:118` is the only site; grep confirms it).
- Add `auto?: boolean` to `FeedPick` (optional and additive, like `fit`).

### 10. Targets UI: refetch-on-focus and overlapping PUTs lose edits; the server has a 500 race. — SHOULD-FIX [inferred from design; SQL not run]

**On the client:**
- T030's "re-fetch on window focus" will overwrite an unsaved edit:
  - after a failed save, which violates FR-014a's "keep the user's edit on screen";
  - while a PUT is in flight.
- Rapid reorder clicks fire overlapping PUTs. A slow older response that arrives last can overwrite newer local state if the hook applies PUT responses.

**On the server:**
- R4's delete-then-insert under READ COMMITTED races. Two concurrent PUTs for the same owner and draft both delete, then both insert the same `(owner, draft, player)`. The second hits the partial unique index → `DuplicateKeyException` → 500.

**Amendment:**
- Client:
  - serialize PUTs (one in flight, coalesce to the latest list);
  - never apply a PUT response over newer local state;
  - skip the focus refetch while dirty, in flight or errored.
- Server:
  - take `pg_advisory_xact_lock(hashtext(owner || ':' || scope))`, or `select … for update` on the owner+scope, at the start of `replace`;
  - and/or map the duplicate key to 409.
- T004 should run the two-concurrent-PUT case by hand (lessons class 2).

### 11. Auto-finish cost and the fork's off-board edge. — SHOULD-FIX [verified code; timing not measured]

**Cost:**
- Each `submitPick` calls `buildContext` → `boards.currentBoard(sport)` (a full player-table scan plus board load, per the comment at `MockDraftService.java:484-488`) and `profiles.fit(sport)` (not cached, `ProfileService.java:244-245`).
- `advanceUntilUserOrEnd` then replays every pick from pick 1 (`MockDraftEngine.java:79-96`).
- A FINISH from the first turn of a 12-team NBA mock is 14 iterations of all three.
- T041's "build the DraftContext once per loop iteration" is the expensive version.

**Edge cases:**
- A target, or a top-ADP player, that is in `player` but not on the current board (`ctx.byId` miss) would be inserted. The engine's replay then logs "already off the board" and skips him (`MockDraftEngine.java:83-96`). The roster silently loses that pick.

**Amendment:**
- Fetch `board` and `fit` once per request and call `contexts.build(...)` per iteration with the growing `completed` map.
- Only consider targets and candidates present in `ctx.byId` and not yet drafted.
- Measure FINISH in T045 as planned.
- Because the mock's idle state is always "your turn" (#9), FINISH's first iteration is always an AUTO pick. Say so in R5.

### 12. Don't widen `DraftRepository.DraftRow` for `draftType`. — SHOULD-FIX [verified]

**Evidence:**
- `DraftRow` has no `draft_type` (`DraftRepository.java:173-189`). It already carries three back-compat constructors that default trailing fields.
- It is constructed in **33** places across main and test.
- Adding another defaulted constructor is the "optional params that encode rules" pattern the repo memory warns about.
- Also, `upsert` does not update `draft_type` on conflict (`DraftRepository.java:43-55`), so the stored value is first-ingest-only.

**Amendment:**
- T006: add one query, `DraftRepository.format(long draftId) → (String draftType, Integer pickTimerSeconds)`, and call it in `seats()`, the same shape as the existing `drafts.reversalRound(id)` at `LeagueController.java:213-214`.
- Add `draft_type = excluded.draft_type` to the upsert's conflict list (same change as #2).

### 13. NBA multi-position: deferring is acceptable for this spec's scenarios, but name the new disagreement it creates. — SHOULD-FIX [verified]

- **The deferral is OK.** No acceptance scenario in spec 024 depends on SG/SF filters being populated. FR-018 only asks that 0/0 not show, and research R6's note is honest.
- **The backend is multi-position.** `Player.positions()` is multi-valued, and `BasketballRules.isEligible`, `maskOf` and `rosterNeed` all read it (`BasketballRules.java:420-430`). Local DB: 1,679 of 2,091 NBA players carry more than one position.
- **The frontend is single-position.** Only the frontend's `PlayerRef.position` is single.
- **The new disagreement.** Auto-pick (backend, multi-position) and the list's "fills a need" tag (`teamNeeds.ts`, single position) will now disagree about the same player in the same room. That is the "two implementations of one rule" class.
- **Amendment:**
  - Record this disagreement in R6.
  - Make the R6 note copy exact: the meter hides positions whose count is 0 *by first-listed position*.
  - Add the follow-up (`positions[]` on `PlayerRef`) to HANDOFF as owed. It's not in scope here.

### 14. The breakpoint gap (700 / 860 / 1280) strands the pick card and the board. — SHOULD-FIX [verified CSS; behaviour inferred]

**Evidence:**
- The pick card is `position: absolute` against `.board-stage` above 700 px, and `fixed` only at ≤700 px (`styles.css:1482-1530`).
- `useNarrow` is 860 px (`useNarrow.ts:16`). The new toggle is <1280 px.
- Between 701 and 1279 px, with the Players tab selected, the board region is hidden and an arriving pick card is invisible. That is the same bug T042 (spec 012) found at 375 px.
- At ≥1280 the board region becomes a fraction of the height (~300–400 px). The pick card (`top: 8px`, up to 440 px wide, a header plus fit, summary and tags) can cover most of the board region.

**Amendment:**
- T011/T014: the card anchors to a non-scrolling `position: relative` wrapper around the board region.
- Below 1280, make it `fixed` (or force-show the board when a card opens).
- Live-check a pick card at 1024 and at 1440 in T045.

### 15. Auto-pick ignores `isDraftable`; FINISH can build a roster the engine would not. — SHOULD-FIX [verified]

This is the same root as #1, but called out because it affects step 3 (the plain ADP fallback) too. The bots never draft a non-draftable player (`PickDecider.java:99`).

**Amendment:** filter by `isDraftable` in all three steps. A target who isn't draftable is skipped, not taken.

---

## NOTES

### 16. Snake helpers, round.pick and the reversal-round prop. [verified]
- `roundPickLabel(15, 12)` → `"2.03"` (`roundPickLabel.ts:3-7`, padStart 2). ✓ T019's "confirm it pads" is satisfied; no new variant is needed.
- `snake.ts` exports `isForward(round, reversalRound)` with a required `reversalRound` (`snake.ts:27-31`). ✓ It is the direction helper R3 needs.
- `DraftBoard`'s own `reversalRound?: number` prop **is** defaulted to 0 (`DraftBoard.tsx:42,87`). That is the footgun the memory names, and T019 only hardens "the internal helper", which is already hard.
- All four callers already pass it (`LiveDraftView:718`, `DraftView:578`, `MockDraftView:171`, `CompletedDraftBoard:219`). Make the prop required in T019 at no cost.

### 17. `api.ts` mirrors: optional, not just nullable, and the names are off. [verified]
- **Split deploy.** The frontend and backend deploy separately (precedent: `sourceSleeperLeagueId?` and `sleeperLeagueId?`, the 2026-09-14 white page). `draftType` and `pickTimerSeconds` should be `?: … | null`.
- **Absent endpoints.** `getTargets` and `autoMock` must degrade on a 404 from an older backend. The strip shows "targets unavailable"; the auto controls hide.
- **Names.** T007's "PickView.source in api.ts (~line 611)" is actually `MockPick.source` (`api.ts:600-616`). The Java record is `MockSessionState.PickView`.
- **The live frame.** "Mirrors the backend's LiveState record" (`api.ts:396`) refers to a record that doesn't exist (#2). If #2 is adopted, no `LiveState` change is needed at all.

### 18. Tests named as new or existing. [verified]
- **New, confirmed absent:** `DraftBoard.test.tsx`, `SplitDivider.test.tsx`, `DraftRoomLayout.test.tsx`, `TargetStrip.test.tsx`, `FormatSummary.test.tsx`, `targets.test.ts`, `TargetControllerIT`, `MockAutoPickIT`.
- **Existing, confirmed present:** `ScarcityMeter.test.tsx`, `LiveStatusBar.test.tsx`, `PickFeed.test.tsx`, `CompletedDraftBoard.test.tsx`, `LeagueControllerSeatsOwnerConfiguredIT.java`, the five `LiveDraftView.*.test.tsx`.
- **The existing pattern.** `MockDraftServiceTest` is a Mockito unit test that runs the **real** `FootballRules`/`BasketballRules`. That is the cheap, right place for #1's preference-ordering cases. A real-Postgres `MockAutoPickIT` would need a full seeded board.
- **Recommended split:**
  - ordering assertions in `MockDraftServiceTest`;
  - a slim IT only for the V30 `AUTO` insert and the endpoint's 404/409;
  - `/auto` and `/api/targets` cases added to `AccessControlMvcIT` (the repo's ACL suite, which already covers `/api/mocks/*`).

### 19. 409 is achievable, and visibility gates targets. [verified]
- **409:** `ErrorHandler.java:21-24` maps every `IllegalStateException` to 409, and `submitPick` already throws it for "already complete" and "not the user's turn" (`MockDraftService.java:394-408`). `auto()` throwing the same gives 409 with no controller work.
- **404:** not-owned is `Optional.empty()`, which becomes 404.
- **Visibility:** `membership.visibleDraft` (`LeagueMembership.java:247-251`) returns empty for a non-member, so `TargetController` using it 404s a non-member's GET and PUT.
- **Unspecified (decide in the contract):**
  - the precedence between "anonymous GET → empty list" and "anonymous → 404 via `visibleDraft`";
  - an **admin-token request with no `X-Sleeper-User`**, since `owner_sleeper_user_id` is NOT NULL. It must 401 on PUT even for admin.

### 20. Off-board targets and `PlayerRef.adp`. [verified]
- `SimulationResult.PlayerRef.adp` is a primitive `double` (`SimulationResult.java:27-28`). The contract's "fall back to the player row for a target off the board" would have to invent an ADP.
- **Amendment:** either return off-board targets in a separate `missing: [{sleeperId, name}]` list, or add a nullable or flagged field to a dedicated `TargetView` record. Don't fake an `adp`.

### 21. Mock constraints the quickstart runs into. [verified]
- **Team counts.** `mock_draft_session_teams_check` is `teams in (8,10,12,14)` (measured in `pg_constraint`), and `LeagueShape.SUPPORTED_TEAM_COUNTS` matches.
  - The 4-team screenshot league can never be forked.
  - Forking also requires `status = drafting` (`MockDraftService.java:238-242`).
  - So quickstart step 2's "a mock forked from it" is impossible for that league. Use a 12-team drafting league, or compare parity against an ordinary mock.
- **Round count.** The NBA mock default is 14 rounds (`LeagueShape.NBA_ROUNDS`). SC-006 and quickstart 7's "12-team, 13-round" mock can't be created from scratch; say "12-team NBA mock (14 rounds)".

### 22. The Sleeper reset edge case contradicts R9. [verified by reading]
- The spec edge case says stale "taken" marks should clear once picks go away.
- R9 says, correctly, that `upsertPicks` never deletes, so they won't.
- Amend the spec edge case to say this is not met until the open reset bug is fixed.

### 23. Accent folding doesn't exist yet. [verified]
- T029 says "reuse any existing accent folding in `searchIndex.ts`". There is none: no `normalize(` anywhere in `web/src`, and `searchDestinations` only lowercases (`searchIndex.ts:266-267`).
- The need is real: the local DB has `Nikola Jokić`, `Luka Dončić`, and 91 NBA names with non-ASCII characters (measured).
- Write one `fold()` in `targets.ts` (NFD + strip `\p{M}` + lowercase). Note that it is new.

### 24. The rest is checked and fine. [verified]
- **V30** is free: every local branch, remote and worktree tops out at V29.
- **The constraint name** `mock_draft_pick_source_check` is right (V4 drops and re-adds it by that name).
- **`Map.of`:** `seats()` and `statePayload` already use `LinkedHashMap`, so adding `draftType` or `pickTimerSeconds` there is safe.
- **`useAnnouncer`** is a page-level hook (`LiveDraftView.tsx:683`) independent of layout. The layout move doesn't affect it. The pick card does, see #14.
- **`.live-waiting`** is at `LiveDraftView.tsx:936-941`, and the ResizeObserver at `AvailabilityPanel.tsx:233-265`, as R2 and R8 say.
- **"Continue as a mock"** is disabled (not hidden) unless `drafting` (`:870`). FR-019's "whenever it is available" should say whether pre-draft hides it or shows it disabled.
