# Research: Historical Draft Game (spec 053)

Every number here was measured on 2026-11-02 unless marked **(assumed)**. The
measuring scripts were throwaway Python in the session scratchpad. They are not in
the repo, and their results are copied here.

## R1. Historical box scores: source, depth, licence

**Decision**: Use the Kaggle dataset `loganlauton/nba-players-and-team-data`,
version 2 (CC0 per Kaggle's dataset listing). The zip SHA-256 is
`7a127548ce7143e85540ea899c93bada25a5944344d7adb4cfc89645ef3d46a4`.
It contains both files we need:
- `NBA Player Box Score Stats(1950 - 2022).csv`. Byte-identical to the separate
  `nba-player-box-score-data` set: 158 MB, about 1.4M rows, regular season only
  (every `Game_ID` starts `002`).
- `NBA Player Stats(1950 - 2022).csv`. 28,237 season rows with `Pos`, `Age`
  (never blank) and `Tm`, including `TOT` rows for traded players.

The zip downloads anonymously. `GET https://www.kaggle.com/api/v1/datasets/download/loganlauton/nba-players-and-team-data`
redirects to a signed storage URL.

**Game counts**, grouped by the season code inside `Game_ID`, match the real
schedule exactly: 1970-71 = 697, 1975-76 = 738, 1990-91 = 1107, 1998-99 = 725,
2010-11 = 1230, 2011-12 = 990, 2019-20 = 1059, 2020-21 = 1080. 1980-81 has 943
games. My expected figure of 902 was wrong (Dallas made it 23 teams), and the data
was right. 2022-23 has 1196 of 1230 games because the snapshot is from April 2023.
That season comes from Sleeper anyway (R3).

**Provenance caveat (not hidden)**: the season-stats file's columns (`Tm`, `TOT`,
`eFG%`) are Basketball-Reference's layout. The box scores came through `nba_api`
(NBA.com). The uploader licensed the dataset CC0. We rely on that licence. Allan
should know the upstream provenance before shipping. See plan "Risks".

**Alternatives rejected**: bulk-copying Basketball-Reference (its terms forbid
copying a materially significant portion); `deocheng/nba-data-1946-2026` (labelled
Apache 2.0, but its own description names Basketball-Reference as the primary
source); NBA.com's stats API directly (not probed: the attempt spoofed browser
headers and the session's permission check blocked it).

## R2. Which stats exist when (the era rule)

Share of player-game rows carrying each stat, by NBA season:

| Season | REB | AST | STL | BLK | 3PM | TOV |
|---|---|---|---|---|---|---|
| 1970-71 | 96% | 94% | 0 | 0 | 0 | 0 |
| 1973-74 | 91% | 90% | 27% | 30% | 0 | 0 |
| 1977-78 | 99% | 98% | 67% | 71% | 0 | 50% |
| 1979-80 | 98% | 98% | 71% | 81% | 99% | 65% |
| 1981-82 | 98% | 98% | 54% | 75% | 99% | 56% |
| 1982-83 | 99% | 99% | 82% | 91% | 99% | 81% |
| 1983-84 → | ~99–100% for all |

**Decision**: a scored stat counts in a season only if at least 95% of that
season's rows carry it. The threshold is hand-set and labelled as such in config.
Some rebounds and assists fall below 95% (1971-72 has 92%), so those seasons would
be scored on **points only**. That is honest but bleak. The plan's adversarial
review should look at the per-season outcome table this rule produces before it is
locked; see Open item O2.

**Derived stats** (Sleeper scores them, but the box scores don't carry them):
- `dd` and `td` are computed only when all five of pts/reb/ast/stl/blk count that
  season; otherwise they are excluded.
- `bonus_pt_40p` / `bonus_pt_50p` need pts.
- `bonus_reb_20p` needs reb.
- `bonus_ast_15p` needs ast.
- `tf` / `ff` (technical and flagrant fouls) are not in the dataset at all, so they
  are excluded for every Kaggle season.
- A 50-point game earns both `bonus_pt_40p` and `bonus_pt_50p`. Measured on a
  Sleeper line with pts=100: both are 1.0.

**The rule does not apply to Sleeper seasons.** Sleeper omits zero-valued stat
keys (recorded in project memory for spec 043), so a missing key there means 0,
not "unrecorded". Every scoring key counts in Sleeper-sourced seasons.

### R2 amended after review (2026-11-02): the final rule and its outcome

The final rule (spec FR-007, plan F7 + O2): a stat counts if the league tracked it
that season, meaning the season file has it, **and** it is present in at least 90%
of *played* box-score rows. A counted stat missing from a single box score scores 0,
and the screen shows its coverage. Measured outcome:

| Seasons | Counted | Notable exclusions (coverage) |
|---|---|---|
| 1970-71 … 1972-73 | pts reb ast (reb 93.0–97.1%, ast 92.8–94.6%) | stl/blk/to/3pm untracked |
| 1973-74 … 1978-79 | pts reb ast | stl 27.8→75.0%, blk 30.9→79.2%, to 50.3–65.1% (from 1977-78) |
| 1979-80 … 1981-82 | + 3PM (≥99.6%) | stl 54–72%, blk 76–83%, to 57–68% |
| 1982-83 | + blk (91.6%) | stl 82.8%, to 81.2% |
| 1983-84 → | all | — |

`dd`/`td` still require all five of pts/reb/ast/stl/blk to count, so they first
count in 1983-84. The screen says so.

### R2 amended after code review (2026-11-02): Sleeper bonus keys

The premise "Sleeper omits zeros, so an absent key is 0" is wrong for `bonus_pt_40p/50p`, `bonus_reb_20p` and
`bonus_ast_15p` in Sleeper 2017-18, 2018-19 and 2019-20: those seasons never computed them (333 games of 40+ points,
0 flagged). The importer now derives `dd`, `td` and the four bonuses from the box stats for every Sleeper row, with the
same `KaggleIdentity.derive` the Kaggle path uses (OR-ed with Sleeper's own flag where it sent one). From 2020 on the
derived and Sleeper flags agree exactly.

## R3. Season ownership by source (FR-015)

| Drafted seasons | Box scores | Prior-season cards | Positions | Player identity |
|---|---|---|---|---|
| 1970-71 … 2016-17 | Kaggle box file | Kaggle season file (prior season) | Kaggle `Pos` | name + disambiguator (R5) |
| 2017-18 … 2025-26 | Sleeper `/stats/nba/{y}/{w}` | Sleeper `/v1/stats/nba/regular/{y-1}` | Sleeper `/v1/players/nba` `fantasy_positions` | `sleeper_player_id` |

Measured Sleeper facts:
- Weekly logs exist for 2017–2025 and are empty for 2016 and earlier.
- Season totals with `gp` go back to 2013.
- Labels are **start-year**. Week 1 of `2017` is 2017-10-17..22 and week 1 of
  `2025` is 2025-10-21..26, so `2017` means 2017-18.
- The Kaggle season file is **end-year** (`Season=1970` is 1969-70: Kareem, MIL,
  82 G, 2,361 pts). The Kaggle box file's `Season` column is the **calendar year**.
  The importer must use `Game_ID` characters 4–5 instead (`0024600034` → 46 →
  1946-47).

**Amended after code review (2026-11-02):** Sleeper season totals are imported from 2014 (not 2016) as card/ranking
lines only (`/v1/stats/nba/regular/2014` and `/2015` answer: 494 and 481 players), so a 2017-18 board's 3-season
window (2014, 2015, 2016) is full within one source. No `historical_season` row exists for 2014-2016 Sleeper.

The 2016-17 / 2017-18 seam needs no cross-source identity join: a 2017-18 game
takes its cards from Sleeper's 2016 totals, which exist. Sleeper has
`fantasy_positions` for **every** non-team player with games played in 2016–2025.
The only gaps are 30 per season from 2019 on, which are the 30 `TEAM_*`
pseudo-players. They are filtered out.

"Most recent complete season" is 2025-26 today. The app's NBA 2026 league is the
2026-27 season, now in progress, so it is not offered.

## R4. ADP: no trustworthy historical source, fallback for every season (amends the spec's assumption)

The spec assumed Sleeper `adp_std` (present for 2019–2025, 550–700 players) is
real preseason ADP, and asked the plan to confirm it. **It could not be confirmed,
and the evidence points the other way:**

- Spearman rank correlation of the ADP top 150 with fantasy points (core
  scoring):

  | Year | vs prior season | vs same season |
  |---|---|---|
  | 2019 | 0.647 | 0.677 |
  | 2020 | 0.663 | 0.599 |
  | 2021 | 0.695 | 0.584 |
  | 2022 | 0.597 | 0.628 |
  | 2023 | 0.650 | 0.639 |
  | 2024 | 0.683 | 0.487 |
  | 2025 | 0.684 | 0.470 |

  In two of seven seasons, ADP tracks the season being drafted more closely than
  the season before it.
- A head-to-head comparison against Yahoo's 2019-20 public ADP (game key 395,
  `average_pick`), rho = 0.774 over 200 players. The disagreements lean toward
  hindsight. Each pair below is Sleeper vs Yahoo:
  - Luka Dončić went 4.0 vs 17.8, then finished top-5.
  - Ja Morant went 42 vs 68.4, then won Rookie of the Year.
  - Kemba Walker went 45 vs 18.4, then declined.

  That is the pattern an ADP that includes in-season drafts would produce. It is
  one season of evidence, not proof, but it is enough not to label `adp_std` as
  ADP (FR-009).
- Yahoo's public draft analysis answers only for 2019 (key 395) among 2010–2025.
  Every other season's key (249…466, listed by Yahoo's `games` endpoint) returns no
  player data.

**Decision**: every season in v1 uses the **fallback ranking** (R6), labelled
"ranked by last season's points". Real ADP becomes a later enhancement when a
verified preseason source exists, such as Yahoo 2019 alone or crowd ADP from this
game. The spec already covers this case ("If it is contaminated, those seasons use
the fallback"), so no spec requirement changes. The spec's Assumptions line is
amended to record the result.

## R5. Player identity in the Kaggle era (FR-014)

- Box-score names joined to season-file names, by (season, name), 1969-70 to
  2016-17: **1,014 of 17,878 player-seasons (5.7%) are unmatched.** The cause is
  spelling variants, e.g. "Alvin Attles" vs "Al Attles", "Jojo White" vs "Jo Jo
  White", "Mccoy McLemore". By decade: 1970s 160 of 2,371, 1980s 146 of 3,182,
  1990s 114 of 4,086, 2000s 299 of 4,464, 2010s 295 of 3,775.
- **Same-name collisions** in the season file are season rows with more than one
  team and no `TOT` row, meaning two different people: **23** cases. Examples:
  George Johnson in 1973, 1974, 1979–83 (three different players), and Eddie
  Johnson in 1982–84.

**Decision**:
1. Normalise names: NFKD, strip accents and punctuation, lower-case, collapse
   whitespace.
2. Then match on (season, team, normalised name) first and (season, normalised
   name) second.
3. Then apply a checked-in alias file, `historical-name-aliases.csv`, for what
   remains.
4. The import **fails loudly** if a fantasy-relevant player-season is still
   unmatched. Fantasy-relevant means within the top `teams × rounds × 1.5` by
   fantasy points in either the box or the season data. The failure lists the
   names, so the alias file grows. It never silently drops the player.
5. Collisions: identity is (normalised name, disambiguator). The disambiguator
   comes from a checked-in `historical-same-name.csv` that maps (season, team,
   name) to a person key, e.g. `george-johnson-1` / `-2` / `-3`. The import detects
   collisions with the no-`TOT` multi-team test and **fails** on any collision the
   file doesn't cover.

SC-007's "known same-name pairs" list is this file.

## R6. Fallback ranking

The board order is the prior-season **total** fantasy points under the drafted
season's era scoring, which is per-game × games, as the spec says. Players with no
prior NBA season (rookies, and players who sat out a whole season) rank after
everyone with a prior line, in seeded random order, since the dataset has no NBA
draft position. The card says "no NBA stats last season".

This rank is fed to the engine as `BoardEntry.adp`. `PickScorer.java:104` scores
bots around it and `BasketballRules.value` (`sport/BasketballRules.java:120`) decays
from it, so bot behaviour is unchanged from the normal mock room.

**Known leak (documented, accepted)**: the board holds the players who played at
least one game in the drafted season. A player who missed the entire season is
therefore absent. A real drafter usually knew that (e.g. Kevin Durant in 2019-20),
but a season-long injury that happened after draft day leaks. There is no source
of preseason rosters to do better.

## R7. Scoring values ("Sleeper default lock-in settings")

Sleeper's API exposes league settings, not product defaults. Both of Allan's NBA
leagues were read on 2026-11-02:

| key | Legacy Ball Knowers (dynasty, 10 teams) | Ball Knowers (12 teams) |
|---|---|---|
| pts / reb / ast / stl / blk / to / tpm | 0.5 / 1 / 1 / 2 / 2 / −1 / 0.5 | same |
| dd / td | 1 / 2 | 2 / 3 |
| bonus_pt_40p / 50p | 2 / 2 | 2 / 2 |
| bonus_reb_20p / bonus_ast_15p | 1 / 1 | 2 / 2 |
| tf / ff | −2 / −2 | −2 / −2 |

**Decision (provisional; Allan to confirm, plan "Open items" O1)**: use the
Legacy values. **(assumed)** They are Sleeper's untouched defaults, since a
dynasty league's bonuses are the least likely to have been customised. The values
live in config, so changing them is one edit.

Roster: 12 teams × 14 rounds, with `LeagueShape.NBA_ROSTER`
(`engine/LeagueShape.java:62`): PG SG G SF PF F C UTIL UTIL + 5 BN. That is the
app's existing NBA standard and matches the 12-team league. Lock-in mode is not
distinguishable in the settings payload. `settings.type` is 2 vs 0, which is
dynasty vs redraft, not lock-in.

## R8. Weeks and the 2019-20 cut

Weeks run Monday to Sunday, starting with the week that contains opening night.
They are bucketed from game dates for **both** sources, so Kaggle and Sleeper
seasons share one rule. Sleeper's own `week` field is ignored here: its own week 1
runs Tuesday–Sunday, so it is close but not the same. 2019-20 keeps games dated on
or before 2020-03-11 (FR-010).

A player's weekly score is the **max** single-game score that week (lock-in best
ball). A week with no games scores 0, not "absent".

## R9. Reuse found in the code (constitution rule 6: check the code, not the plan's framing)

- **Draft engine**: `MockDraftEngine.advanceUntilUserOrEnd`
  (`engine/MockDraftEngine.java:59`) and `DraftContextFactory.build`
  (`engine/DraftContextFactory.java:56`) are pure functions of a
  `List<BoardEntry>` and `LeagueSettings`. They work as-is on a synthetic board.
  Bots get `ManagerProfile.neutral` (no seats are manager seats) and
  `PositionalPriors.uniform(NBA)` (`profile/PositionalPriors.java:114`).
  *Not* the fitted priors: those describe Allan's league in 2024–26, not drafters
  in 1985.
- **Session storage cannot be reused**: `mock_draft_pick.player_id` has a foreign
  key to `player(id)` (`db/migration/V3__mock_draft.sql`), and historical players
  are not in `player`. This feature gets its own tables (data-model.md).
- **Best lineup**: `RealizedLineupService.bestLineup`
  (`engine/RealizedLineupService.java:65`) takes a Sleeper `players_points` JSON
  keyed by sleeper id. Its core (rank, `RosterState`, `rules.startingLineup`) is
  extracted into an overload that takes `(Map<Player, Double>, settings, rules)`.
  The JSON method delegates to it, so there is one lineup implementation and not
  two.
- **Game scoring**: `GameScoringService.score` (`engine/GameScoringService.java:42`)
  is used unchanged. Era scoring is a filtered scoring map, not a second scorer.
- **Room UI**: `web/src/pages/MockDraftView.tsx` (340 lines) is composed from
  `DraftBoard`, `OnTheClockPickInput`, `DraftRoomLayout`, `TurnIndicator`,
  `AvailabilityPanel` and `FormatSummary`. The historical room composes the same
  components over its own state. It is not a fork of `MockDraftView`.

## Open items handed to the adversarial review

- **O1**: which scoring set is "Sleeper default"? (R7). Needs Allan.
- **O2**: what the 95% rule does to the early 1970s. Measured over rows where the
  player actually played (minutes or points > 0):

  | Season | REB | AST |
  |---|---|---|
  | 1969-70 | 96.2% | 92.7% |
  | 1970-71 | 97.1% | 94.6% |
  | 1971-72 | 93.0% | 92.8% |
  | 1972-73 | 94.3% | 92.8% |
  | 1973-74 | 92.4% | 91.9% |
  | 1974-75 | 96.0% | 95.3% |

  From 1975-76 on, both are ≥98%. So **1970-71 through 1973-74 would score
  points + rebounds, or points only, under a strict 95% rule.** The gaps are mostly
  whole box scores with no assists (17–39 games a season) plus about 30 partial
  ones, so they are not concentrated on particular players. Options for the
  review: (a) keep 95% and accept thin early scoring; (b) drop the bar to 90% and
  treat an unrecorded stat in an otherwise-recorded season as 0, saying so on
  screen; (c) start the offered range at 1974-75. This is a scoring-fairness call,
  not a data question.
- **O3**: production DB footprint for about 1M Kaggle rows plus about 0.6M Sleeper
  rows. **(assumed)** a few hundred MB. Measure on the local load before deploying.
