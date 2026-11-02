# Code review: spec 054 (weekly median what-if)

Bug-hunting review of the uncommitted diff in worktree `054-weekly-median-what-if`, 2026-11-02.
Read against spec.md, plan.md ("Amended after review" is the override), data-model.md,
contracts/league-history-median.md, build-notes.md and AGENTS.md's recurring bug classes.

**Run by this review:** `cd web && npx vitest run src/pages/LeagueHistory.test.tsx` (1 file, 38 passed)
and `npx tsc -b` (clean). **Not run:** gradle (DB in use by another process, per instructions), so every
backend claim below is verified by reading, not by running.

Severity: **BUG** = produces a wrong or false result on real data; **RISK** = could mislead or break
under plausible conditions; **NIT** = small.

---

## 1. BUG: the playoff-line check can never pass on a real finished season, so every complete season says "this app's order doesn't match who actually made the playoffs"

- **Where:** `backend/.../engine/MedianWhatIfService.java:157` (call) and `:206-219`
  (`orderAgreesWithTheBracket`); root cause in `backend/.../ingest/LeagueHistoryIngestService.java:216`
  and `ingest/espn/EspnSeason.java:33`.
- **Fact (verified by reading):** `roster_season.final_placement` is only ever written as `1` for the
  champion of a complete season and `null` for every other roster, by both ingests (Sleeper: `seasonComplete
  && championRosterId == rosterId ? 1 : null`; ESPN: "1 for the champion of a COMPLETE season, otherwise
  null"). Nothing else writes the column (grep of `final_placement` in `src/main`: only V5, the repository,
  ChampionRule, ManagerCareerService, the controller). Sleeper's placements 2..N are never stored.
- **Failure:** for any complete season with `playoffTeams >= 2`, `byBracket` holds at most one roster (the
  champion) while `byOrder` holds `playoffTeams` rosters, so `byOrder.equals(byBracket)` is always false and
  the block always comes back `playoffLineWithheld = "ORDER_DISAGREES"`, `playoffTeams = null`, no
  `playoffCross` on any team. On the requested league that is 2024 and 2025, i.e. every season that gets a
  what-if at all. The MADE/MISSED marker (FR-008, US3 scenario 2, SC-007's "would I have made the
  playoffs") is dead code on real data.
- **Worse than a missing feature (lessons class 5/8):** the page then prints
  `PLAYOFF_LINE_WITHHELD.ORDER_DISAGREES` (`web/src/pages/LeagueHistory.tsx:377-378`): "This app's
  regular-season order doesn't match who actually made the playoffs". That is a factual claim the code never
  tested; the order may match exactly. The How-this-works copy (`:743-746` region, "or when this app's order
  doesn't match who actually made the playoffs") repeats it. Also note the plan amendment #4 said "rosters
  with `final_placement ≤ playoffTeams` **or who appear in the winners bracket**"; the implementation dropped
  the bracket half, which is the half that actually has data.
- **Why the tests are green (see #2):** both the unit test and the IT seed full placements 1..4, a shape
  the ingest never produces.
- **Fix:** derive the actual playoff set from the stored winners bracket, which exists:
  `store/LeagueBracketRepository.forLeagues(List.of(leagueId))` (spec 034, `league_bracket_match`). The set
  of every non-null `t1RosterId`/`t2RosterId` in a Sleeper winners bracket is exactly the playoff field
  (byes enter in round 2; the 3rd/5th-place games are among playoff teams only). Pass that set into the pure
  `whatIf(...)` (e.g. `Optional<Set<Integer>> playoffField`) instead of reading `finalPlacement`. Split the
  "can't confirm" case from the "confirmed different" case with a new code (e.g. `BRACKET_UNKNOWN`: "Who made
  the playoffs isn't stored for this season, so no playoff marker is shown"), so the page never asserts a
  disagreement it didn't measure; ESPN seasons (no stored bracket) would land there. Add the new code to the
  Java javadoc, `api.ts`'s `playoffLineWithheld` union and the `PLAYOFF_LINE_WITHHELD` map in the same change.
  Then measure on the requested league, in the verification pass, whether 2024/2025 come back agreeing.
- **Status:** verified by reading (ingest code + check logic). The consequence on the real league is inferred
  (no DB run), but follows mechanically for any playoffTeams ≥ 2.

## 2. BUG (test): the playoff-line tests pass for the wrong reason

- **Where:** `backend/.../engine/MedianWhatIfServiceTest.java:198-213`
  (`aCompleteSeasonWhoseOrderDisagrees...`: placements `5,2,1,4` and `2,5,1,4`), and
  `backend/.../api/LeagueHistoryMedianIT.java:89-94` (seeds `final_placement` `2,3,1,4` for every roster)
  with its assertions at `:158-161` (`MISSED`/`MADE`, `playoffLineWithheld` null, `playoffTeams` 2).
- **Failure:** both fixtures store a placement for every roster. Real ingests store `1` for one roster and
  null for the rest (#1), so these tests prove the check works on data that cannot exist and would stay
  green while production always withholds. The IT's comment "the final order this app's own ordering gives"
  documents the fiction. Only `aCompleteSeasonWithNoStoredBracket...` (`:215-219`) mirrors reality, and it
  asserts the withheld outcome — i.e. the test suite already encodes "real data ⇒ always withheld".
- **Fix:** with #1's fix, seed `league_bracket_match` rows in the IT (champion-only `final_placement`, as the
  ingest writes) and assert `MADE`/`MISSED`; in the unit test, feed a playoff-field set, and add one case with
  realistic standings (champion=1, others null) and no bracket that asserts the new "can't confirm" code, not
  `ORDER_DISAGREES`.
- **Status:** verified by reading.

## 3. RISK: ▲/▼ means two different things in the same table, and the copy says it means "best/worst"

- **Where:** `web/src/pages/LeagueHistory.tsx:382-391` (`PlaceMove`: `▲N` = moved up N places) vs `Mark`
  (`:359-368`: `▲` = best in the league, `▼` = worst) used in the W/L/PF/PA/Record vs all/Vs weekly median
  cells of the same row; How-this-works at `:756`: "In each number column, ▲ marks the best value and ▼ the
  worst".
- **Failure (lessons class 5):** a reader sees `6 → 4 ▲2` in the Place column and reads it by the page's own
  stated rule: "best value". Both glyphs also share the `--up`/`--down` colours. The direction itself is
  correct (`gained = from - to`, ▲ when the place number gets smaller; verified by reading and by the
  vitest `2 → 1 ▲1` case), it's the legend that collides.
- **Fix:** use a different encoding for movement (`+2` / `−2`, or `↑2`/`↓2`), or keep ▲ and amend the legend
  sentence to "In each number column except Place, ▲ marks the best … ; in Place, ▲/▼ is the move".
  The former matches spec US3 scenario 1 ("a move of +2") literally.
- **Status:** verified by reading.

## 4. RISK: drift copy says "by a game" but `driftGames` counts rosters that differ by any amount

- **Where:** `MedianWhatIfService.java:124` (`drift++` on any inequality of the W-L-T triple);
  `LeagueHistory.tsx:561` ("N managers' stored scores differ from Sleeper's record by a game") and
  How-this-works `:739` ("disagree by a game").
- **Failure:** a roster whose stored scores flip two results (or that is an unowned roster, not a "manager")
  is still reported as "by a game". The Sleeper NBA 2025 drift memory is exactly the case where this number
  will be non-zero. Also the field name `driftGames` counts rosters, not games (data-model calls it "rosters
  whose re-counted head-to-head ≠ official record"), which invites the next reader to show it as a game count.
- **Fix:** copy: "N teams' stored scores give a different W-L from Sleeper's record; the median games use the
  stored scores." Optionally rename to `driftRosters` (Java + api.ts + contract together) — or leave the name
  and fix only the copy.
- **Status:** verified by reading.

## 5. NIT: ALREADY_ON note points the wrong way and uses present tense for finished seasons

- **Where:** `LeagueHistory.tsx:444-448`: "This season already plays a median game every week — it's in the
  W-L above." The note renders *above* the table, so the W-L is below it; for a finished season "plays" reads
  oddly. The vitest at the ALREADY_ON case pins the "above" wording.
- **Fix:** "This season played a median game every week, so those games are already in its W and L." (and
  update the test regex).
- **Status:** verified by reading.

## 6. NIT: the what-if group doesn't name its span (FR-006)

- **Where:** `LeagueHistory.tsx:458-463, 481-487`. The two median columns name `(N final wks)`; the
  "If median had been on" group and its three columns don't. Under `OFFICIAL` the spans are provably equal, and
  under `COUNTED_GAMES` the note below the table names it, so this is cosmetic, but FR-006 says "wherever it
  could differ".
- **Fix:** "If median had been on ({span})" in the group header.
- **Status:** verified by reading.

## 7. NIT: web test fixture is internally inconsistent

- **Where:** `web/src/pages/LeagueHistory.test.tsx`, `medTeam`/`medBlock`: `gamesCounted: 3`,
  `regularSeasonWeeks: 3`, `realSource: 'OFFICIAL'`, but `real: 9-5` (14 games). The UI doesn't check, so the
  tests are fine, but a reader copying the fixture would get a block the backend can never emit (OFFICIAL
  requires W+L+T == regularSeasonWeeks). Make `real` 2-1 / withMedian 5-1 etc., or set `regularSeasonWeeks: 14`.
- **Status:** verified by reading.

## 8. NIT: one extra `playoffFormat` query per season

- **Where:** `MedianWhatIfService.forSeason:64` calls `expectedWins.regularSeasonBound(league)`, which
  re-runs `leagues.playoffFormat(...)` that the controller already passed in. Could build the bound from the
  passed `format` (same `>= 2` rule). Cheap; SC-006 not measured either way.
- **Status:** verified by reading.

---

## Checked and found correct

- **`ExpectedWinsService.compute()` behaviour after the extraction:** byte-identical. The old loop body
  (`HEAD:390-404`) moved verbatim into `finalGames(league, bound)`; `compute()` calls it with the same
  `league` and `bound` and the same empty check follows. Verified by reading `git show HEAD:` against the
  working tree.
- **`headToHead`:** same `Double.compare` rule as `actualWins`, ties counted for both sides; unit-tested.
- **Place ordering and signs:** comparator is win share desc, then PF desc, then roster id asc, the same for
  both orders (`:225-233`); `MADE` = in now and out before, `MISSED` the reverse (`:169-172`); the strict
  preference-ordering unit test (`:132-149`) pins the direction. UI `PlaceMove` sign correct.
- **Null unboxing:** `StandingRow.wins/losses/ties/pointsFor` are boxed `Integer`/`Double`; they are only
  unboxed under `useOfficial`, after `officialCoversTheCountedGames` has null-checked all four and checked the
  roster sets are identical (`:122`, `:139`, `:194-195`). `format.get()` at `:149` is safe because `WHAT_IF`
  implies `regularSeasonWeeks != null` which implies a present format.
- **ALREADY_ON branch `median.get()`/`allPlay.get()`:** both maps are built from the same `games` via
  `scoresByWeek`, which adds both sides of every game, so every key of `weeksByRoster` is present; no null.
  No what-if fields leak for ALREADY_ON (unit test `:246-264`, IT `:164-177`), and the UI shows no what-if
  columns for it — no double count.
- **JSON shape:** the controller serialises via `LinkedHashMap`s (`medianBlock`/`wlt`), not `Map.of`, so null
  fields serialise as `null` and stay present; no global `NON_NULL` inclusion in `src/main`. The IT asserts
  `containsKey` on the controller's map (it calls the controller directly, so Jackson itself isn't exercised,
  but nothing in between drops keys).
- **Failure isolation (FR-012):** `forSeason` (including its `playoffFormat` call) is inside the try; a
  `RuntimeException` degrades that season to `UNAVAILABLE` and logs; `medianBlock` can't throw on that value.
  UI treats `UNAVAILABLE` and a missing block as dashes (vitest covers both).
- **`api.ts` vs Java:** `SeasonMedian` (status, weeksCounted, driftGames, regularSeasonWeeks, realSource,
  playoffTeams, playoffLineWithheld, teams) and `SeasonMedianTeam` (rosterId, gamesCounted, median, allPlay,
  real, withMedian, winsGained, actualPlace, whatIfPlace, playoffCross) match the records field for field,
  with correct nullability (`int` → `number`, `Integer`/`WinLossTie`/`String` → `| null`). `WinLossTie`
  matches `ExpectedWinsService.WinLossTie(int,int,int)`.
- **In-progress wording:** the page's `inProgress` (`rankStatus === 'IN_PROGRESS'`) is exactly
  `!league.complete()` (`PowerRankingService.finalRankForSeason:399`), the same flag the backend uses to skip
  the bracket check, so "would be in/out" appears exactly when the check was skipped.
- **Header group:** `colSpan={10}` matches the ten real columns before the three what-if columns.
- **Mobile:** the table is still inside `.table-wrap` (`overflow-x: auto`, `styles.css:1674`), so the extra
  three columns scroll inside it rather than widening the page (inferred, not checked at phone width).

## Summary by severity

- **BUG (2):** #1 the playoff-line check compares against `final_placement`, which the ingests only ever set
  for the champion, so every complete season withholds the MADE/MISSED marker and the page falsely states
  the order "doesn't match who actually made the playoffs"; fix by using the stored winners bracket and a
  separate "can't confirm" code. #2 the unit test and IT seed full 1..N placements that real data never has,
  so they pass for the wrong reason.
- **RISK (2):** #3 ▲/▼ for place movement collides with the page's stated "▲ = best" legend; #4 drift copy
  says "by a game" for a count of rosters differing by any amount.
- **NIT (4):** #5 ALREADY_ON note says "above" for a W-L that's below and uses present tense; #6 what-if group
  header lacks the span; #7 inconsistent web test fixture; #8 duplicate `playoffFormat` query.

---

## Fix pass (after code review), 2026-11-02

All eight findings fixed. Backend run against scratch Postgres on 5439 (`DB_URL=...5439`): MedianWhatIfServiceTest
25/25, LeagueHistoryMedianIT 6/6 (0 skipped), LeagueHistoryCanCommissionIT 3, LeagueHistoryIngestServiceTest 16,
LeagueHistoryContaminationIT 1, ExpectedWinsServiceTest 13, ExpectedWinsServiceBoundTest 3: 0 failures, 0 skipped,
BUILD SUCCESSFUL. Web: `tsc -b` clean, `vitest run` 156 files / 2733 tests passed, `npm run build` OK.
Not measured: the real league's 2024/2025 seasons against a real stored bracket (verification pass still owed).

1. **BUG 1 fixed.** `MedianWhatIfService.forSeason` now reads `LeagueBracketRepository.forLeagues(List.of(league.id()))`
   for a complete season; every non-null t1/t2 roster id is the playoff field, passed to the pure
   `whatIf(..., Optional<Set<Integer>> playoffField)`. Empty = no bracket stored -> new code `BRACKET_UNKNOWN`;
   only a real mismatch -> `ORDER_DISAGREES`. `final_placement` is no longer read. Javadoc, `web/src/api.ts`
   union and `PLAYOFF_LINE_WITHHELD` map (LeagueHistory.tsx) updated, plus data-model.md with an amendment note.
   Covered by `aCompleteSeasonWhoseStoredBracketAgreesWithThisAppsOrderShowsTheMarkers`,
   `...DisagreesWithholdsTheLineAsOrderDisagrees`, `...NoStoredBracketIsBracketUnknownNotADisagreement`
   (MedianWhatIfServiceTest) and the IT's bracket-seeded `theOlderMedianOffSeason...` (MADE/MISSED).
2. **BUG 2 fixed.** Unit fixtures now use champion-only placement (`championOnlyOfficial()`); the IT seeds
   `final_placement` champion-only plus a `league_bracket_match` row. New cases: bracket agrees (unit + IT),
   disagrees (unit + `aStoredBracketThatDisagrees...` IT), no bracket (unit + `aFinishedSeasonWithNoStoredBracketIsBracketUnknown` IT),
   in-progress without a bracket (`aSeasonInProgressShowsTheLineWithNoBracketAtAll` unit + `aSeasonInProgressShowsTheLineWithoutAnyBracket` IT).
3. **RISK 3 fixed.** `PlaceMove` shows `+N` / `−N`; the How-this-works legend says Place is excluded from the
   best/worst glyph rule. Covered by the `2 → 1 +1` / `1 → 2 −1` assertions in LeagueHistory.test.tsx.
4. **RISK 4 fixed.** Copy now reads "N managers' records differ from Sleeper's when re-counted from the stored
   scores" (any amount); How-this-works wording matches. Field name `driftGames` kept (rename not trivial across
   contract/data-model); its api.ts comment now says it counts rosters, not games. Test: `names the span and the drift...`.
5. **NIT 5 fixed.** ALREADY_ON note: "This season played a median game every week, so those games are already in its W and L." Test regex updated.
6. **NIT 6 fixed.** Group header is "If median had been on ({span})"; tests match `(3 final wks)` / `(4 final wks)`.
7. **NIT 7 fixed.** Web fixture is consistent: 3 games each, real 2-1 / 1-2, with-median 5-1 / 1-5; the ties test uses a
   4-game block whose median, real and with-median all sum to 4.
8. **NIT 8 fixed.** `forSeason` builds the `WeekBound` from the passed format (`playoffWeekStart >= 2` -> `through(start-1)`,
   else `ALL_WEEKS`), no second `playoffFormat` query; `LeagueBracketRepository` is injected.
