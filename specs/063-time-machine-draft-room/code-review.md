# Bug-hunting code review: 063 Time Machine draft room and playoffs

**Date**: 2026-11-13 · **Reviewer**: bug-hunting code review pass (not a style review)

**Read**: `AGENTS.md`; `spec.md`, `plan.md` (with the "Amended after review" table), `data-model.md`, `contracts/api.md`, `tasks.md`, `plan-review.md`, `research.md` R3; the whole working-tree diff against `origin/main` 1369668 and every untracked file: backend `SeasonFormat`, `SeasonReplay`, `HistoricalResult`, `HistoricalGameState`, `HistoricalGameService` (`state`, `auto`, `requireDrafting`), `HistoricalGameRepository`, both properties records, `application.yml`, `DraftSlot`; tests `SeasonReplayPlayoffsTest`, `SeasonReplayTest`, `SeasonFormatTest`, `HistoricalGameMvcIT`, `NflGameFlowIT`, `HistoricalRealSeasonIT`, `TimeMachineBotStrengthHarnessIT`, `DraftSlotParityFixtureTest`, both synthetic seasons; web `usePickReveal.ts` (+ test), `revealSpeed.ts`, `useDraftQueue.ts`, `draftSlot.ts`, `historicalCopy.ts`, `api.ts`, `pages/HistoricalRoom.tsx` (+ test), `pages/HistoricalDraft.tsx`, `App.tsx` (route), all eight new or changed `Historical*` components, `teamNeeds.ts`, the appended `styles.css` blocks.

**What I ran** (nothing in the project was changed except this file; the web build wrote the git-ignored `web/dist`, Gradle wrote `backend/build`):

| Run | Result |
|---|---|
| `web`: `npx tsc -b` | exit 0 |
| `web`: `npx vitest run` | 172 files, **2925 passed, 0 failed, 0 skipped** |
| `web`: `npm run build` | built |
| `backend`: targeted `gradlew test` (`*SeasonReplay*`, `*SeasonFormat*`, `*HistoricalGameMvcIT`, `*NflGameFlowIT`, `*Comparisons*`, `*DraftSlotParity*`, `*HistoricalRealSeasonIT`) | 74 tests, 0 failures, **3 skipped** (read from the XML): `HistoricalRealSeasonIT` 2 of 2 and `HistoricalGameMvcIT.postCreatesARealSeasonGameAs201WithTheBotsAdvanced`, all "no real seasons imported". The database-backed ITs ran (Postgres on 5433 was up): `HistoricalGameMvcIT` 14 of 15, `NflGameFlowIT` 8 of 8. |
| `SeasonFormat.of` sweep, real class, 12 teams, weeks 1 to 30, configured 19 and 14 | 60 of 60 consistent (see "Checked and correct") |
| Mutations of a scratch copy of `SeasonReplay`, run against the real test classes | see CR-02 |
| The `GameSummary` SQL expressions on 5433 (read-only; 5432 not touched) | execute; old shape gives NULL (see "Checked and correct") |
| A scratch vitest driving the real `HistoricalRoom` | CR-01 reproduced |

The full backend suite was not run (per the brief: its 13 known failures on this empty database are the same on `main`).

**Caveat that limits everything below**: `historical_season` on 5433 holds **0 rows**. No real NBA or NFL season was replayed under the new format in this review, and none can be on this database. Every playoff result I saw came from the synthetic seasons. See CR-03.

**VERIFIED** = reproduced by running something. **REASONED** = from reading the code only.

Severity: **BLOCKER** = wrong result in normal use that must not ship. **SHOULD FIX** = a real failure, hole or unbacked claim to close before merge. **NIT** = cosmetic, hygiene, latent.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| CR-01 | SHOULD FIX | Reveal / room | Auto-finish pressed during the final reveal sends a request the server refuses; the fan sees "is COMPLETE, not DRAFTING" and the reveal does not end |
| CR-02 | SHOULD FIX | Tests / ranking sign | The standings order now decides seeds, byes and the playoff line, and nothing pins it: an inverted points-for tie-break and "a tie counts as a win" both pass the whole suite |
| CR-03 | SHOULD FIX | Honesty / verification | T001 was not done: which NBA seasons are short is still unmeasured, R3 still says "not yet run", and no real season ran here |
| CR-04 | NIT | CSS | The appended block restyles the existing `.hist-callout`, so the old-format results page changes look; the block's own comment says nothing is restyled |
| CR-05 | NIT | Results | Week picker reads "Week Week 20 · round one" |
| CR-06 | NIT | Results | A playoff game decided by seed shows no winner in "Week by week"; one-decimal scores can show 150.0 v 150.0 with a winner and no tie note |
| CR-07 | NIT | Queue | `bk.tm.queue.<id>` is never removed for a game abandoned from the list page; two tabs overwrite each other |
| CR-08 | NIT | Accessibility | Star double-encodes its state; "The draft is complete." is never spoken; one polite message per second at Normal |
| CR-09 | NIT | Room | Switching Players → Queue → Players throws away the search text, filter and paging |
| CR-10 | NIT | Drift | `api.ts` comment on `finish` is stale; bracket score column is 8 px off between winner and loser rows |

No BLOCKER. 3 SHOULD FIX, 7 NIT.

## Findings

### CR-01 (SHOULD FIX, VERIFIED) - Auto-finish during the final reveal is refused by the server

**Where**: `web/src/pages/HistoricalRoom.tsx:179-195` (`run`), `:329` (`onAutoFinish`); `web/src/components/HistoricalClockBanner.tsx:65-70`; server `HistoricalGameService.java:213-218` (`auto` → `requireUsersTurn`) and `:435-439` (`requireDrafting`).

**What happens**: the response to the fan's last pick is already `COMPLETE` and carries the computer picks that end the draft. The view correctly keeps `status = 'DRAFTING'` until they are revealed, so the banner still offers "Auto-finish the draft", enabled. Pressing it calls `historicalAuto(id, 'FINISH')` on a game the server has finished. `requireDrafting` throws `IllegalStateException`, which the API returns as 409 (`HistoricalGameMvcIT.conflictsAre409AndABadPlayerIs400` pins that mapping). The room shows the error and the reveal carries on at its own pace. The spec's edge case says the opposite: "The fan presses Auto-finish during a reveal: the reveal ends and the draft finishes".

This is the last thing that happens in almost every game: the final pick belongs to the fan in only one slot (slot 1 with 14 rounds, slot 12 with 15), so every other seat ends on a reveal of 1 to 11 picks, up to 11 seconds at Normal. Auto-finish is the primary button and reads as "get me to the results". No data is harmed and the results still appear when the reveal ends.

**Reproduced**: a scratch vitest rendering the real `HistoricalRoom` (2 teams × 2 rounds, fan in slot 2, speed Slow, `historicalAuto` rejecting with the server's message). Output:

```
final reveal running; Skip enabled = true ; Auto-finish enabled = true
banner = Team 1 is on the clock · No picks left for you · ...
historicalAuto calls = [[5,"FINISH"]]
error shown = historical game 5 is COMPLETE, not DRAFTING
still in the room (not results) = true
after the reveal ends, results shown = true
```

The server half (409 for a COMPLETE game) is REASONED from the code and the existing IT, not driven here.

**Fix**: the room has to know the server's state for this one decision, without rendering from it. Expose it from the hook, e.g. `serverDrafting: s.state?.status === 'DRAFTING'` in `PickReveal`, and in `HistoricalRoom`:

```ts
const autoFinish = () => {
  if (!reveal.serverDrafting) { reveal.skip(); return }   // nothing left for the server to do
  void run('FINISH', () => historicalAuto(state.id, 'FINISH'))
}
```

Optionally relabel the button "Show the results" in that state. Do not read `reveal.target >= teams * rounds` in the page: that is a second copy of "is the draft over".

**Regression test**: in `HistoricalRoom.test.tsx`, a game whose last pick is the computer's; after the fan's last Draft click and before the timers run, click Auto-finish; assert `historicalAuto` was not called, no `.error` is in the document, and the results region is shown.

### CR-02 (SHOULD FIX, VERIFIED) - nothing pins the standings order that now decides the bracket

**Where**: `SeasonReplay.java:247-250` (the comparator); `SeasonReplayPlayoffsTest.java` (every assertion is relative to `standings[].place`); `SeasonReplayTest.java` (no ordering test).

**Why it matters now**: before this feature the standings order was only the fan's finish. It now sets seeds 1 to 6, the two byes and who misses the playoffs. With 12 teams over 19 or 14 weeks, equal records at the 2/3 and 6/7 boundaries are routine, so the points-for tie-break decides real outcomes. This is bug class 1 in `AGENTS.md`: a ranking that can be well-formed and pointed backwards.

**What I ran**: a scratch copy of `SeasonReplay.java` with one line changed, compiled ahead of the real classes on the test classpath, then every `@Test` of `SeasonReplayPlayoffsTest`, `SeasonReplayTest`, `ComparisonsTest` and `SeasonReplayNullDateTest` (39 tests; unmutated baseline 39 pass).

| Mutation | Result |
|---|---|
| Points-for tie-break ascending (fewer points ranks higher) | **39 pass, 0 fail. Survives.** |
| A tie counts as a full win (`wins + 1.0 * ties`) | **39 pass, 0 fail. Survives.** |
| Wins ascending (worst record is seed 1) | 38 pass, 1 fail, and the one failure is `SeasonReplayTest.theLineupObeysTheNbaRosterEligibility`, a two-team lineup test that happens to read the finish. **All 18 playoff tests pass with the worst team seeded first.** |
| Level playoff score goes to the worse seed | killed (2 tests) |
| Reseeding swapped (seed 1 plays the better survivor) | killed (4 tests) |
| Lower score wins a playoff game | killed (8 tests) |

So the bracket itself is well pinned (who advances, reseeding, the tie rule, final places by seed, lineups only for weeks played, whole-season comparison total). The input to the bracket is not. The code is correct today as far as I can read it; this is a test gap, not a wrong number.

**Fix** (tests only): in `SeasonReplayPlayoffsTest`, where slot `s` scores `200 - 10 * s` every regular week, assert the standings are slots 1 to 12 in order, so seed 1 is the best team. Add two small explicit tests: two teams with the same record and different points for (the one with more points ranks higher), and a team at 1-0-1 against a team at 2-0-0 and one at 1-1-0 (the tie is worth exactly half). Re-run the two surviving mutations to confirm they die.

### CR-03 (SHOULD FIX, VERIFIED for the facts) - T001 was skipped, so FR-011's short-season list is still a guess and no real season ran

**Where**: `specs/063-time-machine-draft-room/research.md` R3 ("**Not yet run**"); `spec.md` FR-011 and the first edge case ("unmeasured"); `tasks.md` T001.

**What I found**: `select sport, count(*) from historical_season` on 5433 returns no rows. `HistoricalRealSeasonIT` (2 tests) and the real-season MVC test skip. T001 says to run the week-count query against a database that holds the import, or, if none is reachable, "write that in R3 in those words". Neither happened: R3 still reads as a task to do, not as a recorded "no database with the import was reachable".

**Consequences**:
- Which NBA seasons take the shortened arm (plan-review PR-03 guessed 1998-99, 2011-12, 2019-20, 2020-21) is unmeasured. Whether any real season has fewer than 14 weeks, and therefore finishes with no playoffs at all, rests on the same guess.
- The real-data path (real `week_count`, real `weekStarts`, 168 or 180 picks) has not executed under the new format anywhere I can see. The synthetic ITs cover the shape, not the data.
- `HistoricalRealSeasonIT`'s new assertions have never run.

**Fix**: before live verification, point a backend at a database that holds the import (the scratch database used for spec 053/056, or prod read-only), run `select sport, season_start, label, week_count from historical_season order by 1, 2`, paste the output into R3, correct the spec's list in place with a dated note, and run `HistoricalRealSeasonIT` there and read its skip count. If that cannot be done, R3 and this file should say so in plain words and verification step 6 (a short NBA season) stays open.

### CR-04 (NIT, VERIFIED by reading both files) - the appended CSS restyles an existing class

**Where**: `web/src/styles.css:7046-7048` against the existing rule at `:6914`.

`.hist-callout { border-left: 4px solid var(--line); }` is unscoped, and `Callouts` now always adds `hist-callout-bust` / `hist-callout-steal`. The old-format results page therefore gains a red and a brand-coloured left bar on its two callouts. Plan D promises "today's page exactly, plus one line" for an old result, and the block's header comment says "nothing above is restyled". This is the only collision: I checked every other class the appended blocks define against `main`'s stylesheet and found no other pre-existing selector (`.hist-finish-title.champion` only adds a modifier). The diff removes no CSS line.

**Fix**: scope the three rules to `.hist-moments .hist-callout…`, or accept the change and correct the comment.

### CR-05 (NIT, REASONED) - "Week Week 20 · round one"

**Where**: `HistoricalResults.tsx:87-92` and `:209-213`. The visible label is `Week ` followed by a select whose format-2 options now start with "Week". The old-format options are still the bare number, so the old page is unchanged. **Fix**: drop the "Week " prefix from the option text, or render the label text only when `old`.

### CR-06 (NIT, REASONED) - a seed-decided game has no winner in the week list, and one decimal can hide a real margin

**Where**: `HistoricalResults.tsx:231-233`; `HistoricalBracket.tsx:44`.

- In "Week by week" a playoff matchup marks its winner with `homePoints > awayPoints`. A game level to the hundredth marks neither side, although the bracket says the better seed advanced. For a `PLAYOFF` week, take the winner from `roundOfWeek.get(week).matchups` (`winnerSlot`) and repeat the tie note.
- Winners are decided on hundredths but both views print one decimal (`pts1`). 150.04 v 150.01 prints "150.0" twice with "Advances" on one side and, correctly, no tie note. It looks like an unexplained tie. Print two decimals when the two one-decimal strings are equal.

### CR-07 (NIT, REASONED) - queue storage loose ends

**Where**: `useDraftQueue.ts:95-101`; `HistoricalDraft.tsx:111-114`.

- The key is deleted only when the room sees the game `COMPLETE` or `ABANDONED`. Abandon is a button on the list page, and an abandoned game's room is rarely opened again, so the key stays for good. Export a `clearDraftQueue(gameId)` and call it after `abandonHistoricalGame` succeeds.
- Two tabs on the same game do not see each other's queue (no `storage` listener); the last writer wins. Each tab stays internally consistent and the server rejects a stale pick, so nothing wrong is shown. Recorded, not worth code.

### CR-08 (NIT, REASONED) - accessibility

- `HistoricalPlayerList.tsx:126-134`: the star has `aria-pressed` and a label that flips to "Remove X from your queue". A screen reader says "Remove X from your queue, pressed", which states the same thing twice in opposite directions. Keep one: a fixed label ("Queue X") with `aria-pressed`, or the flipping label without it.
- `HistoricalRoom.tsx:154`: " The draft is complete." is put into the live region in the same commit that replaces the room with the results page, where the region is not rendered. It is never spoken. Either keep the region mounted on the results branch or drop the phrase.
- At Normal the region gets a new sentence every second (`:155-157`). Plan-review PR-16 suggested per-pick speech at Slow only; the plan kept Slow and Normal. A polite queue will fall behind an 11-pick run. Worth a listen with a real screen reader in the verification pass; I did not run one.

Checked and fine: every new control is a real `<button>`, Skip is a disabled button rather than a missing one, the tabs and speed control carry `aria-pressed`, the live region is `role="status"` and polite, Fast and Instant speak one summary, and focus is not moved by a reveal.

### CR-09 (NIT, REASONED) - the Players pane forgets its state

**Where**: `HistoricalRoomLayout.tsx:47, 72-83`. Panes that are not showing are not rendered (by design), so `HistoricalPlayerList`'s search text, position filter and "show more" count reset whenever the fan looks at Queue, Board, Picks or Roster and comes back. On a phone that is every glance at the roster. **Fix**: lift those three values into `HistoricalRoom`, or keep the players pane mounted with `hidden`.

### CR-10 (NIT, REASONED) - drift

- `web/src/api.ts:4726`: `finish` is still documented as "Your place in the final standings". Since this change it is the final place after the playoffs for a format-2 game and the standings rank otherwise.
- `styles.css:7076`: `.hist-bk-side` has four columns; a losing side renders three children, so its score sits in column 3 and ends one 8 px gap left of the winner's score. Look at it in the browser pass.

## Checked and found correct

**Backend playoffs**
- **Regular-season boundary**: round robin, W-L-T and points for run over `1..regularWeeks` only; playoff-week scores cannot move the standings (pinned by `standingsCountTheRegularSeasonOnlyAndIgnorePlayoffScores`). Pairings for weeks 1..R are unchanged by season length (pinned).
- **`points[][]` beyond `lastPlayed`**: the zeros are never read. Standings read regular weeks, the bracket reads the three playoff weeks, and the comparison total is computed separately by `team.season(roster, in.weekCount())`.
- **Bracket**: 3 v 6 and 4 v 5, byes for seeds 1 and 2, reseeding (seed 1 against the higher-numbered survivor), the final with the better seed as `high`, level score to the better seed with `decidedBySeed`, final places a permutation with semifinal and round-one losers ordered by seed, `userFinish` equal to the user's final place, lineups only for playoff weeks the user played, nothing after the final. Each is pinned and each killed its mutation (CR-02 table).
- **Comparisons stay whole-season**: pinned by `theHindsightComparisonStillCoversTheWholeSeasonNotTheRegularSeason`.
- **Determinism**: no new random draw; the new lists are built in fixed order; the `HashMap`s (`rosters`, `players`, `PlayerWeeks`) are not serialised. Two runs serialise to identical bytes (pinned, with playoffs).
- **`SeasonFormat` sweep** (real class, 12 teams): weeks 1-13 no playoffs; 14-21 (NBA) and 14-16 (NFL) shortened to `weeks - 3`, never below 11; 22+ → 19 + [20, 21, 22]; 17+ → 14 + [15, 16, 17]; regular + playoff + unused equals the season in all 60 cases; `shortened` is true exactly when playoffs exist and the regular season is below the configured length. One thing to know, by design: 13 weeks plays all 13 with no playoffs, 14 weeks plays 11 plus playoffs.
- **`GameSummary` SQL** (run on 5433): an old-shaped result gives `format_version` NULL and `outcome` NULL; explicit JSON nulls give NULL; a NULL `result` gives NULL for all three; a format-2 result gives 2 and the outcome. The full statement plans against the real tables. The MVC IT exercises the real code path for all three shapes and passed.
- **Jackson**: no `non_null` inclusion is configured, so the replay emits `"playoffs": null` for a no-playoffs season, and an old stored result simply lacks the keys. The web reads all of them through `== null` / `??`, so both forms work. `SeasonFormat`'s helper methods are not serialised because the stored record is the separate `HistoricalResult.Format`.
- **Hard rules**: no migration (V47 is still the highest); no `Map.of` on the new JSON path; both regular-season lengths and all four reveal intervals are labelled hand-set where defined; temperature 0.25 untouched and the yml note says the 11% / 9% are pre-063 standings-rank wins; the harness reads `standings[].place`.

**`api.ts` against the Java records**, field for field: `HistoricalResult` (+4), `Week.phase`, `Format` (7), `Playoffs`, `Seed`, `Round`, `PlayoffMatchup` (8), `FinalPlace`, `HistoricalGameState.reversalRound`, `GameSummary.formatVersion` / `outcome`. Names, nesting and nullability agree; every new field is optional on the web. The two 062 fixtures are byte-identical.

**Reveal hook**
- `LOADED`, `RESPONSE`, `TICK`, `SKIP` read correctly; state, pool and cut move in one reducer step, so no render shows an uncut response. The pool survives the completing response (`available = []`). A response mid-reveal keeps the cut and adds nothing early. `INSTANT` schedules no timer. One timer at a time, cleared on unmount and on a speed change; safe under StrictMode. The full-draft test walks all 154 paced ticks of a 12 × 14 draft and checks the invariant at each.
- **Nothing in the room reads the raw server state.** `HistoricalRoom` aliases `reveal.view` as `state` and every consumer (header, results switch, board, list, queue, rail, roster, banner, announcements) takes it from there. CR-01 is the one place that needs the server's state and does not have it.
- The route is keyed by game id (`App.tsx:76-78`), so a different game remounts the room: no cross-game queue pruning and no stale fetch landing in another game.
- Dev only, harmless: StrictMode's double effect fetches twice and dispatches `LOADED` twice, which can restart an opening replay a few milliseconds in.

**Queue**: pruned only for a loaded `DRAFTING` view; storage failures fall back to memory; corrupt values read as empty. **Auto-pick label and action agree**: both come from `queuedCards[0]` of the same render, which is already filtered against the view, and Auto-pick only exists on the fan's turn with no reveal running, so no reveal or prune can come between render and click. The in-flight ref still guards a double click.

**Draft order**: `slotForPick` mirrors `DraftSlot.slot` and reuses `snake.ts`'s `isForward`; the parity fixture test ran on both sides (backend 2 of 2, web not skipped). `reversalRound` is required, not defaulted, in the helper; the room passes `state.reversalRound ?? 0` for an old backend only.

**Roster panel**: `computeTeamNeeds` gets the lower-cased sport, the game's `rosterPositions` and the fan's revealed picks; NBA goes through the matroid lineup, NFL through the two-pass rule; bench is drafted minus seated by id, so no player is shown twice or dropped.

**Results**: old-format detection is the single `isOldFormat`; `NO_PLAYOFFS` has its headline, its line and its list wording; the shortened line and the "Weeks 23 to 25" line compute from `format`; the schedule note gives "eight teams twice and three once" for 19 and "three teams twice and eight once" for 14; bye, eliminated and missed-playoffs wording follow the right conditions; the old-backend tests cover a 062 result and summary row.

**Shared components**: `DraftRoomLayout`, `DraftBoard`, `TurnIndicator`, `RoomControls`, `useRevealedBoard`, `snake.ts`, `teamNeeds.ts` are unmodified. `HistoricalPlayerList` renders as before when the queue props are absent, and the room is its only caller.

## Not verified here (for the live pass)

- Any real season (CR-03).
- Everything visual: the sticky banner's offset under the top bar, 1366×768 and 375 px, the rail's scroll-into-view, the board at full width.
- A real screen reader.
- The server half of CR-01 against a running backend.

## To the parent session

**0 BLOCKER, 3 SHOULD FIX, 7 NIT.**

Two findings are lessons, both sharpenings of classes already in `claude/lessons.md` rather than new ones:

1. **CR-01: a view that deliberately lags the server must not decide which actions are legal.** The reveal hides the server's state from every renderer, which is right, and the same rule then let a button fire a request the server had already made impossible. Any future "presentation lags truth" feature needs each action checked against the truth, not the view.
2. **CR-02: a test written relative to the code's own ordering does not pin that ordering.** Every playoff test reads seeds from `standings[].place`, so the standings comparator could point either way. This is lesson 1 (preference order, not structure) with a new disguise: the suite has ordering tests, and they are all downstream of the unpinned order. Cheap to detect with one mutation.

## Fix pass (2026-11-13, after this review)

CR-01, CR-02 and CR-04 to CR-10 were fixed by a Sonnet builder and the diff read by the parent session. CR-02's new
ordering tests (`SeasonReplayStandingsOrderTest`) were shown to fail under each of three comparator mutations.
CR-03 was closed by running the week-count query against a fresh import (research R3) and by a live sweep of 95
real games; see [verification.md](verification.md). Not addressed: the announcement rate at Normal speed under a
real screen reader (CR-08, needs a screen reader), and the accepted two-tab queue overwrite (CR-07).
