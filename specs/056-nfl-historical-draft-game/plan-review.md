# Adversarial plan review: spec 056 (Football Time Machine)

This review read spec.md, plan.md, research.md, data-model.md, contracts/api.md and
quickstart.md cold on 2026-11-05. It checked them against the spec 053 code in worktree
`056-nfl-historical-draft-game` @ `01531cb`, against three nflverse files (2000, 2001,
2021, `stats_player_week_{y}.csv`), against Sleeper's `/v1/stats/nfl/regular/2021`
season dump, and against the V43 schema on the shared 5433 database (read-only, plus
one DDL probe in a throwaway schema inside `BEGIN … ROLLBACK`, so nothing was left
behind). No code was written.

Every item is labelled **measured** (run today, result quoted) or **read** (from code
or docs, not executed). **Suspected** marks a conclusion that was reasoned out but
not run. The probe scripts are in the session scratchpad under `scripts056/`, and
the downloads are in `dl056/`. Neither is in the repo.

Findings marked **[WHAT, not HOW]** change what the feature is, not just how it gets
built. Allan should decide those.

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| R1 columns: `player_id`, `player_display_name`, `position`, `season`, `week`, `season_type`, `game_id`, `team`, `opponent_team`, the counting stats, `*_2pt_conversions`, `*_fumbles_lost`, `special_teams_tds`, `fantasy_points_ppr` | 2000 header, 150 columns (measured) | ✅ All present, with exactly those names |
| 2000 has 246 REG game_ids | measured | ✅ 246 (weeks 3, 6 have 13 games, weeks 4, 5, 7–9 have 14) |
| R2: FR-003 plus `st_td` matches `fantasy_points_ppr` on 100% of rows | measured on **2000, 2001, 2021** (2001 and 2021 are new seasons) | ✅ 0 mismatches over 5260 / 5397 / 5987 QB/RB/WR/TE/FB rows. No skill row has an NA PPR |
| PK `(player_id, season, week)` holds | measured | ✅ 0 duplicate (player, REG week) pairs in all 3 files |
| Position is constant within a season | measured | ✅ 0 players with more than one position per season in all 3 files. "Last row's position" and "first row's position" are the same thing |
| **R4's open question: how 2001's postponed week 2 is numbered** | 2001 file (measured) | ✅ **nflverse keeps it as week 2** (`2001_02_BAL_CIN` …, 14 games). REG weeks are 1–17, with 248 game_ids, so 2001 matches the 248 expected. `week_count` = 17 and no week 18 exists. The spec edge case ("follows the source's week numbers … still has 17 weeks") holds as written. Note that the week-2 matchups are scored from games played in January. That doesn't matter for best ball |
| 2021 has 18 weeks and 272 games | measured | ✅ 18 REG weeks, 272 game_ids |
| FootballRules with QB,RB,RB,WR,WR,TE,FLEX + 8 BN and no K/DEF | `sport/FootballRules.java:136-175, 304-317`, `LeagueSettings.dedicatedStarters:56-65` (read) | ✅ K/DEF get 0 dedicated slots. `isDraftable` gates only K and DEF by `latestRounds` (`weights.yml` K:3, DEF:4), and the board has none, so it is always true. PickDecider's "only K/DEF left" fallback (`PickDecider.java:107-111`) never fires. `startingLineup` fills dedicated slots and then one FLEX from the RB/WR/TE overflow, so acceptance scenario 5 holds. 15 rounds = 7 starters + 8 BN |
| Bots don't assume NFL ADP data | `PickScorer.valueDelta:104-108`, `FootballRules.value:78-80` (read) | ✅ Both read `adp` as a pick-number rank. Board rank 1..N is the same unit with the same sign, as in 053. `PositionalPriors.uniform(NFL)` is 1/6 per position, a constant log term with no effect on the ordering |
| `RealizedLineupService.bestLineup(List, …)` has no sport branch | `engine/RealizedLineupService.java:107-137` (read) | ✅ |
| Sleeper key vocabulary | `src/test/resources/sleeper/nfl-scoring-parity-2025.json` (the real NFL league's `scoring_settings`) and the Sleeper 2021 stats dump (measured) | ✅ `pass_yd, pass_td, pass_int, rush_yd, rush_td, rec, rec_yd, rec_td, fum_lost, pass_2pt, rush_2pt, rec_2pt, st_td` are all real Sleeper keys. `st_td` is the player special-teams TD (`def_st_td` is the defense's). `ProjectionParityService.ASSUMED_DEFAULT_PPR:39-43` already uses the same names |
| The V43 constraint names in data-model | `pg_constraint` on 5433, which has V43 applied (measured) | ✅ `historical_season_pkey`, `historical_game_season_start_fkey`. Also needed and **not named** in data-model: `historical_season_source_check` and `historical_player_source_check` (see F3) |
| The board is big enough for 12×15 = 180 | 2001 drafted from 2000 lines, S-1 only (measured) | ✅ 533 candidates, 326 with a qualifying prior line (≥ 6 games). With the 3-season lookback there will be slightly more |
| V45 is free | `git log --all` over `V4[4-9]__*` (measured) | ✅ Only V44 (055) exists, plus a `V45__historical_draft_game.sql` that was added and then reverted on 053 (cf84da0 / ff6711f). Neither exists at any branch tip |

## Findings

Ranked by severity.

**F1 (HIGH, measured). The plan's premise that "053 is unmerged / not deployed" is
out of date. 053 is on `main`.**
`origin/main` holds `V43__historical_draft_game.sql` and V44. Its head `5a47471` is
"Merge 053-historical-draft-game … closes PR #53", and ff6711f says "main merged 053
(V43) before 055 (V44) on 2026-11-03". This undermines three plan statements:
- spec.md:9 and plan.md "(off `053-historical-draft-game`, PR #53 unmerged)";
- the constitution check says "V43 is not edited, though unmerged";
- contracts/api.md says "053 is not deployed, so there is no old client to stay
  compatible with". **Suspected:** main was deployed for 055's NBA week-3 deadline,
  so the Time Machine and real users' NBA games are probably live in prod. That was
  not checked against prod.

Consequences: SC-008 now protects real prod games, not just a scratch DB. A
required `sport` with no default (contracts) breaks the deployed web bundle for as
long as the backend deploys ahead of the web service. These are split services that
deploy independently (memory: deployed-to-Railway).
**Resolution:** rebase 056 onto `main` and target the PR at `main`. Keep `sport`
required, but **deploy web first**: today's backend ignores an unknown `sport` body
field and query param, since Spring doesn't fail on unknown properties. Write the
deploy order into quickstart/tasks. Check prod for existing `historical_game` rows
before V45, so SC-008's "game created before V45" can use a real one.

**F2 (HIGH, measured). V45 as listed can't execute: the season PK can't be dropped
while the game FK depends on it.**
data-model lists the `historical_season` PK swap before the `historical_game` FK
swap. Probe (throwaway schema, rolled back): `alter table historical_season drop
constraint historical_season_pkey` → `ERROR: cannot drop constraint
historical_season_pkey … because other objects depend on it. DETAIL: constraint
historical_game_season_start_fkey … depends on index historical_season_pkey`. This
order works (measured, every statement OK):
1. add `historical_season.sport`;
2. add `historical_game.sport`;
3. drop `historical_game_season_start_fkey`;
4. drop `historical_season_pkey`;
5. add PK `(sport, season_start)`;
6. add FK `(sport, season_start)`;
7. swap `historical_season_source_check`;
8. drop not null.

No other FK references `historical_season` (`historical_game_pick` references
`historical_game(id)` and `historical_player(id)` only, measured). Don't use
`CASCADE`: it would silently drop the FK.
**Resolution:** put this order into data-model and T-migrate.

**F3 (HIGH, measured). Two 053 queries break or turn wrong as soon as V45 lands or
the first NFL season exists. Neither is in the plan's list of "six hardcoded sites".**
- `HistoricalDataRepository.upsertSeason:207` uses `on conflict (season_start)`.
  After the PK change, Postgres refuses it. Probe: `ERROR: there is no unique or
  exclusion constraint matching the ON CONFLICT specification`. **The NBA import
  breaks the moment V45 is applied**, before any NFL code runs. That is an SC-008
  regression and bug class 2.
- `HistoricalGameRepository.summaries:94` joins `on s.season_start = g.season_start`.
  With NFL 2005 and NBA 2005-06 both present, one game returns **2 rows** (probe:
  `join rows for one game: 2`). Every NBA game from 2000-01 to 2025-26 would show
  twice in the list, once with an NFL label.
- Also sport-blind: `findSeason:230` (it would return either sport's row), and
  `offeredSeasons:248-253`. Its `exists (… historical_player_season … season_start
  - 1)` is satisfied by **NBA** lines, so an unscoped NBA query would offer NFL
  season rows as basketball games.

The plan's "sport-scoped season queries (offered, find)" covers the last two in
spirit. It misses the upsert and the join.
**Resolution:** list all four statements in the plan with their file:line. Make
`upsertSeason` take `sport` and conflict on `(sport, season_start)`. Join
summaries on both columns. Add an IT that creates NBA 2005 and NFL 2005 rows and
asserts that the list has one row per game and that NBA re-imports still run.
Widening `historical_player_source_check` (named above) belongs in V45 too.

**F4 (HIGH, read). `SeasonReplay` throws an NPE on every football starter, and the
web says "did not play" for every football starter.**
`SeasonReplay.replay:240` does `l == null ? null : l.date().toString()`. Starters
always have a `Locked` (only players with a game are passed, `:177-181`), so for
NFL, where `gameDate` is null (R4), `l.date()` is null → NPE at the end of every
football draft. The plan touches `SeasonReplay` only for `statMap`. Separately,
`HistoricalResults.tsx:171` renders `s.gameDate ?? 'did not play'`, so even with the
NPE fixed, every football starter reads "did not play". The copy at `:157` ("the
date is the game that was locked") and on the start page
(`HistoricalDraft.tsx:89`, "each starter's best game of the week") is also NBA-only.
**Resolution:** null-safe date in `SeasonReplay`. Per sport in the web, either a
"Locked game" column that shows "Week N" or the opponent, or drop the column for
football. Sport-aware copy. Add a unit test that replays one NFL week with
null-dated lines.

**F5 (HIGH, measured). SC-005 can't pass as written: Sleeper's `pts_ppr` uses
different scoring from FR-003.**
On Sleeper's 2021 season dump (352 non-team players with `pts_ppr ≥ 50`), FR-003
applied to **Sleeper's own stats** comes within 1 point of Sleeper's `pts_ppr` for
only **188 / 352 (53%)**. For QBs it is 10 / 33. The best-fitting simple formula is
INT −1, −1 per fumble, −2 per fumble lost (281 exact), which is not FR-003. So
SC-005's "≥ 95% within 1 point" would fail even when the nflverse import is
perfect. A failing SC invites exactly what the hard rules forbid: retuning FR-003
until a number agrees.
**Resolution:** rewrite SC-005 to cross-check **stats**, not points. Per player, the
season sums of `pass_yd, pass_td, pass_int, rush_yd, rush_td, rec, rec_yd, rec_td`
from nflverse should equal Sleeper's, which needs a GSIS→Sleeper id join via
Sleeper's player dump. Or compare FR-003 applied to Sleeper's stats with FR-003
applied to nflverse's. Either way, report the measured share and don't pre-commit
95%. Mark SC-005 "amended after review".

**F6 (MEDIUM, measured board, suspected bot effect) [WHAT, not HOW]. Raw
prior-season points over-rank QBs, so the bots will hoard QBs.**
On the 2001 board (from 2000 lines), the top 24 holds **7 QBs** and the top 60
holds **15 QBs**. A 1-QB, 12-team league starts 12. FR-003's 4-point pass TD and
0.04/yd make a mid-tier QB outscore most WR2s. The bots' only brake is need:
football `rosterNeed` for a second QB = `benchFloor` 0.15, against 1.0 for an
empty RB/WR/FLEX slot. That is a 0.425 score gap at `w_need` 0.5, about 5 picks of
`valueDelta` at `adpScale` 12. So a QB who has fallen more than about 5 picks
further than the best RB/WR is taken as a backup. In 053 every NBA position is
interchangeable through UTIL, so this didn't arise. Expect bot rosters with 2–3 QBs
by round 8 and a game that's easy to beat by drafting QB late. The bot-roster
effect was not run through the engine.
**Resolution:** two options for Allan.
- (a) Accept and label it: "bots draft from raw last-season points".
- (b) Rank by points over a positional replacement level, for example the
  12th QB / 24th RB / 24th WR / 12th TE prior-season line. That is a new hand-set
  constant and has to be labelled.

Either way, T-measure should report the QB count on bot rosters from a seeded
auto-finish of one season. Add this as plan open item O3.

**F7 (MEDIUM, measured). The "rows with no player id" data note describes team
rows, not dropped players.**
In 2001 and 2021, every blank-`player_id` row is a team-total row:
`player_name = 'Team'`, position blank, carrying only penalties, team tackles and
punts. That's 17 REG rows in 2001 and 18 in 2021, and 0 in 2000. 2005's 17 are
suspected to be the same. Showing "17 source rows had no player id and were left
out" (FR-014, data-model's example) tells the user that player data is missing
when none is. That's bug class 5 (a correct count that misleads).
**Resolution:** count only blank-id rows that have a skill `position` or any
nonzero scored stat. Expect 0. Record the raw count in the import report only.
Amend the spec edge case and R1 in place.

**F8 (MEDIUM, read). The defaulted `sport` columns encode a rule.**
data-model adds `historical_game.sport … default 'NBA'` and `historical_season.sport
… default 'NBA'` so that backfill is free. Left in place, an NFL insert that forgets
the column (`HistoricalGameRepository.create:52-73` has a fixed column list today)
gets silently tagged NBA. When NBA has the same `season_start`, the FK is
satisfied, so nothing fails. That is the "optional params that encode rules" bug
the contracts doc itself cites.
**Resolution:** in V45, add the column with the default, then `alter column sport
drop default`, for both tables. Existing rows keep 'NBA', and every new insert must
name a sport.

**F9 (MEDIUM, read). Adding `dataNotes` to the board snapshot breaks reopening
pre-V45 NBA games (SC-008).**
`HistoricalGameService.readSnapshot:487-493` reads the object form with
`n.get("excludedStats").toString()`, with no null check. If `dataNotes` is added to
the snapshot and read the same way, every NBA game created before V45 has no such
key → NPE on reopen. The bare-array legacy branch (`:490`) shows the pattern for
tolerating old shapes.
**Resolution:** `n.has("dataNotes") ? … : List.of()`. The SC-008 IT must reopen a
game whose board JSON was written by 053's code (a fixture string), not one created
by 056's code.

**F10 (MEDIUM, read). Undercounted call sites, from the plan's own "difficulty isn't
a spec" row.**
Beyond the six sites in R6, more 053 code assumes NBA. Each needs a line in the
tasks:
- `HistoricalGameController.seasons:62` calls `data.offeredSeasons()` directly, not
  through `HistoricalSport`;
- `SETTINGS_SOURCE` is a constant in two places (`HistoricalGameState:24`,
  `HistoricalGameController:36`);
- `HistoricalBoardBuilder.label` is used for `Prior.seasonLabel` and `bestOfTwo`
  (`:91, :143`), which the contract expects as "2006";
- `HistoricalDataRepository.FIRST/LAST_OFFERED:245`;
- `HistoricalGameService.create:113-141` reads top-level `props.teams/rounds/
  scoring/rank*`, which are the NBA values;
- `SeasonDisclosure` (`HistoricalRoom.tsx:50-80`) prints "A stat missing from a box
  score scores 0. Bonuses…" and coverage percentages, which are empty for NFL;
- `historicalCopy.ts:11-12` `STAT_ORDER`/`SHORT` are NBA-only, so perGame ordering
  would fall back to insertion order;
- `historicalCopy.ts:64` defaults positions to `'SF'`;
- `HistoricalDraft.tsx:70-100` copy ("a real NBA season", "1970-71") and its
  `seasons[0].settingsSource`, which is now per sport;
- `HistoricalGameMvcIT` constructs `new HistoricalProperties(…)`, which breaks when
  the record gains an `nfl` component.

**F11 (LOW, suspected). The download client may not follow GitHub's release
redirect.**
The plan names `RestClient`. GitHub release-asset URLs redirect to a CDN host. 053's
Kaggle download deliberately uses `HttpClient.newBuilder().followRedirects(ALWAYS)`
(`KaggleHistoricalSource.java:176`). Whether Spring's default `RestClient` request
factory follows that redirect wasn't measured.
**Resolution:** reuse 053's `HttpClient` construction (`Redirect.NORMAL` is enough).
The `.csv.gz` asset exists (HEAD 200, 980,369 bytes for 2001, measured).

**F12 (LOW, read). `FootballRules.startingLineup` iterates `Map.of`, so FLEX ties
depend on the JVM.**
`LeagueSettings.dedicatedStarters:57-64` returns `Map.of(…)`, whose iteration order
is salted per JVM. Totals are order-independent, but on an exact points tie the
player who lands in FLEX, and the starters' order in the stored JSON, can differ
between JVMs. A "same seed → byte-identical result" test passes inside one JVM and
can flake across them. Finished games are frozen, so FR-010 is unaffected.
**Resolution:** don't change FootballRules, since a live sim depends on it. In 056's
replay test, compare totals and lineup sets, not JSON order, or sort starters by
slot then id before storing.

**F13 (LOW, measured) [WHAT, not HOW]. FR-003's INT −2 is not Sleeper's or Allan's
league's value.**
The real NFL league's `scoring_settings` fixture has `pass_int: -1, fum_lost: -2`.
FR-003's −2 matches nflverse's own PPR column, which is why R2 hit 100%, but the
spec calls these "common PPR defaults". Ask Allan whether "full PPR" means
nflverse's −2 or Sleeper's −1. Whichever wins, label the value as chosen, and keep
SC-004's cross-check by recomputing with the source's value if they differ.

**F14 (LOW, measured). Rookies: R7/O1, with numbers.**
On the 2001 board, 207 of 533 candidates have no qualifying prior line, including
LaDainian Tomlinson (269 PPR) and Tom Brady (163). 5 of 2001's top-60 finishers are
in that tail. Bots only see the first `candidatePool` = 30 available by rank
(`PickDecider:99-106`), and the 180th pick never reaches rank 326. So **no bot ever
drafts a rookie**, and the user can always take the best one in round 15. That's
stronger than R7's "bots' last rounds". Put this measurement in O1 for Allan.

**F15 (LOW, read). The SC-002 text omits 2022.**
SC-002 says "272 from 2021". R4's config adds `2022: 271` (cancelled Bills–Bengals).
Amend SC-002 to name the 2022 exception, so the check isn't read as a failure.

**F16 (LOW, read). Per-game rounding hides rare scored stats.**
`HistoricalBoardBuilder.prior:140` rounds per-game numbers to 1 decimal, and the
contract says the web hides zero keys. `pass_2pt`, `rush_2pt`, `rec_2pt`, `st_td`
and often `fum_lost` round to 0.0 per game for almost everyone, so a card can't
show a player's two return TDs. Show these as season totals, or keep at least
`fum_lost` and `st_td` visible when the total is nonzero.

## Checked and fine

- No K/DEF misbehaviour (see the table). The empty-slot need of 1.0 applies equally
  to QB/RB/WR/TE/FLEX, so TEs still get drafted even though only 1 TE is in the
  2001 top 60.
- `chooseAuto` (`HistoricalGameService:327-352`) works unchanged with FootballRules:
  a starter if one is needed, else the best draftable.
- Players traded mid-season (6 / 23 / 26 skill players in 2000 / 2001 / 2021): one
  row per week, all counted, and `team` = last row is well defined.
- NBA arithmetic under the `StatLine` move: `GameScoringService.score:42` reads
  `Number.doubleValue()`, so an Integer→Double return type changes no value.
  `scoring` is a TreeMap, so the summation order is unchanged.
- Hindsight cost: about 530 candidates × 15 picks × 17–18 weeks, which is the same
  order as NBA (about 450 × 14 × about 24). The "smaller than NBA" claim is roughly
  right. Not timed.
- `weekStarts` empty for NFL → `SeasonReplay.monday(null)` = null (`:293-295`). The
  web week picker already handles null (`HistoricalResults.tsx:131`).
- `api.ts` already types `startDate`/`gameDate` as `string | null` (`:4417, :4427`).
  Only `sport`, `dataNotes` and `attribution` need adding.
- Nothing in the plan presents a hand-set number as fitted. The bot-ranking caveat
  is F6.

## Summary

| # | Severity | Kind | One line |
|---|---|---|---|
| F1 | HIGH | measured | 053 is merged to main (and likely deployed). Rebase, deploy web first, SC-008 covers real games |
| F2 | HIGH | measured | V45 drops the season PK before the FK that depends on it. Postgres refuses. Order given |
| F3 | HIGH | measured | `upsertSeason` ON CONFLICT breaks the NBA import at V45. The summaries join duplicates rows. find/offered are sport-blind |
| F4 | HIGH | read | `l.date().toString()` NPEs on every NFL starter. Web shows "did not play" for all of them |
| F5 | HIGH | measured | SC-005 can't pass: Sleeper `pts_ppr` ≠ FR-003 (53% within 1 pt on Sleeper's own stats) |
| F6 | MEDIUM | measured + suspected | QB-heavy raw-points board, so bots hoard QBs. [WHAT] O3 for Allan |
| F7 | MEDIUM | measured | The "no player id" rows are team-total rows. The data note would mislead |
| F8 | MEDIUM | read | `sport default 'NBA'` left in place silently mis-tags inserts. Drop the defaults after backfill |
| F9 | MEDIUM | read | `readSnapshot` NPEs on pre-V45 NBA boards if `dataNotes` is read like `excludedStats` |
| F10 | MEDIUM | read | About 10 more NBA-assuming sites than the plan's six |
| F11 | LOW | suspected | RestClient vs GitHub's redirect. Reuse 053's HttpClient |
| F12 | LOW | read | `Map.of` order makes FLEX ties and JSON order vary by JVM |
| F13 | LOW | measured | INT −2 vs Sleeper's and the real league's −1. [WHAT] ask Allan |
| F14 | LOW | measured | No bot ever drafts a rookie (207/533 unranked in 2001). Feeds O1 |
| F15 | LOW | read | SC-002 lacks the 2022 = 271 exception |
| F16 | LOW | read | 1-decimal per-game rounding hides 2-pt/return TDs on cards |

Counts: **5 HIGH, 5 MEDIUM, 6 LOW.** For Allan: F6 (bot ranking), F13 (INT value),
F14/O1 (rookies). F1's deploy order is a process point, not a scope change.
