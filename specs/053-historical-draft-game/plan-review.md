# Adversarial plan review

This review read the plan documents cold on 2026-11-02. It checked each claim
against branch `053-historical-draft-game` @ `ad1d031` (code identical to `main`;
the spec docs are untracked), and against the two Kaggle CSVs the research pass left
in the session scratchpad (`kaggle-players/`, dataset version 2). No Postgres or
server was started. No code exists yet.

Every item is labelled **measured** (run today, output quoted) or **read** (from
code or docs, not executed). The throwaway scripts are in the scratchpad under
`review053/` and are not in the repo.

## Claims confirmed against the code

| Claim | Where checked | Result |
| --- | --- | --- |
| `MockDraftEngine.advanceUntilUserOrEnd` is a pure function of a context, seats and seed | `engine/MockDraftEngine.java:59-131` (read) | ✅ No DB, no `player` table, no Sleeper id. It replays completed picks by `ctx.byId()` and decides the rest with `PickDecider` |
| `DraftContextFactory.build` works on any board | `engine/DraftContextFactory.java:56-87` (read) | ✅ Reads only `SportRulesRegistry` and `ScoringProperties.forSport(NBA)` (bot weights, not fantasy scoring). It refuses a board shorter than `teams × rounds` (`:97-106`); 168 is below every season's board (1970-71 has about 230 player-seasons) |
| The draft path never reads `Player.sleeperId`, `status`, `injuryStatus` | `PickDecider`, `PickScorer`, `BasketballRules.prepareLineup/rosterNeed/isDraftable/isEligible`, `RosterState`, `DraftContext` (read) | ✅ Only `positions` (via `primary()` and `maskOf`) and `adp`. `status` is read only by `BasketballRules.isSuspended`, which is not on this path. A historical `Player` with `sleeperId`/`status`/`injuryStatus` = null is safe |
| Player id collisions | `DraftContext:73-75` (read) | ✅ `byId` is built from the one board, so all ids are `historical_player.id`. `completedAt` uses 0 as "none" (`:113-115`), and bigserial starts at 1. Nothing mixes `player.id` and `historical_player.id` |
| Cited lines | `PickScorer.java:104`, `BasketballRules.java:120`, `RealizedLineupService.java:65`, `GameScoringService.java:42`, `PositionalPriors.java:114`, `LeagueShape.java:62` (read) | ✅ All exact |
| `BoardEntry.adp` as a rank works with bot scoring | `PickScorer.valueDelta:103-107`, `BasketballRules.value:120-122` (read) | ✅ Pick-number units. A rank 1..N is in the same units. Lower is better in both, so "rank 1 = best" is the right sign |
| `mock_draft_pick.player_id` has an FK to `player` | `V3__mock_draft.sql:45` (read) | ✅ Separate tables are needed |
| Highest migration is V42; V43 is free | every local branch and remote ref, and every worktree directory under `.claude/worktrees` (measured) | ✅ No V43+ anywhere |
| One lineup implementation | grep `startingLineup(` / `bestLineup(` (read) | ✅ `BasketballRules.startingLineup` is the only solver. `RealizedLineupService` and `LeagueAnalysisService:1061` both call it. The proposed overload adds a caller, not a solver |
| Per-player max game before slotting is right for lock-in best ball | `BasketballRules.prepareLineup` javadoc and `:239-290` (read) | ✅ Each starter contributes exactly one game, independent of slot. So "max per player, then matroid greedy over those values" is exactly optimal. Nothing couples games across slots |
| `GameScoringService.score` with a filtered map | `:42-80` (read) | ✅ It iterates **scoring** keys, so removing a key removes the stat. It needs `Number` values, which Jackson gives from jsonb |
| Sleeper's per-week endpoint is per game | `SleeperPlayerStatsClient.week:37-62` (read) | ✅ One entry per player-game with `game_id`, `date`, `team`. DNP entries (`stats: {}`) are included (see F12) |
| "Sleeper omits zero stat keys" | `specs/043-tweet-digest-admin/plan.md:117` (read) | ✅ for NBA 2025 (993 played rows with no `pts_std`, none with 0). **Not measured for 2017–2019.** SC-005 is the check |
| CORS and the admin gate cover the new routes | `config/WebConfig.java:40-61` (read) | ✅ `/api/**` is mapped and `X-Sleeper-User` is allowed. `/api/admin/**` already goes through `AdminGateInterceptor`. `/api/history/**` matches none of `PlatformGuard.PATH_PATTERNS` (`api/PlatformGuard.java:80-84`), and no existing route uses `/api/history` |
| Season decoding from `Game_ID` | box CSV (measured) | ✅ `0024600034` is 1946-47. Mapping `yy ≥ 46 → 19yy`, else `20yy`, gives 1,006,146 rows for 1969-70..2016-17 |
| Season file `Season` is the end year | `Kareem 1970 MIL 82 G` (research); `Michael Jordan 1995 CHI 17 G` (measured) | ✅ |
| Era rule numbers (R2) | box CSV (measured, all rows / played rows) | ✅ 1982-83 STL 82.7%, BLK 91.5%, TOV 81.1%; 1983-84 ≥ 99.7% for every stat; 1975-76 STL 61%, BLK 63% (so the spec's 1975-76 test holds) |

## Findings: the plan was wrong or incomplete

Ranked by severity.

**F1 (HIGH). The biggest-bust formula has its sign inverted. It names the user's
best steal as their biggest bust.**
data-model.md, "Derived": *"Biggest bust: the user's pick with the largest
(pick number − season-value rank among all drafted players)."* A bust is taken
early and finishes low: pick 20, rank 88 gives 20 − 88 = −68. The largest value of
`pick − rank` comes from pick 160 at rank 2 (+158), which is a steal. The contract's
own example (`pickNo 20, seasonValueRank 88`) contradicts the formula. This is bug
class 1, a well-formed number pointed backwards, and no structural test catches it.
**Amendment**: bust = argmax over the user's picks of `seasonValueRank − overallPickNo`,
and null if no pick has a positive gap. Add a preference-ordering unit test: a
pick-3-finished-150 player must be the bust, and a pick-160-finished-2 player must
never be.

**F2 (HIGH). The same-name detector misses different people with the same name
in consecutive seasons. The prior-season card then shows the wrong person's
stats.**
R5 finds collisions with "multi-team rows with no `TOT` row in one season". An
age-continuity check on the season file (measured, `review053/age.py`) finds 51
names whose age jumps impossibly between seasons. Some of them are adjacent-season
cases that the no-`TOT` test cannot see:
- `Charles Smith`: 1996-97 SAS, age 31, then 1997-98 TOT/MIA/LAC, age 22. There is a
  `TOT` row, so it isn't flagged. The 1997-98 card for the 22-year-old would show the
  31-year-old's 19 games.
- `Charles Jones`: 1997-98 HOU, age 40, then 1998-99 CHI, age 23. Each season has one
  team, so nothing is flagged.
- `Michael Smith` 1994-95 (ages 22 and 29) into 1995-96 (age 23), and `Chris Johnson`
  2012-13 into 2013-14, are the same shape.

There is a second problem. The curated file maps **(season, team, name) → person
key** only for collision seasons, but the prior-season join is by person key across
seasons. If `eddie-johnson-2` is keyed for 1982–87 and the lone 1988 row falls back
to the plain normalised name, the 1988 card can't find its 1987 line.
**Amendment** (R5 and data-model):
1. Add an age-continuity check: for a normalised name present in seasons S and
   S+k, ages must differ by k−1..k+1. Any violation is an uncovered collision, and
   the import fails loudly as it already does for the no-`TOT` case.
2. Any name that appears in `historical-same-name.csv` must have **every** season row
   keyed. The import fails if a curated name has an unkeyed row in any season.
3. SC-007's known-pairs list must include the adjacent-season cases above, not only
   the 23 concurrent ones.

**F3 (HIGH). The box-score CSV is sorted by player, not by season. "Stream it,
one transaction per season" can't be done in one pass, and grouping it in memory
won't fit in `-Xmx1024m`.**
Measured: 1,309,778 rows with **120,134** changes of season, 120,057 of them back
to a season already seen. The file is grouped by player (the first rows are Adolph
Hoefer's 1946-47 games). Holding about 1.0M parsed rows (about 30 string fields each)
until each season is complete is several hundred MB of heap, on top of the running
app. That is the OOM spec 041 tuned around. Reading the 158 MB file 48 times is
slow but bounded.
**Amendment**: phase 1 streams the zip entry once and writes rows into an
`UNLOGGED` staging table through pgjdbc's `CopyManager`. That needs no new
dependency; pgjdbc is already on the classpath. Phase 2 processes one season at a
time from staging, in its own transaction, then drops staging. Inserts into
`historical_player_game` use `COPY` or `batchUpdate`, never row-by-row inserts
(about 1M rows). State this in the plan, not in tasks.

**F4 (HIGH). "Greedy hindsight" picks by raw season value, ignoring the lineup.
It can come out below the user's own draft, and the plan doesn't say how it
co-exists with the bots' fixed picks.**
data-model: *"At each user pick, take the available player with the highest season
value, then replay."* Raw season value ignores slots. Greedy can take a fourth
centre who never starts: there's one C slot plus two UTIL, and in Kaggle seasons
almost every player has exactly one position (F6). The "hindsight" team can then
score **less** than the user's. A negative gap next to the word "hindsight" is
bug class 5: the number is correct but reads as something else. The plan also
doesn't say what happens when the hindsight pick is a player a bot took later in
the real draft.
**Amendment**:
- (a) At each user pick, choose the candidate that maximises the **team's season
  points** (sum of weekly best-lineup totals) given the hindsight picks so far. Each
  evaluation is 24 cheap `startingLineup` calls, so that's about 14 × 300 × 24 ≈ 100k
  calls, well inside the 2 s budget.
- (b) Candidates are the players actually available at that pick in the real draft,
  minus earlier hindsight picks. A player a bot took later is allowed. The
  hindsight roster is scored on its own (points only, no head-to-head), so it never
  changes a bot's roster. Say so in the label.
- (c) If hindsight still comes out below the user's total, show that honestly
  ("you beat the greedy hindsight draft") rather than a negative "gap".

**F5 (MEDIUM). Positions in the source are per season and almost always a single
position. The data-model's person-level `positions` and its "G/F/G-F mapping"
premise don't match the data.**
Measured (season file, 1969-70..2016-17): about 21.8k rows; 21,542 are exactly one of
PG/SG/SF/PF/C. Hyphenated pairs (`SF-SG`, `C-PF`, `PG-SG`, …) are 214 rows.
`G`/`F`/`G-F`/`F-C`/`C-F` together are **21 rows**. Basketball-Reference assigns
modern positions retroactively even in the 1970s. 977 of 3,287 names change `Pos`
across their career. Consequences:
- `historical_player.positions` (one set per person) must pick a season, and the
  union across a career leaks later positions and over-broadens eligibility.
- `Player.primary()` returns **`Position.WR`** for an empty list
  (`domain/Player.java:19`). A box-only player with no season-file match would get
  football's WR on an NBA board. `PickDecider` only fills the NBA positions'
  caches (`:126-130`), so he would score with a zeroed positional term, and
  `maskOf` = 0 keeps him out of every lineup.
- Kaggle-era rosters are single-eligibility, while Sleeper-era ones are typically
  multi-eligible (66% of drafted players in the real 2025 league,
  `BasketballRules` javadoc). Lineup flexibility differs by era. That is real
  structure, not a bug, but it should be stated.

**Amendment**:
- Move `positions` to `historical_player_season`. A card and its eligibility use the
  **prior** season's `Pos`. A player with no prior season uses the drafted season's
  `Pos`; that is a small, documented leak.
- Map hyphenated values to the union, first listed = primary. Fail the import on
  any board player whose mapped set is empty.
- Exclude from the board any player-season that has no season-file match and isn't
  fantasy-relevant. Today R5 only says what happens to fantasy-relevant ones.

**F6 (MEDIUM, needs Allan). The fallback ranking hands the user every star who
missed most of the prior season, and every rookie and ABA arrival. In named mode
that is a known exploit.**
The ranking is prior **total** points, and "no prior NBA season" sorts last. Measured
prior lines:
- Larry Bird, drafting 1989-90: 6 G.
- Michael Jordan, drafting 1995-96: 17 G.
- Stephen Curry, drafting 2012-13: 26 G.
- Kobe Bryant, drafting 2014-15: 6 G.
- Kevin Durant, drafting 2015-16: 27 G.

Bots draft from this list (`PickDecider` takes candidates in board order), so all of
these fall many rounds. So does every rookie (Jordan 1984-85, Duncan 1997-98) and
every ABA player in 1976-77 (Erving, Gervin, Malone). A user who knows history takes
them late every time. This undermines the hindsight comparison and the game's point.
The spec's Assumption fixes the formula, so changing it changes what the bots
*are*. That's Allan's call, not a build detail.
**Amendment (options for Allan)**:
- (a) Rank by prior per-game × max(games, a hand-set floor such as 60). Label the
  floor as hand-set.
- (b) Use the better of the last two seasons.
- (c) Keep the formula and say on the start screen that bots don't know who was
  hurt.

Rookies need a source of draft order with a compatible licence. Until one exists,
(a) or (b) still leaves them last. State that limitation next to R6's accepted leak.

**F7 (MEDIUM). The era-rule denominator is defined two ways, and the two choices
flip a real season.**
FR-007 says "box-score rows". The O2 table in research uses "rows where the player
actually played". Measured for 1974-75 assists: **94.8% of all rows, 95.3% of played
rows**, which is fail on one definition and pass on the other.
**Amendment**: pick one and write it into FR-007, EraScoring and the config comment.
Recommended: played rows (minutes or points > 0), because a DNP row carries no stats
and shouldn't count against a stat. Then regenerate the per-season outcome table
(1970-71..1983-84) and put it in research.md, so the locked outcome is visible.

**F8 (MEDIUM). The draft order (reversal round) is neither specified nor stored.**
The engine picks the seat with `DraftSlot.slot(pickNo, teams,
ctx.settings().reversalRound())` (`MockDraftEngine:84-92`). The mock service checks
whose turn it is with the stored value (`MockDraftService:415`, `:590`). The comment
at `MockDraftEngine:84-91` records the bug when the two disagreed.
`historical_game` has no `reversal_round`. Allan's 12-team NBA league, whose shape
the plan borrows, drafts with `reversal_round: 3`
(`claude/multi-sport-and-rebrand.md:292`). Sleeper's default is plain snake. A
defaulted value here asserts a rule (memory: "Optional params that encode rules",
which shipped three times).
**Amendment**: add `reversal_round int not null` (0 in v1, "Sleeper default: plain
snake"). Build `LeagueSettings` with the 6-argument constructor and pass the value.
The service's on-the-clock, `myPicks` and 409 checks read the same column.

**F9 (MEDIUM). Nothing pins the board of an in-progress game. A re-import could
change ids or order under it.**
The engine is stateless per request: it rebuilds the board and replays picks by id
(`MockDraftEngine:80-100`). The mock room builds its context "from the session's own
snapshotted shape/seats/board" (`:46-48`). This plan rebuilds the board from
reference tables on every call. The import is "replace rows per season". If that
deletes and re-inserts `historical_player`:
- ids change, so stored picks point at nothing (`log.warn`, then skip);
- or the FK from `historical_game_pick` blocks the delete.

Ties in prior points and the "seeded random" rookie order also have to be
reproduced byte-for-byte on every request.
**Amendment**:
- The import **upserts** `historical_player` on `(source, source_key)` and never
  deletes it. Only `historical_player_game` and `historical_player_season` rows are
  replaced.
- At game creation, snapshot the board onto the game: player ids in rank order, plus
  `adp` and `positionalRank`. A `bigint[]` plus `real[]`, or jsonb. Every later call
  builds `BoardEntry`s from the snapshot.
- Tie-break the ranking by `historical_player.id`.
- Note in the board builder that the list must be sorted by `adp` ascending.
  `PickDecider.choose` takes the first `candidatePool` entries in list order
  (`:113-120`).

**F10 (MEDIUM). Pick, auto and abandon need the row lock the mock room uses, or
the replay can run twice.**
`MockDraftService` takes `lockForUpdate` in every mutating call (`:409`, `:461`). The
plan says "replay runs in the same transaction" but never says the game row is
locked. Two tabs, or a double-click on the last pick, would both see
`current_pick_no = 168`.
**Amendment**: name `lockForUpdate` in HistoricalGameService. Run the replay only
when the locked row is still `DRAFTING`. 409 otherwise.

**F11 (MEDIUM). DNP entries become 0-point "games", and the two sources disagree
about them.**
Sleeper's weekly payload includes `stats: {}` entries for DNPs
(`SleeperPlayerStatsClient:45-46`). Kaggle mostly has no DNP rows (measured: 90–131
rows a season with zero minutes and zero points in modern seasons). Storing DNPs has
three effects:
- A DNP week scores 0 instead of "no game". That changes nothing by itself.
- A week whose only played game was negative (turnovers, `tf`/`ff`) scores 0 in
  Sleeper seasons but negative in Kaggle seasons.
- `gameDate` can point at a game the player didn't play, which breaks the SC-003
  hand-check.

It also inflates O3.
**Amendment**: drop non-played entries at import, using `BasketballRules.playedIn`
(non-empty stats) for Sleeper and minutes or points > 0 for Kaggle. A player-week
with no played game is absent. He scores 0 in the lineup only if he's needed to fill
a slot. A week where every played game is negative keeps its max (negative) score.
State that this matches lock-in, where a starter's locked game counts even when
it's bad.

**F12 (MEDIUM). The card and ranking formula differ by source, and cards can show
stats that don't count.**
R3 takes Kaggle cards from the **season file**, which has no `dd`/`td`/`bonus_*`.
Sleeper season totals may carry them (not measured). So "last season's points"
means different things before and after 2017-18. Separately, the season file is
complete where the box file isn't. Measured: STL and BLK are 100% present in the
season file from 1973-74 and TOV from 1977-78. So a 1975-76 card would carry
1974-75 steals, which are fully recorded in the season file, even though steals
don't count in 1975-76 (61% box coverage). That breaks SC-004 ("no stat that fails
the era rule appears in … cards") unless the card filters.
**Amendment**:
- Card `perGame` and `fantasyPoints` use only the drafted season's counted keys.
- The ranking and card points use **counting stats only** (no `dd`/`td`/`bonus_*`)
  for both sources, labelled "excludes bonuses".
- The replay keeps the bonuses.

**F13 (MEDIUM). FR-002 says "Sleeper's default settings, read, not guessed". The
plan uses Allan's league's roster and the other league's scoring.**
R7 shows Sleeper's API exposes no product defaults. The plan uses
`LeagueShape.NBA_ROSTER` (12 teams, 14 rounds, the Ball Knowers 12-team league) and
Legacy's 10-team scoring. `BasketballRules` hardcodes that exact 9-slot shape
(`:58-70`), so no other shape can be used without new lineup code. That's fine, but
the spec still claims Sleeper defaults.
**Amendment**: reword FR-002 to "the app's standard NBA shape (12×14, PG SG G SF PF F
C UTIL UTIL + 5 BN, the shape `BasketballRules` is proven against) and
[O1-resolved] scoring". Record the provenance on the start screen's "which stats
count" panel.

**F14 (MEDIUM). Decide the import's async shape now. It will run for minutes, and
its first prod runs will fail by design.**
About 1.0M Kaggle rows, plus Sleeper's 9 seasons × about 25 weekly calls, plus 10
totals calls. That is certainly over the plan's "30 s locally" trigger, so make it
async now: a job row and a polled report (`GET /api/admin/historical/import/{id}`).
Railway's proxy timeout makes a synchronous call unsafe. Three more gaps:
- Fail-loud identity (R5) means the alias and same-name files have to be curated
  **locally** to an empty report before prod. Say so in quickstart.
- Kaggle's anonymous download was measured from this machine, not from Railway.
  Allow `historical.kaggle-zip-url` to be overridden, and make the SHA check
  independent of the URL.
- A season is offerable only if its own rows **and** its prior season's lines
  committed. 1969-70 lines must be imported even though that season isn't offered.
  With per-season transactions, season S can commit while S−1 failed. Encode this in
  the offered-seasons query.

**F15 (LOW). O3's number and the Sleeper row count are inconsistent and
unmeasured.**
The plan says "about 0.25M Sleeper rows" and research O3 says "about 0.6M". 1,006,146
Kaggle rows is measured. With per-row `jsonb` (about 12 numeric keys), tuple
overhead, the `(player_id, game_id text)` PK and the `(season_start, week)` index,
the estimate is about 400–500 bytes a row, or about **0.5–0.6 GB** **(assumed, not
measured)**.
**Amendment**: store box stats as typed nullable columns (`smallint` for pts, reb,
ast, stl, blk, to, tpm, plus derived flags). NULL preserves "unrecorded". The
`game_id` becomes an integer. Drop DNPs (F11). Measure locally, as quickstart already
says, and check the Railway Postgres volume before deploying.

**F16 (LOW). Ties aren't modelled.**
Half-point scoring makes equal team-week totals possible. The contract's standings
row has only `wins` and `losses`, and a week with no games (unlikely, not measured)
would tie every matchup at 0.
**Amendment**: add `ties` to standings, ranked W-L-T then points for, then a seeded
coin. Bucket weeks from game dates only, so an empty calendar week is skipped
rather than numbered.

**F17 (LOW). CSV parsing traps.**
`GAME_DATE` is `"NOV 19, 1946"`, all upper case. `DateTimeFormatter.ofPattern("MMM d,
yyyy", Locale.US)` rejects `NOV` unless you use `parseCaseInsensitive()`. Column to
key mapping must be explicit (`TOV→to`, `FG3M→tpm`, `REB→reb`). The first column
header is empty. Box and season files use different team codes. Measured: the box
file has `BKN BLT GOS PHL PHX SAN UTH`, the season file has `BRK BAL CHO KCO WSB`,
and `TOT` appears only in the season file. R5's "(season, **team**, name) first"
match and the `(season, team, name)` keys in the same-name file need a team-code
crosswalk, which the plan doesn't mention.
**Amendment**: add `historical/team-codes.csv` and unit tests for both parsers.

**F18 (LOW). Web wiring is missing from the structure.**
The plan never names:
- the `App.tsx` routes, inside the signed-in shell (`App.tsx:113-199`);
- a nav or rail entry for a page that has no league. `destinations.ts` is
  league-scoped, so this page needs a global entry like `/managers`;
- the 401 path when `X-Sleeper-User` is missing.

Two-service deploy: ship the backend first, or the page 404s until it lands.

**F19 (LOW). Information hygiene while drafting.**
- A rookie's card must have `team` = null and `age` = null, or age from birth year
  if a prior row exists. Taking them from the drafted season's row leaks his
  drafted-season team.
- Sleeper-era `positions` come from today's `/v1/players/nba`, not as of that season.
  That's a mild future leak; document it beside R6.
- Sleeper `age` can be computed from `birth_date` in the same dump rather than left
  null (data-model says "(assumed)" null).
- "Best steal passed on" must exclude players the user drafted later (spec: "an
  undrafted-by-them player"). The data-model's wording doesn't.

**F20 (LOW). The `RealizedLineupService` overload's shape.**
A `Map<Player, Double>` keys on record equality, including the `positions` list.
That works, but use `List<(Player, points)>` or a `Map<Long, …>` keyed by id. Sort
ties by points, then id, so the reported `gameDate` and slot are reproducible.
`WeekLineup.absent` means "exclude this week" (`:41-46`) for its existing callers.
For the replay, an empty roster-week is 0 points, not excluded. Say so at the
call site so nobody copies the existing callers' handling.

**F21 (LOW). The spec contradicts itself.**
US1 says "The season being drafted stays hidden until the draft is over". Scenario
1 and the plan show the label ("1995-96") at the start. Reword US1 to "the drafted
season's **stats** stay hidden".

## Open items

**O1 (scoring set).** Recommend **Legacy, provisionally**. From recollection,
Sleeper's stock NBA points scoring is pts 0.5, reb 1, ast 1, stl 2, blk 2, to −1,
tpm 0.5, dd 1, td 2, 40p/50p 2/2, 20-reb/15-ast 1/1, tf/ff −2. That's Legacy's set
exactly, which supports the "(assumed) untouched" reading. This is **not verified**.
Allan can confirm it without creating anything: Sleeper's create-league flow shows
the default NBA scoring before you submit. Record the result in R7. Either way,
F13's spec wording applies: the roster comes from the 12-team league and the
scoring from the 10-team one.

**O2 (early-1970s era rule).** New evidence: the **season file** has REB and AST for
100% of rows in 1969-70..1972-73 (measured). Rebounds and assists were tracked, and
the box-score gaps are lost box scores (17–39 whole games a season), not untracked
stats. Under weekly-max best ball, a lost box score matters only when it would have
been that player's best game of the week. Recommendation: **(b), refined**. A stat
counts when the league tracked it (season-file coverage ≥ 95%) **and** box coverage
is ≥ 90% of played rows. A blank cell contributes nothing, and the start screen
says "about 5–8% of this season's box scores lack rebounds/assists". The result:
1970-71..1974-75 score pts/reb/ast, and steals stay out until 1983-84 (box 82.7% in
1982-83). Both thresholds are hand-set and labelled. If Allan wants no
partially-recorded scoring at all, (c) is next best: start at 1974-75 under the
played-rows denominator (F7). (a) makes four seasons "points only", which is honest
but makes those drafts a pure scoring-volume contest.

**O3 (storage).** It's unmeasured. The estimate is 0.5–0.6 GB with jsonb rows
**(assumed)**, so it is not "a few hundred MB, comfortably". Do F15 (typed columns,
no DNPs, integer game id), then measure on the local load as quickstart §1 says.
Compare against the Railway Postgres volume before the first prod import.

## Checked and fine

- The engine is board-agnostic for this use, as R9 says. See the table above for
  each class read.
- Lock-in semantics: max game per player, then slotting, is exactly optimal. It
  isn't an approximation.
- `GameScoringService` reuse via a filtered map. There is no second scorer.
- Separate tables are needed because of the V3 FK.
- CORS allowed headers, the admin interceptor on `/api/admin/**`, no PlatformGuard
  overlap, and no `/api/history` collision.
- V43 is free everywhere.
- 2019-20 cut: 2020-03-11 is a Wednesday, so the last week is Mon 03-09 to
  Wed 03-11. It belongs to the Sleeper range (R3), so the cut has to be applied to
  Sleeper rows by date. data-model already does that ("rows after
  `last_counted_date` are not loaded").
- R4's decision to use the fallback everywhere is the conservative reading of
  FR-009. The leak evidence is suggestive, not conclusive, as R4 itself says.
- The fallback rank's sign: rank 1 = best = lowest `adp`, which matches
  `valueDelta` and `value`'s decay.
- No `Map.of` risk was found in the contract's nullable fields beyond what the plan
  already lists.
- Use `createArrayOf("text", …)` for every `text[]` bind (`LeagueRepository:55`,
  lessons #4), and `OffsetDateTime` for `timestamptz` (lessons). Put both in tasks;
  the plan doesn't mention them yet.
