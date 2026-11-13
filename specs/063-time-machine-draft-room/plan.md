# Implementation Plan: Time Machine draft room and playoffs

**Branch**: `063-time-machine-draft-room` | **Date**: 2026-11-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/063-time-machine-draft-room/spec.md`

## Summary

Three changes to Time Machine, both sports.

1. **Pick pacing** is a browser-only reveal over picks the server already made. A new hook holds "revealed through pick N" and the room renders a *view* of the server state cut at N. No endpoint is added; one response field (`reversalRound`) is, see the review amendments at the end.
2. **Playoffs** are a change to the pure `SeasonReplay`: the round robin stops at the regular-season boundary, three playoff weeks follow, and the stored result gains a `format`, a `playoffs` block and `finalPlaces`. Old stored results lack those fields and render as the old format. No migration.
3. **The room** gets its own layout (banner, pick rail, tabbed main area with Players / Queue / Board, roster panel) instead of the shared board-on-top `DraftRoomLayout`. The queue is browser-only.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5 strict (web)

**Primary Dependencies**: Spring Boot 3.5, Jackson; React 18, Vite, react-router, vitest + Testing Library

**Storage**: Postgres. `historical_game.result` is a JSON column written once at the last pick. **No schema change.** Browser `localStorage` for reveal speed and the queue.

**Testing**: JUnit 5 (`./gradlew test`, ITs need Postgres on 5433 or they skip silently: check the skip count); vitest (`npx vitest run`), `npx tsc -b`

**Target Platform**: the deployed web app (Railway, split services), desktop and phone widths

**Project Type**: web application (`backend/` + `web/`)

**Performance Goals**: the replay stays under its current wall-clock (it scores fewer weeks of round robin and adds 5 matchups). Instant speed must cost no more than today (SC-008).

**Constraints**: same stored game → byte-identical result (FR-019). Old results untouched (FR-020). Shared draft-room components must behave identically in the mock and live rooms.

**Scale/Scope**: 12 teams, 14 or 15 rounds, up to 26 weeks. About 6 backend files and 12 web files touched or added.

## Constitution Check

| Rule | Status |
|------|--------|
| Migrations append-only | Pass. No migration. Highest is V47; none added. |
| `web/src/api.ts` mirrors Java records field for field | Applies. `HistoricalResult` (new nested records, new `Week.phase`) and `GameSummary` (two new fields) change; `api.ts` changes in the same task as each. |
| Never retune a hand-set constant to match a guess | Applies. Four reveal intervals and two regular-season lengths are new hand-set numbers; each is labelled hand-set where defined. None is fitted. |
| `Map.of` and nulls on a JSON path | Applies. `playoffs`, `format`, `phase`, `userOutcome` are null for some results. New records are plain records; no `Map.of` is used to build them. |
| Ask before committing | Applies. Nothing is committed without Allan's go-ahead. |
| A planning doc's scope is not a verified spec | Applied. Every "what is there" claim below was read from the code at 1369668. |

No violations. Re-checked after design: unchanged.

## What the code does now (read, with references)

- `HistoricalGameService.place` → `advanceBots` (`HistoricalGameService.java:255-285`): inserts the user's pick, runs `engine.advanceUntilUserOrEnd`, persists every bot pick, and if the draft is complete calls `finishDraft`, which replays the season and writes the result. One request, one response.
- `HistoricalGameState` carries `picks`, `available`, `currentPickNo`, `onTheClockSlot`, `isUsersTurn`, and `result` as a passed-through `JsonNode` of the stored JSON.
- `SeasonReplay.replay` (`SeasonReplay.java:176-275`): scores every team-week into `points[w][s]`, pairs teams with the circle method for all `weekCount` weeks, ranks standings, then calls `Comparisons.compute(in, team, userPf)` where `userPf` is the user's points for over **all** weeks.
- `Comparisons` uses `in.weekCount()` for whole-season values (`Comparisons.java:59,148-184`).
- `HistoricalGameRepository.java:106` reads the list page's `finish` as `(g.result ->> 'userFinish')::int`.
- `web/src/pages/HistoricalRoom.tsx` renders header, `SeasonDisclosure`, then `DraftRoomLayout` with `TurnIndicator`, `RoomControls`, `DraftBoard` and `HistoricalPlayerList`. `run()` sets the whole new state at once.
- `web/src/pages/HistoricalDraft.tsx:47` prints `Finished ${ordinal(g.finish)}` for a complete game.
- Reusable today: `web/src/teamNeeds.ts` `computeTeamNeeds` + `components/TeamStrip.tsx` (roster by slot, both sports, pinned to the backend by a parity fixture); `components/PickFeed.tsx` (recent picks); `useRevealedBoard.ts` (the league simulator's reveal).

## Design

### A. Playoffs (backend)

**A1. Season format.** New pure class `historical/SeasonFormat`:

```
SeasonFormat.of(seasonWeeks, configuredRegularWeeks, teams) ->
  record SeasonFormat(int version, int seasonWeeks, int configuredRegularSeasonWeeks,
                      int regularSeasonWeeks, List<Integer> playoffWeeks,
                      int unusedWeeks, boolean shortened)
```

- `PLAYOFF_TEAMS = 6`, `PLAYOFF_WEEKS = 3` are code constants: they are the bracket's shape, not tunables.
- `seasonWeeks >= configured + 3` → regular = configured, playoffs = the next three weeks, unused = the rest.
- else if `seasonWeeks - 3 >= teams - 1` and `teams >= 6` → regular = `seasonWeeks - 3`, `shortened = true`, unused = 0. *(Amended after review, PR-03: the floor was `>= 1`; it is now one full round robin.)*
- else → no playoffs: `playoffWeeks` empty, regular = `seasonWeeks`. This arm exists for synthetic test seasons; no real season reaches it (shortest real NBA season has more than 4 weeks, to be confirmed by a query in research R3). The result still carries `format.version = 2` with an empty `playoffWeeks`, `playoffs = null`, `finalPlaces` in standings order with outcome `NO_PLAYOFFS`, `userOutcome = NO_PLAYOFFS`, and the finish is the standings rank *(amended after review, PR-08)*.

**A2. Configuration.** `historical.regular-season-weeks: 19` and `historical-nfl.regular-season-weeks: 14` in `application.yml`, each commented "league-format choice (spec 063, Allan 2026-11-13), not a measurement". Bound in `HistoricalProperties` / `NflHistoricalProperties` with the existing `positive(...)` validation, exposed as `HistoricalSport.regularSeasonWeeks()`. `SeasonReplay.Input` gains `int regularSeasonWeeks` (the configured value; the replay derives the format itself so the function stays pure).

**A3. Replay.** In `SeasonReplay.replay`:

1. Score `points[w][s]` for weeks `1..lastPlayedWeek` where `lastPlayedWeek` is the final playoff week (or `seasonWeeks` with no playoffs). Weeks after it are not scored for standings.
2. Round robin and W-L-T/PF over `1..regularSeasonWeeks` only. Standings order and the seeded coin are unchanged.
3. Seeds 1..6 = standings places 1..6.
4. Round 1 (first playoff week): 3 v 6, 4 v 5; byes: seeds 1, 2. Round 2: seed 1 v the higher-numbered surviving seed, seed 2 v the other. Round 3: the two winners.
5. Winner: compare `Math.round(points * 100)`; level → the lower seed number wins and `decidedBySeed = true`.
6. Final places per FR-016. `userFinish` = the user's final place. `userOutcome` ∈ `CHAMPION | LOST_FINAL | LOST_SEMIFINAL | LOST_ROUND_ONE | MISSED_PLAYOFFS` (null in the no-playoffs arm).
7. `weeks` = regular weeks as today, then one `Week` per playoff week holding only that week's playoff matchups (home = better seed). `Week` gains `String phase` (`"REGULAR"`, `"PLAYOFF"`).
8. The user's `lineups` cover regular weeks plus each playoff week in which the user plays a matchup (FR-018).
9. **Comparisons keep the whole season.** `Comparisons.compute` is still called with `in.weekCount()` and with the user's points for over **all** season weeks, computed separately from the standings PF. Today one number serves both; after this change they differ. This is the trap in this change: passing the regular-season PF would silently shrink "You" in the hindsight panel while "Hindsight" stays whole-season.

No new randomness is drawn, so the seed's existing draws (schedule shuffle, coin) are unchanged.

**A4. Result shape.** `HistoricalResult` gains, appended after the existing fields so old field order is kept: `Format format`, `Playoffs playoffs`, `List<FinalPlace> finalPlaces`, `String userOutcome`. See [data-model.md](data-model.md). An old stored result has none of them: `format == null` is the old-format test, used in exactly one web helper (`isOldFormat(result)`).

**A5. List page.** `GameSummary` gains `Integer formatVersion` and `String outcome`, read in the same SQL as `finish`: `(g.result -> 'format' ->> 'version')::int`, `g.result ->> 'userOutcome'`. Both null for old or unfinished games. The SQL is run against Postgres in an IT (bug class 2).

**A6. Old games.** Nothing rewrites `historical_game.result`. An IT stores a 062-shaped result JSON fixture and asserts `GET /api/history/games/{id}` returns an equal JSON tree *(amended after review, PR-09: the column is `jsonb`, so bytes and key order are not preserved)*.

### B. Pick pacing (web)

**B1. Hook.** New `web/src/usePickReveal.ts`:

```
usePickReveal(state, speed) -> { view, revealing, skip, begin(prevPickNo) }
```

- Holds `revealedThrough` (a pick number). On first load it equals the last made pick: nothing replays (FR-007).
- `run()` in the room records `fromPickNo = state.currentPickNo` before the request. When the response arrives, the user's own pick or picks are shown at once and the bot picks after them are stepped with a `setTimeout` chain, one per tick, until `revealedThrough` reaches the last made pick.
- `view` is a derived `HistoricalGameState`-shaped object: `picks` filtered to `pickNo <= revealedThrough`; `available` = **the pool**: the `available` list of the last response that was still `DRAFTING` (or the first load), minus every revealed pick's player *(amended after review, PR-01 BLOCKER: the plan said "the server's available plus unrevealed cards", but the response that completes the draft carries `available = []`, so the final reveal would have shown only the players about to be picked)*; `currentPickNo = revealedThrough + 1`; `onTheClockSlot` = the `draftSlot` of the next unrevealed pick while revealing, the server's value once caught up *(amended after review, PR-05: the page has no snake arithmetic; `myPicks` is server-given)*; `isUsersTurn` true only when the reveal has caught up **and** the server says so; `status` stays `DRAFTING` and `result` stays hidden until the reveal has caught up, then the true state shows.
- Every consumer in the room (board, rail, list, queue, roster, banner) reads `view`, never `state`. That single rule is what makes FR-002 hold; a vitest walks every reveal step of a full draft fixture and asserts no consumer input contains an unrevealed pick's player as taken.
- `skip()` sets `revealedThrough` to the end. Auto-finish calls `skip()` on response (results appear at once, as today).
- Timers are cleared on unmount and when a new response arrives.

**Why not `useRevealedBoard`**: it reveals a whole predicted board from pick 0 on each run and pauses on the user's picks; it has no notion of "resume from where the last response ended" or of a derived available list, and the league simulator depends on its exact behaviour. A second small hook is cheaper than adding a mode to that one. Recorded in research R1.

**B2. Speeds.** `web/src/revealSpeed.ts`: `SLOW 2000 ms, NORMAL 1000 ms, FAST 400 ms, INSTANT 0`, with a comment that all four are hand-set for feel (spec 063) and not derived. Stored under `localStorage['bk.tm.revealSpeed']`, read and written in try/catch, default `NORMAL`.

**B3. Reduced motion.** Pacing stays; the "new pick" highlight animation is disabled under `prefers-reduced-motion` in CSS.

**B4. Announcements.** One `aria-live="polite"` region in the room says "Pick 14: Team 5 took Hakeem Olajuwon". At FAST and INSTANT it announces only the summary when the reveal ends ("11 picks made. You are on the clock."), so a screen reader is not flooded.

### C. The room (web)

**C1. Layout.** New `web/src/components/HistoricalRoomLayout.tsx` with its own CSS grid. `DraftRoomLayout` is not edited, so the mock, live and simulator rooms cannot change.

- **≥ 1280 px**: a sticky banner row; below it three columns: pick rail (fixed ~240 px), main (flex), roster (fixed ~280 px). The main column has a segmented control: Players | Queue (n) | Board.
- **< 1280 px**: banner, then one segmented control with Players | Queue | Board | Picks | Roster, one pane visible at a time.
- The hidden panes are not rendered (unlike `DraftRoomLayout`, nothing position-fixed lives inside them), so there is no inert bookkeeping.

**C2. Banner** (`HistoricalClockBanner.tsx`): who is on the clock (from `view`), "Your turn" or "Your next pick in N picks" / "No picks left", the speed control (segmented, four options), Skip (enabled only while revealing), Auto-pick (only on the user's turn; label "Auto-pick: {top queued name}" when the queue has an available player) and Auto-finish (title: "Makes every remaining pick, yours included. Ignores your queue.").

**C3. Pick rail** (`HistoricalPickRail.tsx`): all picks of the current round and the next, plus the last few made, in pick order. A made pick shows player, position pill and team name; an upcoming pick shows the team name; the user's are marked; the current pick is marked and scrolled into view (without smooth scrolling under reduced motion). It does not show computer teams' position needs: that would imply the bots draft for need in a way this spec has not checked.

**C4. Players** : `HistoricalPlayerList` gains an optional per-row queue toggle (`queued`, `onToggleQueue`) and nothing else. **Queue** (`HistoricalQueue.tsx`): rows in queue order with Draft (on the user's turn), Move up, Move down, Remove. Buttons, not drag and drop: keyboard-operable by construction.

**C5. Queue state** (`web/src/useDraftQueue.ts`): an ordered list of player ids under `localStorage['bk.tm.queue.' + gameId]`, try/catch, pruned against `view.available` on every change of `view` (so a queued player leaves when his pick is revealed, FR-032). When Auto-pick is pressed with a non-empty pruned queue the room calls the existing `historicalPick(id, topId)`; otherwise the existing `historicalAuto(id, 'PICK')`.

**C6. Roster** (`HistoricalRosterPanel.tsx`): `computeTeamNeeds` over the user's revealed picks and `state.rosterPositions`, drawn as a vertical slot list (slot label, player or "empty"), then Bench. Reuses the seating rule; does not reimplement it. Heading: "Your roster so far".

**C7. Disclosure**: `SeasonDisclosure` moves inside a `<details>` ("What counts in 1995-96") below the room, closed by default while drafting, content unchanged. On the results page it stays as it is.

### D. Results (web)

- Finish headline from `userOutcome`: "Champion", "You lost the final", "You lost in the semifinal", "You lost in round one", "You missed the playoffs (8th of 12)".
- "Regular season" table (was "Final standings") with a rule and label between 6th and 7th; a line under the heading says how many weeks it covers and, when `format.shortened`, that the season was short.
- New `HistoricalBracket.tsx`: three columns (Round one, Semifinals, Final), each matchup a small card with seed, team, score, winner marked, "better seed advanced on a tie" where `decidedBySeed`; byes shown in round one; champion called out. On a phone the columns stack.
- Week picker gains playoff weeks labelled "Week 20 (round one)". The user's lineup shows for weeks they played; for a bye or after elimination it says so.
- A note under the bracket: "Weeks 23 to 25 of the real season were not played. The draft comparisons below cover the whole real season." (numbers from `format`).
- Old format (`format == null`): today's page exactly, plus one line: "Played under the old format: no playoffs, finish is the standings rank."
- List page: new games show the outcome words; old games show "Finished 3rd (old format)".

## Not being built, and why

- **Server-side pacing or a pick clock.** The bots are deterministic given the seed; streaming them adds a failure mode and buys nothing the reveal does not.
- **Undo and restart.** Allan chose the queue only.
- **A queue stored on the server.** It would need a table and a migration for something that only matters during one sitting.
- **Bot team needs in the rail.** Not verified to be how bots pick.
- **A third-place game or consolation bracket.** Not in the league format Allan described.
- **Re-scoring old games.** FR-020.
- **Changing `DraftRoomLayout`, `useRevealedBoard`, `DraftBoard` behaviour.** Other rooms depend on them.

## Acceptance for the verification pass

Run the real backend and web app against Postgres and:

1. Draft an NBA game by hand at normal speed; time ten consecutive bot picks with a stopwatch in the page (`performance.now()` on a mutation observer): each 0.5 to 1.5 s (SC-001).
2. During a reveal, confirm a not-yet-revealed player is still in the list and draftable-looking but the Draft button is absent; press Skip mid-reveal; reload mid-reveal.
3. Queue three players, watch one get taken, Auto-pick from the queue.
4. Finish one NBA and one NFL game. For each, hand-derive the seeds and bracket pairings from the regular-season table and check every bracket score against that team's week score in the week picker (SC-004).
5. Open a game completed before the change (captured id) and compare its finish to the pre-change capture (SC-006).
6. Finish a short NBA season (1998-99 or 2011-12) and read the shortened-season line.
7. 1366×768 and 375 px checks (SC-007), keyboard-only pass, reduced-motion pass.
8. Confirm the mock draft room and live room still look and behave as before (one game each opened).

## Project Structure

### Documentation (this feature)

```text
specs/063-time-machine-draft-room/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/api.md
├── checklists/requirements.md
└── (later) plan-review.md, tasks.md, code-review.md, verification.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── historical/SeasonFormat.java            NEW  pure
├── historical/SeasonReplay.java            CHANGED  regular season + playoffs
├── historical/HistoricalResult.java        CHANGED  format, playoffs, finalPlaces, userOutcome, Week.phase
├── historical/HistoricalSport.java (+ Nba/Nfl impls)  CHANGED  regularSeasonWeeks()
├── historical/HistoricalGameService.java   CHANGED  passes regularSeasonWeeks into Input
├── historical/HistoricalGameRepository.java CHANGED  GameSummary formatVersion, outcome
├── historical/HistoricalGameState.java     CHANGED  GameSummary record
└── config/HistoricalProperties.java, NflHistoricalProperties.java  CHANGED
backend/src/main/resources/application.yml  CHANGED  two regular-season-weeks keys
backend/src/test/java/.../historical/
├── SeasonFormatTest.java                   NEW
├── SeasonReplayPlayoffsTest.java           NEW  ordering tests
├── SeasonReplayTest.java, ComparisonsTest.java, HistoricalTestSupport.java  CHANGED (Input arity; expectations)
└── api/HistoricalGameMvcIT.java            CHANGED  old-result passthrough, summary fields

web/src/
├── api.ts                                  CHANGED  mirrors
├── usePickReveal.ts (+ test)               NEW
├── revealSpeed.ts                          NEW
├── useDraftQueue.ts (+ test)               NEW
├── historicalCopy.ts                       CHANGED  outcome words, isOldFormat
├── pages/HistoricalRoom.tsx (+ test)       CHANGED
├── pages/HistoricalDraft.tsx (+ test)      CHANGED  list wording
├── components/HistoricalRoomLayout.tsx     NEW
├── components/HistoricalClockBanner.tsx    NEW
├── components/HistoricalPickRail.tsx       NEW
├── components/HistoricalQueue.tsx          NEW
├── components/HistoricalRosterPanel.tsx    NEW
├── components/HistoricalBracket.tsx        NEW
├── components/HistoricalPlayerList.tsx     CHANGED  queue toggle
├── components/HistoricalResults.tsx (+ test) CHANGED
└── styles.css                              CHANGED  new blocks only
```

**Structure Decision**: the existing `backend/` + `web/` split. All new web components are Time Machine-specific and prefixed `Historical`, matching the files already there.

## Complexity Tracking

No constitution violations to justify.

## Amended after review (2026-11-13)

The adversarial review is [plan-review.md](plan-review.md): 1 blocker, 11 should-fix, 4 nits. Nothing in it was run against a database (Postgres was down). Each finding and what it changed, in addition to the in-place notes above:

| Finding | Change |
|---------|--------|
| PR-01 available is empty on the completing response | Fixed in B1 in place: the hook owns a player pool. |
| PR-02 ITs and synthetic seasons not in the plan | `HistoricalRealSeasonIT` and `NflGameFlowIT` join the changed-tests list; their "every team every week" assertions become "every team every regular-season week; playoff weeks hold only playoff matchups". `HistoricalSyntheticSeason` and `NflSyntheticSeason` each gain a long variant (at least 14 weeks, 12 teams) so an MVC IT sees a bracket; the short ones stay and pin the no-playoffs arm. These ITs skip without Postgres: the skip count is read after every run. |
| PR-03 which seasons are short; floor | Floor is one full round robin (in place). The first build task runs the week-count query and corrects the spec's list in place. A partial final week is accepted and disclosed. |
| PR-04 bot-strength harness reads `userFinish` | `TimeMachineBotStrengthHarnessIT` reads the user's `standings[].place` for its existing "wins" figure, so the 11% / 9% figures cited in `application.yml` keep their meaning, and adds a separate champion count. The yml comment gets a dated note saying which is which. Temperature 0.25 is **not** retuned. |
| PR-05 no client snake arithmetic; reversal is stored | `HistoricalGameState` gains `int reversalRound` (mirrored in `api.ts`, optional on the web so an old backend reads as 0). The pick rail computes upcoming slots from it with a small pure helper that has a parity test against `DraftSlot.slot` values generated by a backend test. The banner reads `draftSlot` from the next unrevealed pick. |
| PR-06 hook races | B1 is specified as: one `useReducer` holding `{state, pool, revealedThrough, target}`; actions `LOADED`, `RESPONSE`, `TICK`, `SKIP`; one effect that schedules a single `setTimeout` when `revealedThrough < target` and clears it in its cleanup (safe under StrictMode's double effect); `INSTANT` sets `revealedThrough = target` in the reducer with no timer; a failed request dispatches nothing, so a running reveal continues; the page header and the results switch read the view, not the raw state. Background-tab timer throttling is accepted: the reveal slows while hidden and Skip still works. |
| PR-07 deploy order and old-backend promise | Every new field is optional in `api.ts` and read through `== null` checks. `HistoricalOldBackend.test.tsx` gains a 062-shaped result and summary fixture. Deploy order: **web first, then backend** (new web renders an old result as old format; old web against a new backend would print a playoff finish above a table titled "Final standings"). Stated in quickstart. |
| PR-08 no-playoffs wording | Outcome `NO_PLAYOFFS`; headline "You finished 3rd of 12" with "This season was too short for playoffs."; list wording "Finished 3rd (no playoffs)". |
| PR-09 jsonb | In place. The determinism test compares the replay's serialised output; the storage IT compares trees. |
| PR-10 opening picks | Paced. On first load of a game in which the fan has made no pick, the reveal starts from pick 0. A reload before the fan's first pick replays it; Skip is there. |
| PR-11 queue | FR-006 reworded. The queue is pruned only while the loaded state is `DRAFTING`, against the view's pool. Its key is deleted when the game is seen `COMPLETE` or `ABANDONED`. |
| PR-12 board width | When Board is selected at 1280 px and up, the rail and roster columns collapse and the board takes the full width; the banner stays. |
| PR-13 two user totals | The comparison total is `team.season(userRoster, in.weekCount())`, the same function as the hindsight total. The results label it "whole real season" next to the regular-season points. |
| PR-14 callers and naming | Positional-constructor callers to update: `HistoricalGameMvcIT`, `NflHistoricalPropertiesTest`, `NflStatKeysAgreeTest`, `NflverseSourceTest`. The pure class is `SeasonFormat`; the stored record is `HistoricalResult.Format`, built from it in one place. Playoff matchups appear in `weeks[]` (for the week picker) and in `playoffs.rounds[]` (for the bracket); a test asserts the two agree. |
| PR-15 unbalanced schedule | Results note, with numbers computed from the format: "19 weeks over an 11-week cycle: you play eight teams twice and three once." |
| PR-16 small things | The collapsed disclosure's summary line shows the count of excluded stats and data notes ("What counts in 1995-96 · 2 stats not counted · 1 data note"), so a caveat is never invisible. Announcements coalesce if more than one pick is pending. The sticky banner offsets by the app's top-bar height. SC-006's baseline is a checked-in 062-shaped fixture, plus a live comparison on whatever completed games the verification database holds. |

Decisions the review flagged as "changes what the feature is", made here and reported to Allan rather than asked, because each follows from what he already said: opening picks are paced (his complaint was unpaced picks); short seasons shrink to a one-round-robin floor (his clarify answer); the bot-strength figure keeps measuring standings rank (so spec 062's numbers stay comparable); the board goes full width when selected.
