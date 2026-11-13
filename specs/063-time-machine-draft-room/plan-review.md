# Adversarial plan review: 063 Time Machine draft room and playoffs

**Date**: 2026-11-13 · **Reviewer**: adversarial plan-review pass (read cold, no 063 code exists)

**Inputs read**: `AGENTS.md`; `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/api.md`, `quickstart.md`, `checklists/requirements.md`; backend `historical/SeasonReplay`, `Comparisons`, `HistoricalResult`, `HistoricalGameState`, `HistoricalGameService`, `HistoricalGameRepository`, `HistoricalSport`, `HistoricalWeeks`, `HistoricalPlayerCard`, `HistoricalBoardBuilder` (rank assignment), `config/HistoricalProperties`, `NflHistoricalProperties`, `application.yml` (`historical`, `historical-bots`, `historical-nfl`), `V43`/`V46` migrations, `DraftSlot`, `BasketballRules.isDraftable`, `FootballRules.isDraftable`; tests `SeasonReplayTest`, `SeasonReplayNullDateTest`, `ComparisonsTest`, `HistoricalTestSupport`, `TimeMachineBotStrengthHarnessIT`, `HistoricalRealSeasonIT`, `NflGameFlowIT`, `HistoricalGameMvcIT`; `claude/time-machine-bot-strength.md`; web `pages/HistoricalRoom.tsx` (+ test), `HistoricalDraft.tsx`, `HistoricalOldBackend.test.tsx`, `components/HistoricalResults.tsx`, `HistoricalPlayerList.tsx`, `DraftRoomLayout.tsx`, `TeamStrip.tsx`, `teamNeeds.ts`, `lineup.ts`, `snake.ts`, `historicalCopy.ts`, `useRevealedBoard.ts`, `api.ts` (Historical types), `main.tsx`; `specs/024-draft-room-ux/spec.md` FR-003.

**Checks I ran** (read-only): `git grep` for every reader of `userFinish`, `HistoricalResult`, `standings`/`place`, `historical_game`, and every positional constructor of the two properties records; highest migration per branch; `netstat` and `psql` against `localhost:5433`.

**Not run**: any SQL. `localhost:5433` refuses connections and only 5432 (the unrelated one, untouched) is listening, so research R3 (real `week_count` per season) is still open and the new list SQL in A5 is unexecuted. No Gradle or vitest run.

**VERIFIED** = I read or ran something that shows it. **REASONED** = argument only.

Severity: **BLOCKER** = the plan as written produces a wrong result in normal use. **SHOULD FIX** = a real failure, contradiction or hole to close before tasks. **NIT** = drift, hygiene, wording.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| PR-01 | BLOCKER | Reveal | The last response has an empty `available`, so the planned view shows only the players about to be picked |
| PR-02 | SHOULD FIX | Playoffs / tests | Three ITs pin "every week, every team"; two are not in the plan, and the NFL synthetic season lands in a one-week regular season |
| PR-03 | SHOULD FIX | Playoffs / data | R3 cannot be run today; the spec's short-season list is probably wrong (2020-21) and short seasons end on a partial week |
| PR-04 | SHOULD FIX | Honesty | Spec 062's "won the league" numbers and harness read `userFinish == 1`, which changes meaning |
| PR-05 | SHOULD FIX | Rule twice | `onTheClockSlot` by client arithmetic with reversal 0 is a third hard-coded copy; the page has no such arithmetic today |
| PR-06 | SHOULD FIX | Reveal | The hook's state and timer mechanism are unspecified where the races are |
| PR-07 | SHOULD FIX | Deploy order | Split services: no 062-shaped fixture is planned, and the reverse order shows a contradictory page |
| PR-08 | SHOULD FIX | Result shape | Format 2 with no playoffs has no headline, no list wording and no `finalPlaces` rule |
| PR-09 | SHOULD FIX | Storage | "Byte for byte" and "field order is kept" do not hold through a `jsonb` column |
| PR-10 | SHOULD FIX | Spec | The opening computer picks (before the fan's first turn) still land all at once |
| PR-11 | SHOULD FIX | Queue | FR-006 and FR-031 disagree about queueing mid-reveal; prune-on-every-view can wipe a stored queue |
| PR-12 | SHOULD FIX | Layout | Board as a tab in the middle column gets about 800 px at 1366 wide |
| PR-13 | NIT | Honesty | Two different "points" for the fan on one results page; make the comparison total use the same function as hindsight |
| PR-14 | NIT | Plan drift | Line references, unlisted test files, `SeasonFormat` vs `Format`, playoff matchups stored twice |
| PR-15 | NIT | Playoffs | 19 or 14 weeks over an 11-week cycle is an unbalanced schedule; say so once |
| PR-16 | NIT | Misc | Collapsed disclosure, announcement backlog, SC-006 baseline source, queue keys never removed |

Checked and **OK** (no finding):

- **No code deserialises a stored result into `HistoricalResult`.** The only read is `json.readTree` (`HistoricalGameService.java:419`). Research R2's assumption holds. VERIFIED by grep.
- **Readers of `userFinish` / `finish`**, complete list: `HistoricalGameRepository.java:106` (list SQL), `HistoricalResults.tsx:37`, `HistoricalDraft.tsx:47`, and tests `HistoricalGameMvcIT:311-345`, `NflGameFlowIT:224`, `HistoricalRealSeasonIT:115` (report line), `TimeMachineBotStrengthHarnessIT:108` (PR-04). No other backend class reads `historical_game`.
- **Comparisons leak (plan A3 step 9).** Bust and steal use `PlayerWeeks.seasonValue` (`SeasonReplay.java:84-89`), which sums every week present in `games` and does not read `weekCount` at all, so they stay whole-season whatever the replay does. Hindsight reads `in.weekCount()` (`Comparisons.java:59,172,184`). The user's points-for (`SeasonReplay.java:256`) is the only place the split can leak, as the plan says. See PR-13 for one sharpening.
- **Circle method cut at the boundary.** `pairings` depends only on the week number (`SeasonReplay.java:227,267-280`), so weeks 1..R are the pairings today's replay gives. FR-012 holds. No new random draw is needed.
- **Tiny synthetic unit tests.** `SeasonReplayTest` (2 or 4 teams, 1 to 3 weeks) and `SeasonReplayNullDateTest` (2 teams) all fall in the no-playoffs arm because `teams < 6`. Their expectations survive; only `Input` arity changes. Consequence: the existing byte-identical test never exercises playoffs, so the new one in `SeasonReplayPlayoffsTest` is required, not optional.
- **Pick endpoint vs auto.** `pick` checks turn, on-board and not-taken (`HistoricalGameService.java:186-196`); both paths go through `place` and record `seatType = "USER"` (`:263-264`); there is no auto flag. `isDraftable` is applied only inside `chooseAuto` (`:333`), so a queue pick behaves exactly like a manual Draft click. Auto-finish is valid mid-reveal because the server is always at the fan's turn (`requireUsersTurn`, `:441-447`).
- **`computeTeamNeeds` reuse.** BN/IR are filtered inside it (`teamNeeds.ts:84`, `lineup.ts:103`). `cardToPlayerRef` supplies `adp = rank`, `position` and `positions` (`historicalCopy.ts:126-140`). Two things the panel must do itself: map `'NBA'|'NFL'` to the lowercase `Sport`, and compute the bench as drafted minus seated (the function returns starters only).
- **Rank order.** Ranks are unique and assigned in list order (`HistoricalBoardBuilder.java:161-171`), so sorting by `rank` reproduces the server's order.
- **Spec 024 FR-003** names the live, projection and mock rooms (`specs/024-draft-room-ux/spec.md:165`). Time Machine is not one of them. `HistoricalRoom.test.tsx` asserts no `data-region`, and no CSS rule couples `.room-list` to `.hist-list`.
- **Hard rules.** No migration (V47 is the highest here; none planned). No `Map.of` planned on the new records. Hand-set numbers are labelled. No new route, so no access-control surface. No Sleeper API claim.
- **A5 SQL** `(g.result -> 'format' ->> 'version')::int` is valid on `jsonb` and null-safe. REASONED only: not executed.

## Findings

### PR-01 (BLOCKER, VERIFIED) - the final reveal shows only the players about to be picked

`HistoricalGameService.state` returns `available = []` once the game is not `DRAFTING` (`HistoricalGameService.java:393-396`). The response that carries the last computer picks is already `COMPLETE`. Plan B1 defines `view.available` as "the server's `available` plus the cards of unrevealed picks". On that response this is the empty list plus up to 11 cards, so the Players pane collapses from about 200 rows to exactly the players the computer teams are about to take, in order. It happens in every game except when the fan holds the last pick, and it breaks FR-002 in spirit and the edge case "the last pick of the draft is a computer pick".

**Amendment**: the hook keeps the `available` list of the response before (the pool), and `view.available` is that pool minus the revealed picks. This is correct for every response, needs no re-sort, and does not depend on the new response's `available` at all. Add a test that walks the final batch and asserts the list length is the previous length minus the revealed count at each step.

### PR-02 (SHOULD FIX, VERIFIED) - integration tests the plan does not list will fail, and one synthetic season becomes degenerate

- `HistoricalRealSeasonIT.java:87-90` asserts `weeks.size() == weekCount` and `lineups["6"].size() == weekCount` on a real NBA season. Both become false (22 weeks, and fewer lineups unless the fan reaches the final).
- `NflGameFlowIT.java:205-207, 223, 236` asserts 6 matchups in every week, `games == 12 * WEEKS` and `lineups.size() == WEEKS`. `NflSyntheticSeason.WEEKS = 4` with 12 teams satisfies A1's second arm (`4 - 3 >= 1`), so this season becomes a **one-week** regular season plus three playoff weeks.
- `HistoricalGameMvcIT.java:296-303` uses `HistoricalSyntheticSeason.WEEKS = 3`, which lands in the no-playoffs arm and keeps passing, so the MVC flow never sees a bracket.

The plan lists only `HistoricalGameMvcIT`. These suites skip silently without Postgres, and Postgres is down on this machine right now, so a build can report green without running any of them.

**Amendment**: add both ITs to the file list with their new assertions. Decide the synthetic lengths on purpose (for example 6 weeks for football: 3 regular + 3 playoff) so one IT per sport drives a bracket end to end. State a floor for the shortened arm instead of `>= 1` (see PR-03) and test it in `SeasonFormatTest`.

### PR-03 (SHOULD FIX, REASONED; R3 not run) - the short-season list is from memory and the final of a short season is a partial week

I could not run R3: `psql` to 5433 is refused. From the calendar and `HistoricalWeeks` (Monday-to-Sunday weeks that contain a counted game):

| Season | Dates | Weeks (my arithmetic) | Format under A1 |
|---|---|---|---|
| 1998-99 | Feb 5 to May 5 | 14 | 11 regular + 3 |
| 2011-12 | Dec 25 to Apr 26 | 19 | 16 regular + 3 |
| 2019-20 | Oct 22 to Mar 11 | 21 | 18 regular + 3 |
| **2020-21** | Dec 22 to May 16 | **21** | **18 regular + 3, not in the spec's list** |

These are guesses until the query runs. Two consequences that hold whatever the exact numbers are:

1. The spec's edge case names three seasons. If 2020-21 is short it needs adding, in place, as R3 already promises.
2. In a shortened season the final is the season's last week, which is a partial calendar week (1998-99 ends on a Wednesday, 2019-20 on a Wednesday, 2011-12 on a Thursday). The championship is then decided on three or four days of games. Week 1 of 2011-12 is a single day (Christmas, a Sunday) with 10 of 30 teams playing; that already exists today but now counts toward seeding in a 16-week season.

**Amendment**: start Postgres, import, run R3 and paste the output into `research.md` before tasks. Replace `seasonWeeks - 3 >= 1` with a named floor and say what happens below it. Tell Allan about the partial-week final; whether it is acceptable is his call (alternative: the results line for a shortened season also says the final week was short).

### PR-04 (SHOULD FIX, VERIFIED) - spec 062's measurement silently changes meaning

`TimeMachineBotStrengthHarnessIT.java:108-112` counts `userFinish == 1` as a win and `<= 3` as top 3, and averages it. `application.yml:134-138` and `claude/time-machine-bot-strength.md` state "won 11% of 168 basketball leagues and 9% of 78 football leagues" as the basis for temperature 0.25. Those were standings rank over every week of the season. After this change `userFinish == 1` means "won a three-week playoff", a noisier event, and "average finish" mixes bracket places with standings places. A re-run would print numbers under the same column names that are not comparable with the recorded ones.

**Amendment**: the harness reads the fan's regular-season `standings[].place` for its existing columns and adds a separate champion column. The yml comment and the 062 doc get a dated note that their figures are pre-063 standings rank over all weeks. The temperature is not retuned to make a new number match (hard rule).

### PR-05 (SHOULD FIX, VERIFIED) - a third hard-coded reversal 0, justified by arithmetic the page does not have

Plan B1 derives `onTheClockSlot` "from the same snake arithmetic the page already has for `myPicks`". `HistoricalRoom.tsx` has none: `myPicks` comes from the server (`:156`), and the page passes the literal `reversalRound={0}` to `DraftBoard` and `FormatSummary` (`:199`, `:239`). The server computes the slot from the stored `row.reversalRound()` (`HistoricalGameService.java:397-401`), the value is configuration (`application.yml:115,152`), and `HistoricalGameMvcIT.java:355` already builds a reversal-3 game. `HistoricalGameState` does not carry the value. This is the "optional parameter that encodes a rule" class the repo has shipped three times.

**Amendment**:
- Banner during a reveal: read `draftSlot` from the next unrevealed pick in `state.picks`. When caught up, use `state.onTheClockSlot`. No arithmetic.
- Pick rail (upcoming, not yet made picks) does need the order. Add `reversalRound` to `HistoricalGameState` and `api.ts` in the same change and feed it to `snake.ts` (which already requires the argument), then replace the two existing literals. That contradicts "no endpoint changes" in the Summary; say so. A missing field from an old backend reads as 0 through one named helper, as `sportOf` does for `sport`.

### PR-06 (SHOULD FIX, REASONED) - the reveal hook is unspecified exactly where it can race

The plan gives an API (`begin(prevPickNo)`, a `setTimeout` chain) but not the mechanism. Open points:

- **Atomicity.** If the new state and `revealedThrough` are set in separate updates, one render can show the whole new state before the cut applies. Hold `{state, pool, revealedThrough}` in one reducer and set them together when the response arrives. Test: the first render after a response contains no unrevealed pick.
- **What is revealed at once.** Define it as the leading run of `seatType === 'USER'` picks after the old `revealedThrough`, not as "fromPickNo". That covers back-to-back turns at the snake corner and Auto-pick.
- **Timer.** One effect keyed on `(revealedThrough, target, speed)` that schedules a single timeout and clears it in cleanup. That is safe under `StrictMode` (`web/src/main.tsx:20`), applies a speed change to the next pick, and cannot leave a stale chain after Skip or Auto-finish.
- **INSTANT** must set the target synchronously, not run a zero-delay chain (11 paints, and SC-008).
- **Hidden tab.** Browsers throttle timers to one per second or slower in a background tab, so FAST runs at NORMAL there. Accept it and say so, or SC-001 needs "in a focused tab".
- **Results and header.** `complete`, the page title and the results branch must read the view, not `state`, or the header says "You drafted" while picks are still appearing.
- **A failed Auto-finish mid-reveal** must leave the running reveal alone. "Timers are cleared when a new response arrives" should say "a successful response".

### PR-07 (SHOULD FIX, VERIFIED) - deploy order is not addressed

Web and backend deploy independently and have drifted before. `HistoricalOldBackend.test.tsx:7-12` exists because the web went out first, and it pins the promise that a new web renders an older backend's payload.

- **New web, 062 backend**: list rows have no `formatVersion`/`outcome` keys (undefined, not null) and a result has no `format`. The plan's `isOldFormat` and list wording must use `== null`, the new `api.ts` fields must be optional, and a 062-shaped fixture belongs in `HistoricalOldBackend.test.tsx`. That file is not in the plan's list.
- **New backend, old web** (a cached tab, or backend first): the old page prints "You finished 3rd of 12" from the new `userFinish` above a table titled "Final standings" that now holds regular-season places, where the same fan may be 1st.

**Amendment**: state the order (web first, matching the existing test) and add the fixture test.

### PR-08 (SHOULD FIX, VERIFIED) - format 2 without playoffs is half specified

A1's third arm returns `format.version = 2`, empty `playoffWeeks`, `playoffs = null`, `userOutcome = null`. Plan D builds the headline from `userOutcome` only, and the list page shows "the outcome words" for new games. For this arm neither has words, and `finalPlaces` is undefined (data-model says "all teams, ordered by place" with an `outcome` each). A1 says no real season reaches the arm, which rests on R3.

**Amendment**: give the arm its own outcome value (for example `NO_PLAYOFFS`), `finalPlaces` equal to standings order, a headline ("You finished 4th of 12. This season was too short for playoffs.") and list wording. One web test with that shape.

### PR-09 (SHOULD FIX, VERIFIED) - `jsonb` does not keep bytes or key order

`historical_game.result` is `jsonb` (`V43__historical_draft_game.sql:108`), written with `?::jsonb` and read back as `result::text`, then parsed and re-serialised by Jackson (`HistoricalGameRepository.java:158-161,166-168`; `HistoricalGameService.java:416-423`). Postgres reorders keys and normalises spacing; the repo already records this (`HistoricalPlayerCard.java:45`, "jsonb reorders keys"). So A4's "appended after the existing fields so old field order is kept" has no effect on stored rows, and A6's IT "returns it byte for byte" fails unless the fixture is already in jsonb's order.

**Amendment**: A6 compares JSON trees (`JsonNode.equals`), with the fixture captured from `result::text`. SC-005 and FR-019 are measured on the replay's serialised output, as `sameSeedGivesByteIdenticalJson` does today. Reword `contracts/api.md` from "byte for byte" to "unchanged".

### PR-10 (SHOULD FIX, REASONED) - the first computer picks are still a single frame

`create` advances the bots to the fan's first turn (`HistoricalGameService.java:147`) and the room then loads with `GET`. B1's rule "on first load `revealedThrough` equals the last made pick" shows those picks at once. From slot 12 that is 11 picks in one frame, at the start of every game, which is the complaint this spec opens with. The spec's Story 1 only covers "after the fan's pick", so the plan follows the spec; the hole is in the spec.

**Amendment (needs Allan)**: either pace the opening batch when the room is entered from "start a game" (router navigation state; a plain load or reload stays instant, FR-007), or say in the spec that the opening picks are not paced.

### PR-11 (SHOULD FIX, VERIFIED for the code facts, REASONED for the failure) - queue rules

- FR-006 says the fan must not "draft, auto-pick or queue a second pick" during a reveal. FR-031 says any available player can be queued. The plan does not say whether the queue toggle works mid-reveal. It should (that is when a fan plans), and FR-006 should drop "queue".
- C5 prunes the stored queue "against `view.available` on every change of `view`". `available` is empty before the state loads and for a `COMPLETE` or `ABANDONED` game (`HistoricalGameService.java:394-396`). A prune that runs in either case writes an empty queue to `localStorage` and breaks FR-034. Prune only while the view is `DRAFTING` and loaded, and prune against "revealed picks", not against "not in the list".
- The Auto-pick label and the pick both read the same pruned queue in the same render. A test: the top queued player is revealed as taken, the label changes to the next name before the button is enabled.
- `bk.tm.queue.<gameId>` is never removed. Delete it when the game completes or is abandoned.

### PR-12 (SHOULD FIX, REASONED) - the board loses about a third of its width on a laptop

C1 puts Board in the middle column at 1280 px and up. At 1366 wide that column is about 1366 - 240 - 280 minus gaps, near 800 px, for a 12-column board that has the full width today. FR-025 says the board "MUST keep its current content". Either the cells truncate or the board scrolls sideways inside the column.

**Amendment**: when Board is selected at desktop width it spans all three columns (rail and roster hidden). Measure at 1366×768 in the verification pass and record the cell width.

### PR-13 (NIT, VERIFIED) - two "points" for the fan on one page

The finish header prints the fan's standings points (`HistoricalResults.tsx:39-43`), which become regular-season only. The comparison panel prints "You" from `hindsight.userPointsFor` (`:81`), which stays whole-season. The plan's note sits under the bracket, away from both numbers. Label each number where it is ("19 weeks", "all 25 weeks of the real season").

Sharpening for A3 step 9: compute the comparison total as `team.season(userRoster, in.weekCount())`, the same function that produces the hindsight total (`Comparisons.java:184`), so `userBeatIt` compares like with like by construction. R6's test stays.

### PR-14 (NIT, VERIFIED) - plan drift

- Line references: `SeasonReplay.java:176-275` is `177-264`; `HistoricalGameService.java:255-285` is `258-285`; `finishDraft` is `344-375`.
- Adding a component to the two properties records breaks positional constructors not in the file list: `HistoricalGameMvcIT.java:328,355`, `NflHistoricalPropertiesTest.java:73`, `NflStatKeysAgreeTest.java:36,40`, `NflverseSourceTest.java:62`. Loud at compile time, but list them.
- A1 names the record `SeasonFormat`; A4 and the data model name it `Format`. One record or two?
- A playoff matchup is stored twice: as a `Matchup` inside a `Week` with `phase = "PLAYOFF"` and as a `PlayoffMatchup` inside `playoffs.rounds`. Add an invariant test that they agree, or store one and derive the other in the web.

### PR-15 (NIT, REASONED) - unbalanced schedule

Twelve teams have an 11-week cycle. Nineteen weeks is one cycle plus 8, so each team meets 8 opponents twice and 3 once; fourteen weeks gives 3 twice and 8 once. Today's 25-week replay was two full cycles plus 3. The draw is seeded and deterministic, so nothing is wrong, but six playoff places now hang on it. One line in the results or the "what counts" disclosure is enough.

### PR-16 (NIT) - smaller points

- **Disclosure (REASONED)**: collapsing "what counts" below the room hides era caveats (a season that does not count steals) before the fan drafts. Keep FR-027, but put the count in the summary ("What counts in 1972-73 · 3 stats not counted").
- **Announcements (REASONED)**: at NORMAL a polite announcement per pick takes longer to speak than the one-second interval. Announce per pick only at SLOW; summarise otherwise.
- **SC-006 (VERIFIED for the environment)**: the quickstart captures baseline finishes from the local database, which is not running and holds no durable data. "Every game completed before the change" is a statement about production. Say where the baseline comes from, or narrow SC-006 to a fixture plus games created on `main` before the change.
- **Sticky banner (REASONED)**: the site now has a top bar; the banner's sticky offset needs it, and the 1366×768 check should include the page header's height.

## What changes what the feature is (for Allan)

1. **PR-10**: should the opening computer picks, before the fan's first turn, be paced too?
2. **PR-03**: which NBA seasons are short (run R3), what the minimum regular season is, and whether a championship decided in a three-day final week is acceptable.
3. **PR-04**: the bot-strength figures behind temperature 0.25 were "top of the standings". Keep measuring that, or measure "champion" from now on?
4. **PR-05**: the pick rail needs the draft order from the server, so one response field (`reversalRound`) is added after all.
5. **PR-12**: board as a tab in the middle column, or full width when selected.
6. **PR-08**: wording for a season too short for any playoffs, if R3 shows one exists.

Everything else is a how-it-is-built amendment.

**Count**: 1 BLOCKER, 11 SHOULD FIX, 4 NIT.
