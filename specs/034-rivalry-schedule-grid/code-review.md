# Code review: spec 034

**Date:** 2026-10-14
**Stage:** bug-hunting code review, run cold against the uncommitted tree in worktree
`.claude/worktrees/034-rivalry-schedule-grid` (cut from 8346cdf). This is not a style pass.

**What I reviewed:** `git diff` (DraftSimApplication, IngestController, LeagueHistoryController,
HeadToHeadService, LeagueHistoryIngestService, LeagueMemberRepository, the three test edits,
weights.yml, App.tsx, api.ts, destinations.ts(+test), LeagueRailSection.test.tsx, styles.css) and
every untracked file: V33, LeagueBracketRepository, BracketIngestService, RivalryGameKind,
RivalryMatching, RivalryService, RivalryProperties, RivalryController, rivalries.ts,
pages/Rivalries.tsx. I read them against plan.md's "Amended after review" (authoritative), spec.md,
data-model.md, contracts/api.md and research.md.

**What I ran:** only the scratch DB on 5434, read-only GETs to `api.sleeper.app`, and the suites
below. I wrote no throwaway test and edited no source file.

- `DB_URL=jdbc:postgresql://localhost:5434/draftsim ./gradlew test --tests '*Rivalry*' --tests '*Bracket*' --tests '*HeadToHead*' --tests '*IngestControllerTest' --tests '*LeagueHistoryIngestServiceTest' --tests '*ChampionOnlyWhenCompleteTest'`:
  12 result files, **0 failures, 0 errors, 0 skipped**. The ITs really ran; this was not the
  silent-skip case.
- `npx vitest run src/pages/Rivalries.test.tsx src/destinations.test.ts src/components/LeagueRailSection.test.tsx`:
  92/92 passed. `npx tsc -b` came back clean.

Severity: **bug-high** means wrong answers or data loss on real data. **bug** means wrong output in
a reachable case. **risk** means correct today but fragile, or honest but confusing. **nit** is minor.

## Findings

### B1. bug, inferred from code: a bracket stored before it finished is frozen for good once the season is `loaded_complete`

`BracketIngestService.java:96-99` (`ingestIfMissing`) and `LeagueHistoryIngestService.java:102-109`.

On the skipped-season path, the bracket is fetched only when `brackets.count(leagueId) == 0`. The
chain walk stores the bracket on every non-skipped pass, so an in-season league always has rows,
whether provisional or partly played. `LeagueRefreshService.refreshChain` (around line 229) marks a
season `loaded_complete` from `RefreshDecision.completeAfter(statusSeen, true, perGameAllFinal)`.
Bracket completeness is never part of that decision.

Scenario: the last non-skipped pass runs while Sleeper's league `status` already reads `complete`
but the bracket's final `w` hasn't been written yet, or a placement row still has a null slot. The
season freezes. From then on, every pass sees rows, so `ingestIfMissing` is a no-op. Results:

- `finals[].winner` stays null for good.
- The reason reads "not decided yet".
- If a slot was still null, the final week stays `BRACKET_PENDING`/unsplit.

C2 with `force=false` would repair it, because the bracket isn't `isComplete`. The automatic path
never does. This contradicts the F6 claim that "a season never freezes as loaded_complete without
[a bracket]": it can freeze with an unfinished one.

**Fix:** in `ingestIfMissing`, use `if (brackets.isComplete(leagueId)) return;` in place of
`count > 0`. That is still one call per pass at most, and none once the bracket is final. A
belt-and-braces option is to make `completeAfter` also require `isComplete` for a league with
`playoff_week_start >= 2`. Add a test: a skipped season with an incomplete stored bracket gets
exactly one `winners_bracket` call.

### B2. risk, measured: in NBA finals the matrix and `finals[]` name different winners for the same game. This is not a round→week mapping bug.

`RivalryService.java:298-344`: `byKind`, totals, streak and grid results use stored scores, while
`Final.winner` uses the bracket's `w`.

The builder's observation holds, and I measured why it happens. **The round→week mapping is
right.**

1. **Mapping.** Mapping NBA 2025 rounds to weeks as `19 + r − 1` (round type 0) and checking every
   bracket row against stored `roster_week_points`: **14 of 16** bracket matches across NBA 2025
   and 2024 agree with the stored scores. That includes the 3rd-place game, which is played in the
   *same* week as the final:
   - 2025 wk21: 11 300.5 v 3 246.5, `w=11`.
   - 2024 wk24: 5 241 v 12 223.5, `w=5`.

   Only the two finals disagree:

   | Season | Week | Stored scores | Bracket `w` |
   |---|---|---|---|
   | NBA 2025 | 21 | roster 7 234.0 v roster 12 199.0 | 12 |
   | NBA 2024 | 24 | roster 6 248.0 v roster 2 215.0 | 2 |

2. **Live data.** Sleeper's live `/matchups/21` (2025) gives 12 269.0 v 7 251.5. Live
   `/matchups/24` (2024) gives 2 288.5 v 6 280.0. Both agree with the bracket.
3. **Starters.** Stored and live for the 2025 final have **identical starters lists**, but they
   credit different single games per player. For example, roster 12's player 2722 is stored as
   23.0 and live as 41.0. That is the one-game-per-week credit mechanism memory
   `project_nba_one_game_per_week_is_correct` describes, and the mechanism is still undiagnosed.
4. **No later week.** No later week is stored: the max week is 21 / 24, the same as
   `last_scored_leg`, and Sleeper's week 22 / 25 has every `matchup_id` null. So the final is not
   spanning a second stored week.

Memory says stored NBA scores are right for **weeks 1–18** (checked against `fpts`, which counts
the regular season only). Playoff-week stored scores were never checked against anything official,
and the bracket is the only official record for them. Which side is right for the finals is not
settled here. *Inferred:* the bracket is Sleeper's at-the-time verdict, so it is more likely
correct.

**Effect in the payload.** For the 2025 final pair (managers of rosters 7 and 12):
`byKind.PLAYOFF` credits roster 7's manager with the win. The matrix record and the season-grid
cell for week 21 (`result.outcome`) also say W for roster 7. Meanwhile `finals[0].winner` and the
reason say roster 12's manager won the title game. The reason text handles this honestly (lines
621-626 print both). The matrix tooltip and the season grid don't mention it.

**Fix:** no engine change to the counting. Totals must stay on stored scores for parity with the
versus page (F3), and changing them would hide the disagreement. Instead:

- Surface `scoresAgreeWithWinner=false` beside the result (a flag on `Final`, and a note in the
  matrix cell title or the week-21 grid cell).
- Record the 2-of-2 finals measurement in HANDOFF as an open data question. Stored NBA
  playoff-week points disagree with the bracket in the final only.
- Don't re-ingest. Per memory, live `/matchups` for old seasons is degraded elsewhere.

### B3. bug, measured data / inferred render: reason sentences and the "sits out" line use Sleeper display names, while every card on the page shows team names

`RivalryService.java:213-217` (`nameOf` = `StandingRow.managerName` = `manager.display_name`), used
at `:573-574` and throughout `reason()`. The UI meanwhile uses `teamName`
(`Rivalries.tsx:36-48, 272-287`).

Scratch DB, league 211:

| Display name | Team name |
|---|---|
| `popsharky` | `the Sheppard said Amen` |
| `wags707` | `(PAO)nd town` |

So a pairing card reads "the Sheppard said Amen vs (PAO)nd town — Met in the 2025 final: popsharky
beat wags707 …". The unpaired line reads "**Kawhi am i winning?** sits out. Leaving jpelwell out
gives the highest total …". Those are two names for one person in one sentence. A reader can't
match the reason to the card without knowing both.

**Fix:** build `nameOf` from the same value `Row.teamName` carries (`teamNames.get(id)`, falling
back to the display name), so one name is used per manager across payload and UI. Update the
service test's expected strings.

### B4. risk, inferred: "The most meetings of any pair in this league" is computed over current pairs only

`RivalryService.java:552-557, 642-644`. `maxMeetings` and `atMax` iterate `calc`, which holds only
current-manager pairs. A pair involving a former manager can have more meetings, and then the
sentence is false as worded. R7 said "among current pairs". The text dropped the qualifier.

**Fix:** reword to "The most meetings of any two current managers: …". Alternatively, compute the
max over `byPair` (all pairs).

### B5. nit, inferred: grid results are re-rounded to one decimal, so a win can print as a tie

`Rivalries.tsx:176` calls `toFixed(1)` on a payload that already carries 2-decimal points
(`round2`). For example, NFL 120.24 v 120.16 prints "W 120.2–120.2". That is the page reformatting
a number it was given, against the "print the payload's number" rule.

**Fix:** use `toFixed(2)`, which matches the reason text's `%.2f`.

### B6. nit, inferred: an ownerless roster in the current season is called "a former manager"

`Rivalries.tsx:37`. `opponentManagerId` is null when the opponent roster has no manager
(`RivalryService.java:500`). That's an orphan seat *this* season, not someone who left.

**Fix:** use "an unowned team", or have the payload carry the roster's team name.

### B7. nit, inferred: a skipped season whose bracket is legitimately empty is re-fetched on every pass

`BracketIngestService.java:96-99`. The javadoc says "one call per season, once". But if Sleeper
answers `[]` (a finished league without playoffs, or `playoff_teams` 0), `count` stays 0. Every
refresh then makes one `winners_bracket` call forever. It's cheap, but the comment is wrong.

**Fix:** skip when `playoffFormat(leagueId).playoffWeekStart() < 2`. Otherwise, correct the comment.
With B1's fix the same applies.

### B8. nit, inferred: a manager with two rosters in one season gets only one season-grid row

`RivalryService.java:165-166` uses `putIfAbsent` for `rosterOfManager`. The schema allows it
(the service javadoc says so). Their second roster's games still count in the matrix, but its
schedule row is never shown. It's rare, so document it or key grid rows by roster.

### B9. nit, inferred: PLAYOFF_HISTORY's "A won x, B won y" doesn't add up to the meeting count while a match is undecided

`RivalryService.java:332-334, 637-638`. A bracket match with `w` null counts toward `k` but toward
neither side, so a live playoff season can print "Have met 2 times in the playoffs: A won 1, B
won 0."

**Fix:** append ", 1 not decided yet" when `k > aWins + bWins`.

### B10. nit, inferred: NOT_YET_MET doesn't check that the scheduled week is in the future

`RivalryService.java:204-209, 360`. `scheduledWeek` is the minimum scheduled week of the
requested season, including weeks already past. A pair scheduled in a past week whose game never
got scored (postponed, or a score missing) reads "the schedule has them meeting in week 3" when
week 3 is over. F11 says "a later week".

**Fix:** only count weeks `> currentWeek` (or weeks with no stored score yet).

## Checked and found correct

- **V33**: the next free number (V32 belongs to 033). Types match the data-model. PK is
  `(league_id, match_id)`. FK is `on delete cascade`. Nullable slots are nullable. It is
  append-only.
- **LeagueBracketRepository**: an empty or null write is a no-op (F5). Delete+insert runs in one
  `@Transactional` on a Spring bean, called from another bean, so the proxy applies. Nullable
  columns are read with `getObject`, not `getInt`, so nothing becomes 0.
  `isComplete` = rows exist and all have `w`.
- **BracketIngestService mapping**:
  - `t1`/`t2` arrive as ints, or are absent with a `t1_from` map. `intOrNull` maps a non-Number to
    null. Measured against live NFL 2026: `{"t2":null,"t2_from":{"w":1}}` gives t2 = null.
  - A null or empty body never deletes.
  - A completed bracket is skipped unless `force`.
  - Exceptions propagate (no catch), so the chain fails as F6 says.
  - The skipped-season path calls only `winners_bracket` (no `/matchups`, no standings).
  - The non-skipped path calls it after `ingestWeeklyPoints`.
  - C2 walks the stored chain (`chainBySleeperId`, local) and returns 404 on an empty chain. It
    sits under `@RequestMapping("/api/ingest")`, so `AdminGateInterceptor` on `/api/ingest/**`
    covers it.
- **RivalryGameKind**:
  - Type 0 maps `start + r − 1`.
  - Type 1's last round covers two weeks (`min(r, maxRound)` for `r ≤ maxRound+1`).
  - Other types are refused.
  - A null slot anywhere in the round gives `BRACKET_PENDING`.
  - Placement 3/5 gives PLACEMENT, and null/1 gives PLAYOFF.
  - Integer/int comparisons are reached only after null checks, so there is no unboxing NPE.
  - Measured on real brackets: the NFL 2025 and NBA 2024/2025 round-to-week mapping matches the
    stored pairings for all 21 bracket rows (B2 covers the score side).
  - `bracketBye` (holds a slot in r+1 but not in r) matches the real NFL 2026 provisional bracket:
    rosters 10 and 6 get a bye in week 15.
- **RivalryMatching**:
  - The DP is exact.
  - The odd-n sit-out is gated on even popcount, so exactly one sit-out happens and `full` is
    reachable.
  - Ties keep the first found (strictly greater than `+EPS`).
  - `-inf` children are skipped.
  - Ids are sorted and duplicates rejected.
  - `unpairedBestScore` covers both index orders.
  - The 2^20 worst case under `maxManagers ≤ 20` is fine.
- **Matrix**:
  - Every game increments exactly one `byKind` bucket, so `byKind` sums equal the totals.
  - Mirror orientation is normalised through `key.lo()` everywhere.
  - Self-pairs and null/null games are dropped.
  - Null-manager games count in `allOpponents` as former.
  - Status precedence is MET > NOT_YET_MET > NEVER_MET > NO_SHARED_HISTORY, as F11 says.
  - Parity with `HeadToHeadService`: the same `pairedWithScores` one league at a time, and the same
    extracted `outcome()`. The extraction is behavior-identical (the same `>`/`>`/else order).
  - `RivalryParityIT` passes on the scratch DB.
- **Two-week final**: legs are grouped by `(league, match_id)`. The points are summed, the winner
  is the bracket's `w`, `twoWeek` is set, and `playoffMeetings` counts it once (F3).
- **Season grid**:
  - `priorRecord` excludes `(season, week) >=` the cell's own (strictly before).
  - Opponents are symmetric (one `oppByWeek` map is written both ways).
  - Regular weeks use BYE vs TBD, playoff weeks use NO_GAME.
  - A BRACKET opponent appears only when `leg >= playoffWeekStart` and both slots are set (the F7
    provisional gate).
  - `currentWeek` is the URL league's own `leg`, nullable, and never defaulted. There is no
    `LeagueSeasonResolver`.
  - Empty `scheduledPairs` gives NOT_PUBLISHED.
- **Recommendation**:
  - The order is CONFIG_MISSING (partial block included) > TOO_FEW > TOO_MANY > NO_HISTORY.
  - NO_HISTORY = chain size < 2 (F10).
  - MOST_MEETINGS requires a unique max of at least 2.
  - A streak breaks on a tie.
  - Every number the text prints is in `numbers`.
  - Output is deterministic.
- **Jackson / api.ts**:
  - Each type mirrors its record field for field, including nullability (`minMeetings`,
    `minMeetingsLabel`, `source`, `opponentManagerId`, `priorRecord`, `result`, `unpaired`,
    `currentWeek`, the three note strings, `Final.winner`).
  - `byKind` is a `Map<String,…>` keyed by enum name, so it is a plain JSON object.
  - Boolean `isMe`/`isPlayoffWeek` record components follow the same pattern already shipping in
    `MemberRankingService.BallotMember` and `PlayerOwnership.Ownership`.
  - There is no `Map.of` anywhere a value can be null. `reason.numbers` is a `LinkedHashMap`, and
    the controller's C2 body is a `LinkedHashMap`.
- **Controller scoping**: `visibleLeague` runs first, and both "not visible" and "not stored" give
  the same 404.
- **LeagueHistoryController.teamNamesByManager**: the method was moved without changes
  (same filter, same map).
- **LeagueRailSection.test.tsx**: adding `'Rivalries'` to the offered list is correct, since
  `requiresStatus: null` and it's offered to both sports.
- **Phone width**: each table sits in its own `.rv-wrap` (`overflow-x: auto`, `max-width: 100%`),
  with a sticky `.rv-first` column.

## Summary by severity

- **bug-high:** none.
- **bug:**
  - B1: an unfinished bracket freezes for good once a season is `loaded_complete`
    (`ingestIfMissing` checks `count > 0`, not `isComplete`).
  - B3: reason and sit-out sentences use display names while the page shows team names.
- **risk:**
  - B2 (measured): NBA 2025 and 2024 finals have stored scores that contradict the bracket winner,
    so the matrix and grid name one winner and `finals[]` the other. The round→week mapping is
    verified correct (14/16 bracket matches agree; only the two finals differ, with the same
    starters but different credited games).
  - B4: the "most meetings of any pair" sentence covers current pairs only.
- **nit:**
  - B5: `toFixed(1)` can show a win as a tie.
  - B6: an orphan roster is labelled "a former manager".
  - B7: an empty-bracket skipped season is re-fetched on every pass.
  - B8: a manager with two rosters gets one grid row.
  - B9: the playoff-history counts don't add up while a match is undecided.
  - B10: NOT_YET_MET can name a past week.

## Fixes applied

- **B1:** `BracketIngestService.ingestIfMissing` now fetches whenever the stored bracket is not
  `isComplete` (no rows, or any row without a winner), not only when `count == 0`. A complete bracket is a
  no-op. Tested: empty, incomplete (exactly one call, rows replaced) and complete (no call).
- **B2:** counting is unchanged (parity). Added `Final.scoresAgreeWithWinner` (Boolean, null while undecided)
  and `CellResult.bracketDisagrees` on the wire, mirrored in `api.ts`. The matrix cell title says "{season}:
  Sleeper's bracket gives the final to X; the stored scores say otherwise." and shows a "final disputed" tag. The
  season-grid cell shows the same sentence. Tests: service (flag true and false, both sides' grid cells) and
  `Rivalries.test.tsx`. Recording the 2-of-2 NBA finals in HANDOFF is left to the coordinator.
- **B3:** `nameOf` is team name first, falling back to the display name, the same lookup as `Row.teamName`.
  `Row.managerName` still carries the display name. Test: reason text uses the team name.
- **B4:** reworded to "The most meetings of any two current managers: ...". Tested.
- **B5:** the grid prints `toFixed(2)`, so 120.24 vs 120.16 can no longer read as a tie. W/L/T still comes
  from the payload's `outcome`. Tested.
- **B6:** a null opponent is labelled "an unowned roster". Tested.
- **B7:** `ingestIfMissing` skips a league whose `playoff_week_start < 2`. Residual cost, documented in the
  javadoc: a finished season that has playoffs but whose bracket Sleeper answers with `[]` is asked again on
  every pass (one call; no state records the empty answer).
- **B8:** new nullable `SeasonGrid.rosterNote` when a manager owns two rosters; the grid still shows each
  manager's first roster only, and every game still counts in the matrix. Rendered by the page. Tested.
- **B9:** PLAYOFF_HISTORY text appends ", N undecided" when the bracket winners do not add up to the meeting
  count (`numbers.playoffUndecided`). Tested.
- **B10:** `scheduledWeek` only counts scheduled weeks after the last scored week of the requested season, so a
  past week whose game never got a score falls through to NEVER_MET. Tested.

Tests after the fixes: backend 1744 run, 1 failed (the known `BoardRepositoryLatestBeforeIT`), 1 skipped;
web `tsc -b` and `npm run build` clean, vitest 2101/2101.

## Found in live verification

- **EVEN wording:** the live text "An even series after 3 meetings: 2, 1" was false for odd counts. The EVEN
  rule (|w-l| <= 1) is unchanged, but the sentence says "An even series" only when the wins are level; at a
  one-game gap it reads "Separated by one game after 3 meetings: A 2, B 1" (ties still printed). Both
  wordings are pinned in `RivalryServiceTest`.
- **Phone width:** under `max-width: 480px` the sticky `.rv-first` column of the matrix and grid is capped at
  120px with the team name ellipsized and the avatar kept (`styles.css`, no logic change).
