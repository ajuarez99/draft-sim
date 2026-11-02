# Research notes (spec 053 build)

## T001: migration number re-check (2026-11-02)

`git fetch`, then every local and remote branch's `backend/src/main/resources/db/migration`
listed for `V4*__`. The highest is **V42** (`V42__sport_trending_snapshot.sql`); no ref
carries a V43. **V43 is free.** Re-check again immediately before the migration is
applied elsewhere.

## T019: local import and curation (2026-11-02)

Run against the scratch Postgres on 5438 (`draftsim-pg-053`), backend on port 8153, through the real async
route (`POST /api/admin/historical/import`, then polling `GET .../import/{jobId}`). **Verified** = executed and
read back from the report or the database. **Assumed** = said so.

### Result

- Final run (`part: ALL`, job 16): **58 seasons committed, 0 refused**: Kaggle 1969..2016 (1969 is card lines only,
  not offered) and Sleeper 2016 (card lines only) plus 2017..2025. `unmatchedFantasyRelevant` = 0,
  `uncoveredCollisions` = 0 (COLLISION, AGE_JUMP and UNKEYED all empty), `emptyPositions` = 0, for every season.
- `GET /api/history/seasons` returns 56 offered seasons, 1970-71 to 2025-26.
- The https path was run once end to end (job 15, `kaggle.com/api/v1/datasets/download/...?datasetVersionNumber=2`,
  anonymous, redirect followed): the SHA-256 of the download matched the configured
  `7a127548...3ef46a4`. The curation runs used a `file:` URL for the same zip; the SHA check runs either way.
- **Wall time** (whole job, same machine, Postgres in Docker): ALL over https 131.7 s; ALL over `file:` 126.7 s;
  Kaggle part alone 109.5 s; Sleeper part alone 87.6 s (270 weekly calls + 10 totals calls + `/players/nba`).
  The very first run, where every season was still refused before writing, took 14.6 s in total, which bounds
  phase 1 (hash, COPY of 1,006,146 box rows into staging, parse of 21,777 season-file rows); the rest is the
  per-season writes. So F14's "async" call is right (over two minutes is far past a proxy timeout). Not measured on
  Railway.
- **DB size of the `historical_*` tables.** The task's query (`sum(pg_total_relation_size(oid))` over every relation
  named `historical_%`) says **219 MB after `VACUUM FULL`**, but it counts each index twice (once inside its
  table's total, once as its own relation). The true on-disk figure, summing the tables only
  (`relkind = 'r'`, indexes and TOAST included), is **173 MB** (heap 127 MB, indexes 46 MB);
  `historical_player_game` is 168 MB of the 219 for 1,218,633 rows. Before the vacuum the same query read 258 MB
  after the first import and 397 MB after re-importing (the per-season replace is delete + insert, so dead tuples
  pile up): **a re-import in prod should be followed by a vacuum**, and the worst case to plan the Railway volume
  around is the 397 MB the unvacuumed query showed (about 290 MB true). Rows: 4,804 players (3,320 Kaggle,
  1,484 Sleeper), 23,427 player-season lines. (O3: now measured, locally; Railway's volume not checked.)
- Box coverage against the season file (rows imported / sum of G in the season-file lines): 98.6% to 100.4% in
  every Kaggle season, so there is no season with a large hole.

### Game counts per season (SC-002), distinct game ids

Kaggle (start year: games): 1970:697 1971:697 1972:697 1973:697 1974:738 1975:738 1976:902 1977:902 1978:902
1979:902 1980:943 1981:943 1982:943 1983:943 1984:943 1985:943 1986:943 1987:943 1988:1025 1989:1107 1990:1107
1991:1107 1992:1107 1993:1107 1994:1107 1995:1189 1996:1189 1997:1189 1998:725 1999:1189 2000:1189 2001:1189
2002:1189 2003:1189 2004..2010:1230 2011:990 2012:1229 2013..2016:1230.

The six the task names, **all as expected**: 1970-71 = 697, 1975-76 = 738, 1990-91 = 1107, 1998-99 = 725,
2010-11 = 1230, 2011-12 = 990. (1976-77 to 1978-79 are 902 for 22 teams and 1980-81 is 943, matching R1.
2012-13 is 1229: the real season was one game short after the Boston postponement.)

Sleeper: 2017:1232 2018:1232 2019:973 (cut at 2020-03-11) 2020:1081 2021:1234 2022:1231 2023:1232 2024:1232
2025:1232. **Unexplained and not investigated:** full Sleeper seasons carry 1 to 4 more distinct game ids than the
real 1230 (checked for 2023: on every date, distinct game ids x 2 = distinct teams, so these are genuine
extra ids, not a double-counted game). Weekly data: first non-empty week 1 and last non-empty week 26
(2017, 2018), 21 (2019 and 2020), 25 (2021 to 2025). Sleeper `game_id` is a string like `20231120_CHI_MIA` for
2017 to 2024 (hashed to a stable 63-bit id, `gameIdsHashed: true` in the report) and numeric for 2025.
Another measured oddity, **not fixed**: in 2019 there are 27 game ids with more than two distinct `team` values,
so Sleeper's `team` on a weekly entry looks like the player's team at some other moment for traded players.

### Counted stats, 1970-1984, against research R2 (amended): no difference

Share of played box rows carrying each stat (reb / ast / stl / blk / to / tpm), as measured by the import:

| start | reb | ast | stl | blk | to | tpm | counted |
|---|---|---|---|---|---|---|---|
| 1970 | 97.1 | 94.6 | 0 | 0 | 0 | 0 | pts reb ast |
| 1971 | 93.0 | 92.8 | 0 | 0 | 0 | 0 | pts reb ast |
| 1972 | 94.3 | 92.8 | 0 | 0 | 0 | 0 | pts reb ast |
| 1973 | 92.4 | 91.9 | 27.8 | 30.9 | 0 | 0 | pts reb ast |
| 1974 | 96.0 | 95.3 | 50.1 | 52.4 | 0 | 0 | pts reb ast |
| 1975 | 98.8 | 98.7 | 61.5 | 63.3 | 0 | 0 | pts reb ast |
| 1976 | 99.2 | 98.4 | 64.0 | 67.5 | 0 | 0 | pts reb ast |
| 1977 | 99.6 | 98.4 | 67.4 | 71.8 | 50.3 | 0 | pts reb ast |
| 1978 | 98.8 | 98.2 | 75.0 | 79.2 | 65.1 | 0 | pts reb ast |
| 1979 | 98.9 | 98.4 | 71.9 | 81.8 | 65.3 | 99.6 | pts reb ast tpm |
| 1980 | 99.5 | 98.4 | 68.3 | 83.2 | 68.1 | 99.8 | pts reb ast tpm |
| 1981 | 99.0 | 99.0 | 54.4 | 75.9 | 57.0 | 99.9 | pts reb ast tpm |
| 1982 | 99.4 | 99.6 | 82.8 | 91.6 | 81.2 | 99.6 | pts reb ast blk tpm |
| 1983 | 100 | 100 | 99.8 | 100 | 99.7 | 100 | everything, dd/td included |
| 1984 | 98.9 | 98.8 | 98.5 | 98.8 | 98.5 | 99.8 | everything, dd/td included |

These match R2's amended table cell for cell (its ranges 92.8-94.6 ast, 93.0-97.1 reb, stl 27.8 to 75.0, blk 30.9
to 79.2, to 50.3 to 65.1 from 1977-78, tpm at least 99.6, 1982-83 adding blk at 91.6, dd/td first counting in
1983-84). The per-season "tracked" input (season file has the stat for more than half its rows) reproduces the
"not tracked by the league that season" reasons for stl/blk before 1973-74, `to` before 1977-78 and tpm before
1979-80.

### Curation (what the three CSVs now hold)

- `team-codes.csv`: the 7 seeds plus **WAS to WSB, CHA to CHO, KCK to KCO, NOK to NOH**, found from the report's
  `teamCodeMismatches`. The match accepts the box code verbatim OR through the crosswalk, because the crosswalk is
  right for some seasons and wrong for others (box WAS is season-file WSB in the 1970s and plain WAS from 1998;
  box CHA is season-file CHO from 2015 but CHA for the Bobcats).
- `historical-name-aliases.csv`: **416 rows** (season_end, box name, season-file name). The first run refused with
  282 unmatched fantasy-relevant name-seasons; I aliased all 416 unmatched name-seasons, relevant or not, so the
  "skip a non-relevant unmatched player" path is never used (`skippedUnmatchedPlayerSeasons` = 0 in every
  season). In the early seasons the cut `teams x rounds x 1.5 = 252` is larger than the whole pool (217
  player-seasons in 1970-71, 171 in 1969-70), so there every unmatched name is fantasy-relevant by definition.
  Rule applied (evidence, never name alone): the box name has no season-file row that season; exactly one
  not-yet-matched season-file player has a similar name (similarity at least 0.45, then read by eye), a team equal
  to all of the box rows' teams (after the crosswalk) and a season-file G within 3 of the box played-game count.
  409 rows passed that rule automatically, and each of the 80 with name similarity under 0.75 was read against its
  team, G and age in the neighbouring seasons (all nicknames or legal-name variants: Tiny Archibald for Nate,
  Double D Davis = Dwight Davis, Mo Layton = Dennis Layton, Pooh Jeter = Eugene Jeter, Bubbles Hawkins =
  Robert Hawkins, World Free = World B. Free, Ike Austin = Isaac Austin, ...). **7 were decided by hand** because
  of a games gap or a second plausible candidate: Thomas Barr = Moe Barr (CIN 1970, 27 box vs G 31), Emmette Bryant =
  Em Bryant (BUF 1971, 50 vs 54), Edward Davis = Mickey Davis (MIL 1972, 70 vs 74), John Clemens = Barry Clemens
  (CLE 1973, 68 vs 71; the other CLE candidate John Warren matches Johnny Warren instead), Johnny Warren = John
  Warren (CLE 1973, 59 vs 69, a 10-game gap), Peter Verhoeven = Pete Verhoeven (POR 1981), Roger Mason Jr. =
  Roger Mason (WAS 2011, 48 vs 52). **Assumed, not proven:** the 1973 Johnny Warren pairing rests on name,
  team and uniqueness only (the game counts differ by 10).
- `historical-same-name.csv`: **363 rows** covering every season row of 26 names (the 23 no-TOT collisions and
  the 25 age-continuity violations the import reported, which are 26 distinct names): Bobby Jones, Cedric Henderson, Charles Jones, Charles Smith, Chris Johnson, Chris Smith, Dee Brown, Eddie Johnson, George
  Johnson, Gerald Henderson, Greg Smith, Jeff Taylor, Ken Johnson, Larry Johnson, Luke Jackson, Marcus Williams, Mark
  Davis, Mark Jones, Michael Smith, Mike Davis, Mike Dunleavy, Patrick Ewing, Reggie Williams, Sam Williams, Tony
  Mitchell, Walker Russell. Each row
  was assigned to a person by age continuity (a person in season S and S+k is k-1..k+1 years older), with team
  continuity breaking ties between two age-feasible people, and the chains were printed and read. **Assumed, not
  proven:** that two people are never merged by that rule when both are age-feasible and on the same team; the one
  such case found is below.

### Left as data gaps, on purpose (listed, not forced)

1. **Charles Jones, 1988-89, both on Washington**: a 31-year-old C (53 G) and a 27-year-old PF (43 G). The box file
   carries no player id, so their 96 played box rows cannot be told apart. They are keyed apart for their season
   lines (so their cards are right), but the 96 game rows are **left off the board** (`ambiguousSameTeamRowsDropped`
   in the report). The same-name file accepts `TEAM:age` (`WSB:31`) for exactly this case.
2. **Box rows on a team the season file never lists for that name**, dropped rather than merged (merging gave Greg
   Smith 1973-74 143 box games on a 67-game line). Season start years: Greg Smith HOU 1973 (76 rows; the season file
   has him on POR only, so a Houston Greg Smith is absent from the season file), Johnny Davis LAL 1978 (5) and UTH
   1979 (12), Michael Cooper SDC (10) and WAS (4) 1982, Sarunas Marciulionis CLE 1996 (1): 108 rows in all. Whether any of these is the same person on a team the season file omits is not known.
3. **The box file has no rows for Loy Vaught in 1990-91 to 1995-96 (start years 1990 to 1995), nor Ed O'Bannon in 1995-96** although the season
   file lists them; they are absent from those boards (`relevantInSeasonFileWithoutBoxRows`). Only these two
   names (plus the Charles Jones case) appear in that list across 48 seasons. This is a hole in the source.
4. **Box games above the season file's G** (informational list in the report, 24 players over the 12 seasons
   1974-75 to 1985-86, the worst Dwight Jones 1979-80 with 122 box games against G 74 and Robin Jones 1977-78,
   33 against 12). For the ones
   checked (Dwight Jones: HOU 69 box rows against 21 in the season file, CHI matches at 53) the extra rows are on
   one of the player's teams, so the source mislabels or duplicates some rows; not resolved. The lock-in score
   takes each week's best game, so a spurious extra game can only matter if it falls in a week the player
   did not play: not measured.
5. **No Kaggle player-season line without a position**: 0 of 21,777 rows (1969-70 to 2016-17) had an empty `Pos`.

### Docs found wrong or incomplete while building this

- `tasks.md` T010 and plan "Technical Context" say pgjdbc is "already on the classpath". It was `runtimeOnly`;
  `CopyManager` needs it at compile time, so `build.gradle.kts` now says `implementation` (same artifact, no new
  dependency).
- `tasks.md` T019 says "the local 5433 DB"; this run used the scratch 5438 instance as instructed.
- `tasks.md` T003 / the same-name file's `season_end,team,name,person_key` cannot key two people of one name on the
  same team in one season (Charles Jones, WSB, 1988-89); the `team` field now also accepts `TEAM:age`.
- `plan.md` and R5 define fantasy-relevant as the top 252 by points. For the early seasons that is more than the
  whole pool (217 player-seasons in 1970-71), so "non-relevant unmatched names are skipped" cannot apply there:
  every unmatched name has to be curated.
- R5's "1,014 of 17,878 (5.7%) unmatched" counted exact-name misses; after NFKD/accent/punctuation normalisation
  282 distinct names (416 name-seasons) remained, plus the same-name curation.
- R5 and the review (F2) say 23 no-TOT collisions and 51 age-jump names. The import's own consecutive-pair check on
  person keys reported 23 COLLISION and 25 AGE_JUMP (26 distinct names together); I did not reproduce the 51.
- `contracts/api.md` and tasks T013 say 401/403 without the token. The route sits under `/api/admin/**`, whose
  interceptor answers **403** (`admin_token_required`) for a missing or wrong token; there is no 401.
- `tasks.md` T012 says `GET /v1/stats/nba/regular/{season}`. `SleeperPlayerStatsClient` is rooted at
  `https://api.sleeper.app` (not `/v1`), so the method adds the `/v1` itself.
- data-model.md says Sleeper age is null; per F19 and T012 it is computed from `birth_date` at Oct 1 of the season
  start (null only when Sleeper has no birth date). Sleeper lines have `team` only where a weekly entry gave one,
  so 2016 card lines (totals only) have team null.

## T015-T018, T021-T022: board, game flow, replay, comparisons (2026-11-02)

**Verified** = executed. Run against the scratch Postgres on 5438 (`DB_URL=jdbc:postgresql://localhost:5438/draftsim`).

### SC-001 timing (T029's log lines are in `HistoricalGameService`, INFO)

`HistoricalRealSeasonIT` creates a game on a real imported season at slot 6, auto-FINISHes it, and asserts a
COMPLETE result with 12 standings rows, 168 picks and `week_count` replayed weeks. Same machine, Postgres in Docker,
warm JVM after the first season, each figure from two runs (the second run's number is the right-hand one):

| Season | Source | create | final call (13 user picks + bot picks + replay + 12-team standings + hindsight) |
|---|---|---|---|
| 1971-72 | KAGGLE | 91 / 98 ms | 252 / 776 ms |
| 1995-96 | KAGGLE | 103 / 123 ms | 445 / 650 ms |
| 2023-24 | SLEEPER | 116 / 97 ms | 520 / 730 ms |

All well under SC-001's 2 s. The spread between the two runs (250 to 780 ms for the same season) is noise from a
loaded machine, not a difference between seasons. Not measured on Railway or under concurrent users.

### One real finished game (1995-96, user slot 6; second run)

Standings (place, slot, W-L-T, points for): 1 s5 18-7-0 5967.5; 2 s4 16-9-0 6095.0; 3 s12 15-9-1 5966.5;
4 s10 15-10-0 5582.0; 5 s8 14-10-1 5759.5; **6 s6 (user) 14-11-0 5812.0**; 7 s3 12-13-0; 8 s1 11-14-0;
9 s2 10-15-0; 10 s9 10-15-0; 11 s11 10-15-0; 12 s7 4-21-0.
Hindsight (greedy, bots unchanged) 7684.5 vs the user's 5812.0, `userBeatIt` false. Bust: pick 19, Chris Webber,
finished 151st of 168 drafted. Steal passed on: at pick 91 (took Reggie Williams, 209.5) Michael Jordan (944.5) was
available. Jordan is the ranking's known blind spot working as designed: he had 17 games in 1994-95 (below the
20-game floor) and none in 1993-94, so he sits in the unranked tail. 2023-24's equivalent is Wembanyama (a rookie).

The user's auto-draft finished 1st in all three games of the first run and 6th, 4th in the second. Three games say
nothing about whether bots are systematically weaker than the auto-pick rule; that is **not measured**.

### Decisions that go beyond the docs, and docs that were wrong or silent

- **The board snapshot is the whole card, not `{playerId, adp, positionalRank}`.** data-model.md and plan F9 name those
  three fields, but a `BoardEntry` also needs name, positions, team and age, and "rebuild from the snapshot only"
  would then still read the reference tables (and a re-import could change positions under a game in progress).
  `historical_game.board` is therefore the rank-ordered `HistoricalPlayerCard` list, prior line included, so a
  game is fully frozen and every call parses one jsonb value. Cost: about 200 KB per game row for a 600-player
  season; not measured.
- **Out of turn is unreachable through normal play.** After every move the bots are advanced to the user's next turn,
  so a DRAFTING game is always on the user's turn. The 409 exists and is tested by rewinding `current_pick_no` in
  SQL; the HTTP-reachable 409s are taken player, and any move on a COMPLETE or ABANDONED game.
- contracts/api.md puts `bestOfTwo` on the card's `prior` (amendment box) but says the card shows "the prior line".
  Built as: `prior` is the line the RANKING used (the better of S-1 and S-2 with at least 20 games), labelled with its
  season; with no qualifying season it is the S-1 line if there is one (bestOfTwo null), else null. Team and age are
  always S-1's. Positions: the first existing line of S-1, S-2, drafted season. An existing line with an empty
  position set fails the game creation.
- `seasonValueRank` (bust) is the rank among the 168 DRAFTED players, which is what makes it comparable with an
  overall pick number. data-model.md says "among all drafted players"; the contract just says "rank".
- v1 stores only the user's lineups in `result.lineups` (the contract example shows one key). All 12 would be a few
  hundred KB per game.
- `HistoricalProperties.scoring` is `Map.copyOf` (iteration order changes between JVM runs). The game's scoring
  snapshot is stored with keys sorted, and the replay reads it back from the stored jsonb, so the summation order,
  and therefore the cents-rounded totals, cannot depend on the hash seed.
- Hindsight uses branch and bound (candidates ordered by the sum of their positive weekly scores, stop when no
  remaining candidate can beat the best found). It is exact assuming adding a player never raises a week by more than
  his own score; the bound carries 1 cent of slack per week for rounding. Not checked against a brute force.
- `HistoricalGameService.chooseAuto` is a second copy of `MockDraftService.chooseAuto` minus targets (that method is
  package-private to the mock package). Two copies of one rule is this repo's recurring bug class: worth extracting
  if either changes.
- `HistoricalGameService.create(user, slot, seasonOverride)` takes any season that is committed with prior-season
  lines, outside 1970..2025 too, for tests and tooling. The HTTP route never passes one.
- Full backend suite against 5438: 2621 tests, 4 failed, 330 skipped. The 4 are not in this feature:
  `PlayerSpotlightTopOfNightIT` and `PlayerSpotlightWeekIT` connect to a hardcoded database that is not 5438, and
  `LeagueRepositorySuccessorIT` (2) needs league rows the scratch DB does not have. Every 053 test ran: 109 tests,
  0 skipped, 0 failed. The other skips are other features' ITs that this DB cannot satisfy.

## SC-005 parity: Kaggle box scores vs imported Sleeper rows, 2018-19 (T026, 2026-11-02)

**Verified** = executed. A throwaway script (`parity.py` in the session scratchpad, outside the repo) summed
counting-stat fantasy points per player for 2018-19 under the Legacy weights (pts 0.5, reb 1, ast 1, stl 2, blk 2,
to -1, tpm 0.5; no dd/td/bonuses, so both sides are comparable) from (a) the local Kaggle CSV, regular-season
games only (`Game_ID` starts `00218`, 1,230 games, 26,101 rows, rows with 0 minutes and 0 pts/reb/ast dropped) and
(b) `historical_player_game` for `season_start = 2018, source = 'SLEEPER'` on the 5438 scratch DB. Players matched by
normalised name (lower-cased, accents stripped, punctuation and Jr/Sr/II/III removed). Kaggle 2017+ was **not**
imported anywhere (FR-015).

| | players | matched | within 0.5 pts over the season |
|---|---|---|---|
| Sleeper rows exactly as imported | 530 | 527 | **478 (90.7%)** |
| same, minus the 4 exhibition teams below | 530 | 527 | **523 (99.2%)** |

Total matched points: 350,563.0 (Sleeper, exhibition rows removed) against 350,564.0 (Kaggle). Of the 527, 526 are
within 2 pts and all 527 within 5 once the exhibition rows are gone. Unmatched by name only (same people, different
spellings): Sleeper "Mitch Creek" / Kaggle "Mitchell Creek", "Cameron Reynolds" / "Cam Reynolds", "N Nene"
(encoding-damaged "Nene") / "Nene". Not compared.

### Disagreements

**Finding 1, a real defect in the Sleeper import (NOT fixed, reference data untouched): All-Star weekend games are
imported as ordinary games.** With the rows as imported, 49 players disagree, 47 of them by exactly one extra Sleeper
game: Sleeper carries a game for players such as Shai Gilgeous-Alexander (83 games; 82 is the maximum), Ben Simmons
(81), Giannis, Tatum, Durant, Curry, ... on 2019-02-15 / 2019-02-17 under team codes `USA`, `WLD`, `GNS`, `LBN`
(Rising Stars and the All-Star Game). Top by size as imported: Ben Simmons +47.0, Donovan Mitchell +35.5, Giannis
+35.0, Jayson Tatum +34.0, Trae Young +32.5, Kevin Durant +31.5, Kyle Kuzma +28.0, Klay Thompson +27.0, Damian
Lillard +26.0, De'Aaron Fox +26.0, LeBron James +25.0, Paul George +25.0, Kyrie Irving +25.0, Stephen Curry +24.5,
Luka Doncic +24.0, SGA +24.0, Joel Embiid +21.0, Kawhi Leonard +21.0, Marvin Bagley III +21.0, Khris Middleton +21.0.
This is also the answer to the earlier "unexplained" 1 to 4 extra distinct game ids per Sleeper season: every season
has 2 to 4 exhibition ids (2017 `LBN STP USA WLD`, 2018 `GNS LBN USA WLD`, 2019 `GNS LBN USA WLD`, 2020 `DRT LBN`,
2021 `BAR DRT IAH LBN PAY WOR`, 2022 `GNS LBN`, 2023 `EST WST`, 2024 `CHK SHQ`, 2025 `STP STR`; measured by listing
Sleeper teams with at most 3 distinct dates per season). The replay sums whatever is in the week, so a star can score
one extra game in All-Star week. Fix, for Allan to decide: drop Sleeper weekly rows whose team is not one of the 30 NBA
codes, then re-import the Sleeper part and VACUUM. Not done here because the brief said not to modify reference data.

**Finding 2, after removing the exhibition rows, 4 players still disagree** (all tiny): Boban Marjanovic +2.0 (57
Sleeper games vs 56), Jarred Vanderbilt -1.0 (16 vs 15), Clint Capela -1.0 and Miles Bridges -1.0 (same games, 1
point apart; a box-score correction between NBA.com and Sleeper is the likeliest cause, **assumed, not checked**).

**Game-count differences that do not change points** (61 of 527 after exhibition removal; histogram of Sleeper minus
Kaggle games: 0 for 466, +1 for 50, +2 for 7, +3 for 2, +6 and +8 for one each): the Kaggle side was filtered to
games with minutes or a counting stat, Sleeper keeps zero-stat DNP lines (for example Quincy Pondexter 53 vs 47 games
with identical points). Harmless to season points; it does inflate a Sleeper player's "games" count.

**Conclusion:** the two sources agree on the stat lines (99.2% of players within 0.5 pts, total within 1 pt of 350,564)
once the exhibition games are set aside. SC-005 is met for 2018-19 for the box-score content; the exhibition-game
defect is in what the importer lets through, not in the numbers.

### SC-005 re-measured after the code-review fixes (2026-11-02, Sleeper part re-imported, job 58)

**Verified** = executed. After findings 1, 2, 5 and 10 (exhibition games and Cup finals dropped by the shared spec-017
rule and the configured id list, bonus flags derived, Sleeper totals from 2014) the Sleeper part was re-imported on
the 5438 scratch DB (job 58, DONE, empty problem report: `failedSeasons`, `emptyPositions`, `uncoveredCollisions`,
`unmatchedFantasyRelevant` all empty) and `parity.py` was re-run on the re-exported 2018 rows:

| | players | matched | within 0.5 pts over the season |
|---|---|---|---|
| Sleeper rows **exactly as imported** (no manual removal) | 530 | 527 | **523 (99.2%)** |

Total matched points: 350,563.0 (Sleeper) against 350,564.0 (Kaggle), the same totals as the earlier "minus the 4
exhibition teams" row. The 4 players still outside 0.5 are the same box-score disagreements as before (Boban
Marjanovic +2.0, Clint Capela -1.0, Jarred Vanderbilt -1.0, Miles Bridges -1.0). The earlier 90.7% as-imported figure
was the exhibition defect, now fixed in the importer. `game_count` is now 1230 for 2017, 2018, 2021-2025, 1080 for
2020 and 971 for 2019 (to 2020-03-11).

## T029 final runs (2026-11-02, after T024-T028)

- Backend, `DB_URL=jdbc:postgresql://localhost:5438/draftsim`, from the result XML: 2,628 tests, 5 failed, 330
  skipped. The 053 classes (every `Historical*`, `Kaggle*`, `Sleeper*Historical*`, `EraScoring`, `SeasonReplay`,
  `Comparisons` test): 109 tests, 0 failed, 0 skipped. The 5 failures are other features and none touches 053:
  `PlayerSpotlightTopOfNightIT` and `PlayerSpotlightWeekIT` (hardcoded other DB), `LeagueRepositorySuccessorIT` (2,
  needs league rows), and `EspnRefreshOnVisitIT` (its cleanup `delete from manager` hits
  `draft_pick_manager_id_fkey` on rows the scratch DB holds; new to this list, not investigated). The 330 skips are
  other features' ITs this DB cannot satisfy.
- Web: `npx tsc -b` clean, `npx vitest run` 158 files / 2,734 tests passed, `npm run build` ok.
- SC-001 timing: logged and recorded above (create 91 to 123 ms; final call 252 to 776 ms), confirmed.
