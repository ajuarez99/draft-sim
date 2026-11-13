# Adversarial plan review: 064 Time Machine achievements and levels

**Date**: 2026-11-13 · **Reviewer**: adversarial plan-review pass (read cold, no 064 code exists)

**Inputs read**: `AGENTS.md`; `spec.md`, `plan.md` (the only two documents in the feature folder: no research,
data-model, contracts, quickstart or checklists exist); backend `historical/HistoricalGameService`,
`HistoricalGameRepository`, `HistoricalResult`, `SeasonReplay`, `SeasonFormat`, `Comparisons`, `HistoricalSport`,
`NbaHistoricalSport`, `NflHistoricalSport`, `WeekStatLine`, `HistoricalPlayerCard`, `HistoricalWeeks`,
`HistoricalDataRepository`, `NflHistoricalRepository`, the row filters of `KaggleHistoricalSource`,
`SleeperHistoricalSource`, `NflverseSource`; `api/HistoricalGameController`; `V43`, `V46`; `application.yml`
(`historical:`, `historical-nfl:`, datasource); `build.gradle.kts`; `ingest/LeagueIngestService` and
`store/DraftTargetRepository` (transaction and advisory-lock precedents); web `pages/HistoricalRoom.tsx`,
`usePickReveal.ts`, `pages/HistoricalDraft.tsx`, `historicalCopy.ts`; `specs/063-.../plan-review.md` for format.

**Checks I ran** (read-only, scratchpad only):
- `git ls-tree` of `db/migration` on every `origin/*` branch and every local head; `ls` of the migration folder in
  the main checkout and every `.claude/worktrees/*` checkout.
- `psql` on `localhost:5433` with `default_transaction_read_only=on`: Flyway history, `show
  default_transaction_isolation`, row counts of the historical tables, and the planned lock expression.
- Downloaded one Sleeper NBA stats week (`/stats/nba/2023/3?season_type=regular`) and one nflverse file
  (`stats_player_week_2015.csv.gz`) to the scratchpad and counted empty, zero-time and all-zero rows with a script.
- Read `backend/src/test/resources/historical/result-062.json` (the old-format fixture) with a script.

**Not run**: any Java, and no query over real game or box-score rows. The local 5433 database has **zero**
`historical_season`, `historical_game`, `historical_player_game` and `historical_nfl_player_week` rows, and no
scratch cluster (5435, 5438) is up. So every statement below about how *often* a badge fires is REASONED, and no
planned SQL was executed against data. Savepoint behaviour was not executed either.

**VERIFIED** = I ran or read something that shows it. **REASONED** = argument only.

Severity (as asked for this review): **BLOCKER** = the plan as written produces a wrong result in normal use.
**SHOULD** = a real failure, contradiction or hole to close before tasks. **NIT** = drift, hygiene, wording.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| PR-01 | SHOULD | Transactions | Use one settle transaction + a per-game `PROPAGATION_NESTED` template; not `REQUIRES_NEW`, not the shared `TransactionTemplate` bean, catch outside the nested call |
| PR-02 | SHOULD | Re-import | A re-keyed player does not throw in settle; he silently scores 0 and earns false BIG_BUST / REACH_MISS / GONE_EARLY. Capture pick facts at completion instead |
| PR-03 | SHOULD | Failure state | A game that fails to settle is retried on every read for ever, earns nothing, and the award endpoint's answer for it is unspecified |
| PR-04 | SHOULD | Product shape | Every badge is once per user, so the reach / bust / gone-early popups Allan asked for stop after the first few games. Needs Allan |
| PR-05 | SHOULD | Rule fires always | REACH_HIT is near-automatic for any late-round reach (pick 160 only has to finish 160th of 168) |
| PR-06 | SHOULD | Rule fires never | CLEAN_SHEET needs all 14 or 15 picks to finish at or above their slot; BEAT_HINDSIGHT unmeasured. Measure both with the 062 harness before shipping them |
| PR-07 | SHOULD | Honesty | GONE_EARLY: no DNP rows exist (good), but the Kaggle import drops real rows (team mismatch, ambiguous name), so "last box score" can be an import artifact |
| PR-08 | SHOULD | Missing state | "Title" is undefined for a format-2 NO_PLAYOFFS season (finish 1, no bracket). Define title as `userOutcome == CHAMPION` |
| PR-09 | SHOULD | Backfill | A mixed first batch (old games + one new) leaves `seen_level` at 1, so the old games' levels pop up loudly |
| PR-10 | SHOULD | Toasts | Which badges a game page toasts, and when "seen" is posted, are unspecified; a batch POST at fetch loses queued toasts |
| PR-11 | SHOULD | Spec vs plan | SC-002's sum double-counts badge XP against the plan's `total`; the level-bar example contradicts "XP into the level" |
| PR-12 | SHOULD | Deploy order | Split services: a new web on an old backend gets 404 from `/profile` and `/award`; only the award panel has a stated fallback |
| PR-13 | NIT | Reveal gate | The gate is right if it reads the reveal `view`; put the fetch in a component mounted only in the results branch |
| PR-14 | NIT | Wins | A playoff tie decided by seed counts as a weekly win and a playoff win; old-format wins cover the whole season |
| PR-15 | NIT | Rule twice | Settle needs the board snapshot, scoring and round; all are private to `HistoricalGameService` or already on `PickRow` |
| PR-16 | NIT | Access / tests | Name the three refusals per route; 404 before 409; IT cleanup of the two tables that have no cascade |
| PR-17 | NIT | Cost | `playedWeeks` for drafted ids only; the lock is held across the CPU work; no measurement method stated |
| PR-18 | NIT | Levels | Retuning thresholds moves levels retroactively while `seen_level` does not |

Checked and **OK** (no finding):
- **V48 number**: highest migration is V47 on `origin/main` and on all 76 remote branches; no local head and no
  worktree folder holds a V48 or later. No collision. (VERIFIED)
- **V48 statements**: `add column ... boolean not null default false` is a metadata-only change on PG 11+ (local
  is 14.5); the `update` rewrites only heap tuples of COMPLETE rows and does not rewrite their TOASTed `board` /
  `result`. No code deletes `historical_game` rows outside ITs, so the `cascade` / `set null` choices are inert in
  production. (VERIFIED by reading; not executed)
- **Sign of every rule**: `HistoricalPlayerCard.rank` is 1 = best (`HistoricalPlayerCard.java:15`),
  `Comparisons.valueRanks` is 1 = best (`Comparisons.java:63-71`), bust gap is `rank - pickNo > 0`
  (`Comparisons.java:86`). So `boardRank - pickNo >= teams` is a reach, `seasonValueRank <= pickNo` is a hit,
  `seasonValueRank - pickNo >= 2*teams` is a miss, BIG_BUST and LATE_GEM point the right way. No inversion.
- **Risk 5**: `standings.wins` is regular season only in a new-format result (`SeasonReplay.java:224-240`, comment
  at `:242`, javadoc `HistoricalResult.java:14`). Playoff wins are exactly the matchups with `winnerSlot ==
  userSlot`; a bye is a `byeSlots` entry, not a matchup (`SeasonReplay.java:341`). No double count.
- **Risk 4**: NFL `week_count` is the highest REG week in the file (`HistoricalImportService.java:173`,
  `NflverseSource.java:143`): 17 or 18. With `regular-season-weeks: 14`, `SeasonFormat.of` gives playoffs in 15-17
  and 0 or 1 unused week. Weeks are the NFL's own numbers, so a bye is simply a week with no row; it cannot make a
  "last week" earlier than the truth. `GONE_EARLY` should read `seasonWeeks` from `result.format()`, not from the
  live season row.
- **Risk 3, DNP rows**: none in any source. Kaggle keeps a row only if `min > 0 || pts > 0`
  (`KaggleHistoricalSource.java:291-293`); Sleeper drops empty `stats` (`SleeperHistoricalSource.java:116`), and in
  the week I fetched 719 of 1,833 entries were empty and all 1,114 non-empty ones carried `sp > 0` (seconds
  played); nflverse rows come from play-by-play, and of 5,618 skill-position REG rows in 2015, 921 score zero on all
  13 keys but belong to players who were on the field (returners, special teams). A row means he appeared. See
  PR-07 for the opposite problem.
- **Isolation**: `read committed` (queried), no Hikari override in `application.yml`. After the advisory lock is
  granted, the "unsettled games" select sees the previous holder's commit.
- **Lock expression**: `hashtextextended('tm-ach:' || ?, 0)` is `bigint` and `pg_advisory_xact_lock(...)` returns
  `void` on PG 14.5 (ran it). Precedent: `DraftTargetRepository.java:64`.
- **Identity**: `requireIdentity` already refuses null and blank (`HistoricalGameController.java:154-159`).
- **XP table**: all 21 badge codes have an XP value; the level formula matches its four examples.
- **Seasons offered are complete**: NBA last offered 2025 (2025-26), NFL last offered 2025; no in-progress season
  can truncate a "last box score".
- **`Map.of`**: the plan already rules it out on the new paths.

## The seven risks, answered

1. **Lock + savepoint + `on conflict`**: sound, with the mechanism in PR-01. Double settle is prevented by the
   lock under read committed, and backed by the `game_id` primary key.
2. **Re-imported season**: nothing throws. See PR-02; the plan's "fail softly" has no trigger as written.
3. **DNP rows**: do not exist in any of the three sources (above). "Last week with a row" is "last week he
   appeared in the data". PR-07 covers rows that are missing.
4. **NFL weeks and byes**: fine (above).
5. **`standings.wins`**: regular season only; playoff wins exactly derivable (above). PR-14 for ties.
6. **Spoiling the reveal**: not if the fetch is gated on the reveal `view`. PR-13 says where.
7. **V48**: no problem found, no collision (above).

## Findings

### PR-01 (SHOULD, VERIFIED by reading; not executed) - the concrete transaction mechanism

The plan leaves "NESTED or REQUIRES_NEW" to the reviewer. Pick NESTED, and four details decide whether it works:

- **Transaction manager**: `build.gradle.kts:18` has `spring-boot-starter-jdbc` and no JPA, so the auto-configured
  manager is `JdbcTransactionManager`, which allows nested transactions through JDBC savepoints by default.
- **Not `REQUIRES_NEW`**: it takes a second pooled connection while the outer one (holding the advisory lock) is
  still checked out. The pool is 10 in production and 3 in the test JVM (`application.yml:16`,
  `build.gradle.kts:47`), so a handful of concurrent settles can each hold one connection and wait for a second.
  This repo has already written down why it avoids `REQUIRES_NEW` inside a held transaction
  (`LeagueIngestService.java:83-86`).
- **Not the injected bean**: `HistoricalImportService:50`, `KaggleHistoricalSource:82` and
  `SleeperHistoricalSource:81` share Spring's singleton `TransactionTemplate`. Calling
  `setPropagationBehavior(NESTED)` on it would change the importers. Build two private templates from the
  `PlatformTransactionManager` (`new TransactionTemplate(txManager)`, as `LeagueIngestService.java:46` does): one
  default for the batch, one NESTED for a game.
- **Catch outside the nested call**: `try { nested.executeWithoutResult(s -> settleOne(game)); } catch
  (RuntimeException e) { log; failures++; }`. An exception leaving the callback rolls back to the savepoint. A
  `catch` *inside* the callback after a failed statement leaves Postgres in "current transaction is aborted" and
  poisons every later game. No `@Transactional` on a private per-game method either: self-invocation skips the
  proxy (`LeagueIngestService.java:88-90`).

Also specify: settle runs in its **own** write transaction before the read, not inside a
`@Transactional(readOnly = true)` read (the existing reads at `HistoricalGameService.java:158,163` are read-only);
take the lock with `jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", key)` because the
function returns `void`; and when the award insert's `on conflict (game_id) do nothing` reports 0 rows, skip that
game's badge inserts.

### PR-02 (SHOULD, VERIFIED) - a re-keyed or re-imported season makes settle wrong, not failed

`finishDraft` detects a re-key by hand: it looks for a drafted player with no rows and throws
(`HistoricalGameService.java:356-366`). The plan's settle calls `playedWeeks` and `Comparisons.valueRanks`
directly, and neither throws: `playedWeeks` returns fewer rows, and `valueRanks` uses `value.getOrDefault(id, 0.0)`
(`Comparisons.java:66`). A drafted player whose rows are gone ranks last among the drafted, which satisfies
BIG_BUST and REACH_MISS, and his `lastWeekPlayed` is absent, which either throws or reads as "week 0" for
GONE_EARLY. A re-import that keeps ids but changes numbers (`replaceSeasonGames` deletes and re-inserts,
`HistoricalDataRepository.java:208-209`) is quieter still: the badge's numbers disagree with the frozen bust
callout on the same page.

The window is "between completion and first award read". Normally seconds; unbounded if the tab is closed, or if
the backend ships before the web.

**Change (recommended)**: capture the pick facts where they are guaranteed consistent with the result, in
`finishDraft`, and evaluate lazily as planned. `finishDraft` already holds `played`, the scoring and the cards.
Add a nullable `historical_game.pick_facts jsonb` to V48, written in the same `update` as `result`
(`HistoricalGameRepository.writeResult`), holding for each user pick: pickNo, round, player name, boardRank,
seasonValueRank, seasonValue, lastWeekPlayed. Compute it in a `try/catch` around pure code so a bug stores null
and never blocks completion (FR-004). Settle then reads only the game row: no `playedWeeks`, no board parsing, no
dependence on reference tables, and risk 2 disappears. A game with null facts settles from the saved result. This
does not touch `result` (FR-002).

**If the plan keeps recompute-in-settle**: before evaluating, require every drafted id to have rows, and recompute
`Comparisons.bust` and require it to equal `result.biggestBust()` (pickNo, rank, value). On either mismatch,
settle that game from the saved result only and record why.

### PR-03 (SHOULD, VERIFIED by reading the plan) - a game that cannot settle has no defined end state

Plan step 4: a throwing game "is logged at error and left unsettled". Consequences not addressed:

- Every later `GET /profile` and `GET /award` selects it again, redoes the work and logs at error again, for ever.
- It earns nothing, including the 100 XP and the weekly wins its saved result proves.
- `GET /api/history/games/{id}/award` for that game: there is no award row. The plan lists 404 (not yours), 409
  (not COMPLETE) and the success shape, and nothing for "COMPLETE, yours, unsettled".
- `settleFailures` is returned but no UI for it is described.

**Change**: when the pick-level evaluation fails for a reason tied to the data, settle the game from its saved
result (`from_saved_result = true`, plus a short reason column or a `detail` note) so it settles once. Leave a
game unsettled only for an unexpected exception. Specify the award endpoint's answer for an unsettled game (for
example 200 with `settled: false`, which the panel renders as the FR-004 line) and the landing page's sentence for
`settleFailures > 0` ("N games could not be counted yet").

### PR-04 (SHOULD, REASONED) - once-per-user badges do not match "popping up" for a reach or a bust

Allan's ask (spec.md:4-5): achievements popping up "for getting a crazy pick reach or getting a bad bust". FR-003
makes every badge unlock at most once per user. BIG_BUST, REACH_HIT, REACH_MISS, LATE_GEM and GONE_EARLY are
events of one draft, yet each can pop exactly once in a user's life. With the thresholds as written (PR-05) most
of them are gone inside the first few games; after that a finished game pops nothing until the next wins
milestone (10, 25, 50, 100).

This is a decision about what the feature is, not how it is built. Options: (a) keep once-only and accept it; (b)
per-game badges are repeatable, stored per (user, code, game), shown in the trophy case with a count ("Called it
x3"), XP paid the first time only or at a reduced repeat value; (c) once-only badges, plus a per-game "moments"
list in the award panel that names this game's reaches and busts without XP. (b) changes the primary key of
`historical_achievement`; decide before V48 is written.

### PR-05 (SHOULD, REASONED) - REACH_HIT is close to automatic late in the draft

`REACH = boardRank - pickNo >= teams`; `HIT = seasonValueRank <= pickNo`, where the rank is among drafted players
only (168 in basketball: 12 x 14; 180 in football: 12 x 15, `application.yml:113-114,154-155`). For pick 160 the
"hit" condition is "finished 160th or better of 168": he only has to outscore eight drafted players. Any late pick
taken 12 spots down the board, which is what a fan who knows the season does on purpose, earns "Called it" (100
XP). The mirror: REACH_MISS needs `seasonValueRank >= pickNo + 24`, which is impossible for the last 24 picks.
Both are well-formed and neither means what its name says in the back half of the draft.

**Change**: bound the reach rules to where a rank comparison is meaningful, and ask for a margin. For example
REACH applies to picks in rounds 1 to `rounds / 2`, and HIT requires `seasonValueRank <= pickNo - teams` (he
finished a full round better than where you took him). State the numbers on the badge. Then count how often it
fires with the harness in PR-06.

### PR-06 (SHOULD, REASONED; one sample read) - CLEAN_SHEET and BEAT_HINDSIGHT may never fire

- CLEAN_SHEET = `biggestBust == null` = **every** one of the user's 14 or 15 picks finished at or above his pick
  number (`Comparisons.java:80-95`). A first-round pick at 6 must finish top 6 of 168, the next top 19, and so on.
  It is also slot-biased: it is easier from slot 12 than slot 1. I expect well under 1 game in 100.
- BEAT_HINDSIGHT = the greedy hindsight roster, chosen with full knowledge from everyone available at each of the
  user's picks (`Comparisons.java:147-186`), scored less than the user. The user's own pick is always a candidate.
  The one stored sample I read (`result-062.json`) has hindsight 6,510 against the user's 6,247; the 053 research
  note has 7,684 against 5,812.

A locked badge that cannot be earned is a display the data cannot back. **Change**: before the catalog is fixed,
run `TimeMachineBotStrengthHarnessIT`'s leagues (168 basketball, 78 football at the 062 measurement) through
`AchievementRules` and record how many games earn each per-game badge, auto-drafted. Put the table in the plan.
Drop or loosen any badge at 0, and say plainly which ones were only measured for auto-draft.

### PR-07 (SHOULD, VERIFIED the drop path; size unmeasured) - GONE_EARLY can be an import artifact

No source stores a row for a game a player sat out, so risk 3's worry does not apply. The opposite does: the
Kaggle import discards played rows it cannot attribute. `KaggleHistoricalSource.java:411-420` drops every box row
with `TEAM_MISMATCH` ("box rows on a team the season file never lists") or `AMBIGUOUS_SAME_TEAM`, and only counts
them in the import report. A player traded in week 3 to a team his season-file entry does not list keeps playing
in real life and stops having rows here. Kaggle also drops a scoreless appearance in a season with no minutes
column (`:291-293`). Football drops rows with a blank player id (`NflverseSource.java:150-153`, surfaced as
`droppedRows`).

FR-006's wording ("his last box score came in week N") is then false for that player; "the last box score we have
for him" is true. I could not count affected players: the local database has no historical rows.

**Change**: (1) word the badge and its `detail` as "the last box score in our data is week N"; (2) on a database
that has the import (prod, read-only), count board players in rounds 1-3 by rank whose last row is week 3 or
earlier, per source, and read a sample by name before shipping; (3) if the Kaggle count is dominated by trades,
restrict GONE_EARLY to seasons whose import report shows zero mismatch rows for that player, or to SLEEPER and
NFLVERSE seasons, and say so on the locked badge.

### PR-08 (SHOULD, VERIFIED) - a title is not defined for a format-2 season with no playoffs

FR-008 covers old results only ("a standings rank of 1 in an old result is not a title"). The plan says playoff
and title badges "require" `format != null`. But `SeasonFormat.of` returns a format with **no** playoff weeks when
the season is too short (`SeasonFormat.java:42`): 12 teams need `seasonWeeks - 3 >= 11`. Then `playoffs` is null,
`userOutcome` is `NO_PLAYOFFS` and `userFinish` is the standings rank (`SeasonReplay.java:282-287`). A rule
written as "`format != null && userFinish == 1`" pays +250 XP, CHAMPION, and counts toward TITLES_3 and
BOTH_SPORTS for a season the page itself headlines as "You finished 1st of 12" (`historicalCopy.ts:211`), never
"Champion". Whether a real basketball season is that short (1998-99 is the candidate) is unmeasured here; 063's
review PR-03 left the same question open.

**Change**: define it once in the plan and spec: title = `"CHAMPION".equals(result.userOutcome())`; PLAYOFFS =
the user's slot is in `playoffs.seeds`; `playoffs == null` means zero playoff wins. Add a NO_PLAYOFFS case to
`AchievementRulesTest`. Say on the locked CHAMPION badge that a season with no bracket cannot earn it.

### PR-09 (SHOULD, REASONED) - a mixed first batch makes the backfill loud

Plan: "If a settle batch contained only such games, `historical_profile.seen_level` is set to the resulting
level." The first settle after deploy can easily hold old games plus one new one: the user resumes a DRAFTING
game by its URL, or the backend ships a day before the web (PR-12) and a game is finished in between. Then
`seen_level` stays 1 and the results page toasts a level-up that mostly comes from old games, which FR-005 and
SC-004 say must be silent.

**Change**: drop the batch rule. Games settle oldest first and every `pre_achievements` game was completed before
any new one, so after settling **each** `pre_achievements` game, raise `seen_level` to the level at that point.
Add the mixed batch to the IT list.

### PR-10 (SHOULD, VERIFIED by reading the plan) - toast scope and the "seen" write are underspecified

- **Scope**: "show toasts for badges with `unlocked && !seen`" reads as every unseen badge on the profile.
  Opening game A then toasts a badge earned by game B, with B's player and numbers. The award response already
  carries this game's badges. Choose: the game page toasts only badges whose `gameId` is this game, plus the
  level-up; the landing page marks anything else unseen as "New" and posts seen for those.
- **When**: "then POST seen" after the fetch marks everything seen at once. A first game can unlock six or more
  badges; at three on screen and about 6 s each, a fan who leaves after ten seconds never sees the rest, and
  SC-001 ("then never again") makes that permanent. Post seen per toast as it is shown or dismissed (the endpoint
  already takes a list).
- **Twice**: React StrictMode runs the effect twice in dev; two fetches must not queue the toasts twice. Key the
  queue by badge code.
- **Level**: `POST /profile/seen` with `level` when no `historical_profile` row exists must upsert.

### PR-11 (SHOULD, VERIFIED) - spec and plan disagree on two sums

- SC-002: "the profile's XP equals the sum of every game's total plus every earned badge's XP". Plan:
  `total = xp + sum(badges.xp)`, so a game's total already contains its badges; SC-002 as written counts each
  badge twice. With the plan's schema the true identity is profile XP = sum of `historical_game_award.xp` + sum of
  `historical_achievement.xp`, which equals the sum of game totals only while every badge row has a `game_id`
  (`on delete set null` can break that). Rewrite SC-002 to the first form.
- Level bar: spec US3 says "XP into the level with a labelled progress bar". Plan's example is "340 of 600 XP to
  level 4", which is total XP against the next threshold; at 340 XP the fan is level 3 (300 to 600), 40 XP into a
  300 XP level. A bar drawn 340/600 looks more than half full; drawn 40/300 it is 13%. Pick one and make the bar
  and the sentence use the same two numbers (the API already returns `levelStartXp` and `nextLevelXp`).

### PR-12 (SHOULD, REASONED) - web and backend deploy separately

The two Railway services have shipped out of step before, and 063's note is "deploy web first". A new web on an
old backend gets 404 from `/api/history/profile` and `/api/history/games/{id}/award`. The plan gives the award
panel a fallback and says nothing for the landing page; `HistoricalDraft.tsx` routes failures through `fail(e)`
into a page-level error banner (`:63-65`). **Change**: the level card and trophy case render nothing on any
profile failure, with a test beside `HistoricalOldBackend.test.tsx`. State the deploy order in the plan. The
reverse order (backend first) is the mixed-batch case in PR-09.

### PR-13 (NIT, VERIFIED) - where to gate the fetch

`HistoricalRoom` renders from `reveal.view` (`HistoricalRoom.tsx:117`), and the view reports `status: 'DRAFTING',
result: null` until the last pick is revealed (`usePickReveal.ts:120-134`). So a fetch gated on the view's
`status === 'COMPLETE' && result` cannot fire mid-reveal; one gated on `reveal.serverDrafting === false` or on the
raw response would spoil it. Two build details: an effect cannot live at `:286` (hooks must sit above the early
returns at `:197-205`), and auto-finish calls `reveal.skip()` so results are immediate (`:187`).
**Recommendation**: put the fetch, the toasts and the seen calls inside `HistoricalAwardPanel`, mounted only in
the results branch (`:286-303`). Mounting is then the gate and no flag is needed. Add a test: during a paced
final reveal, no request to `/award` or `/profile` is made until the last pick shows. (The landing list already
shows a finished game's outcome, so a second tab is not a new leak.)

### PR-14 (NIT, VERIFIED) - two win-count details to state

- A playoff game level to the hundredth goes to the better seed with `decidedBySeed = true`
  (`SeasonReplay.java:372-379`). `winnerSlot == userSlot` then pays 10 + 40 XP and PLAYOFF_WIN for a tie. Either
  accept it and say "advanced" in the line label, or exclude `decidedBySeed` from weekly-win XP.
- An old-format result's `standings.wins` covers every week of the season (the fixture has 22 weeks of round
  robin), a new-format one 19 or 14 plus playoffs. Fine, but the "N weekly wins" line of an old game should not be
  labelled "regular season".

### PR-15 (NIT, VERIFIED) - do not write the snapshot and round rules a second time

If settle recomputes (PR-02's second option), it needs the board cards, the scoring map and league settings.
`readSnapshot` / `readBoard` (which handle the pre-056 bare-array board), `settingsOf` and the `SCORING` reader are
private to `HistoricalGameService` (`:300-303, 480-499`). Expose them package-private rather than copying them.
Take `round` from `PickRow.round` (`HistoricalGameRepository.java:50`), `teams` and `rounds` from the game row,
never from the sport bean or a literal. `rounds / 2` is 7 for both sports (14 and 15 rounds), so LATE_GEM covers
rounds 8-14 and 8-15; say "round 8 or later" on the badge rather than "second half".

### PR-16 (NIT, REASONED) - access control and test hygiene

- Each of the three routes: no header, blank header and a stranger, in `HistoricalGameMvcIT`. Use
  `requireIdentity`, not a bare null check. On `/award`, resolve ownership before status so a stranger gets 404,
  never 409. `POST /profile/seen` with codes the caller has not unlocked is a no-op 204.
- `code = any(?)` needs `createArrayOf("text", ...)` (bug class 3; precedent `HistoricalGameRepository.java:70`).
- Existing ITs delete `historical_game` rows by user prefix on the shared 5433 database. Awards cascade;
  `historical_achievement` (set null) and `historical_profile` (no key to the game) do not. New ITs must delete
  their own rows from both by user id.
- Only one old-format fixture exists (`result-062.json`). Results stored under 053 and 056 are older shapes; the
  parse into `HistoricalResult` should be tested against at least one real row of each from prod.

### PR-17 (NIT, REASONED) - cost and how to measure it

The plan says "a `playedWeeks` query ... unmeasured for settle; measure it" without a method. `finishDraft` loads
the whole board's games; settle needs only the 168 or 180 drafted ids for `valueRanks`. The advisory lock and one
pooled connection are held across the scoring pass, on a virtual thread. State the method (log ms per game in
settle, as `finishDraft` does at `:373`) and a budget. PR-02's capture removes this cost entirely.

### PR-18 (NIT, REASONED) - retuning levels

Badge and game XP are frozen at unlock; level thresholds are derived live. Raising a threshold later lowers
levels while `seen_level` never goes down, so level-up toasts go quiet until the fan catches up. Either accept
and note it under FR-007, or clamp `seen_level` to the current level on read.

## For the parent session

Count: 0 BLOCKER, 12 SHOULD, 6 NIT. Nothing here stops the design; lazy settle under an advisory lock is sound.

Needs Allan's decision (changes what the feature is):
- **PR-04**: once-per-user badges against repeatable per-game popups. Decide before V48, it changes a primary key.
- **PR-05 / PR-06**: the thresholds of REACH_HIT, and whether CLEAN_SHEET and BEAT_HINDSIGHT stay if the harness
  shows they do not fire.
- **PR-07**: whether GONE_EARLY ships for Kaggle seasons, after the count on prod.
- **PR-08**: confirm a first place in a no-playoff season is not a title.

Everything else folds into the plan. PR-02's capture-at-completion is the one structural change I recommend: it
adds a column to V48 and a few lines to `finishDraft`, and removes risk 2, PR-15 and PR-17.
