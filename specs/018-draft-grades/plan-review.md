# Adversarial plan review

This review read the spec 018 plan documents cold on 2026-10-05, before any code
was written. It checked them against branch `018-draft-grades` (worktree, based
on `origin/main` @ `c450f67`, plan files uncommitted) and against the public
Sleeper API.

Each item is labelled **measured** (run today, output quoted) or **read** (from
code, not executed).

**The local DB could not be reached for this review.** Nothing listens on 5433,
and Docker Desktop won't start: `mounting WSL VHDX ... Insufficient system
resources exist to complete the requested service` (0x800705aa, ~2.2 GB free
RAM). `pgdata/` is a PG 17 cluster, so the native PG 14 binaries can't open it.
So every **measured** item below comes from the Sleeper API, pulled fresh and
scored with the same rule in a scratch script: per-week `/stats/{sport}/{season}/{week}`,
league `scoring_settings`, `/matchups/{week}` and `/draft/{id}/picks`. That makes
it an independent check of the DB-based research, not a re-run of it. Small
differences (a few starter-weeks) are stat corrections between the stored rows and
today's API. The DB-only claims are listed in N16 as **not re-measured**.

## Claims confirmed

| Claim | Where checked | Result |
| --- | --- | --- |
| R1: NFL `player_game` × scoring reproduces `players_points` | Sleeper API, (Foot) Ball Knowers 2025 (17 wks) and 2026 (3 wks) (measured) | ✅ 2025: 2,039 starter-weeks, **2,023 exact, 0 mismatches, 16 no game row**. 2026: **360 / 360 / 0 / 0**. Identical to R1's table. D/ST rows are keyed by team code (`SEA`, `NE` …) in the stats payload, and they match |
| R2: NBA credits one game per starter per week | API, NBA 2025 (1229352720222134272) and 2024 (1141438340626231296) (measured) | ✅ `points` = Σ starters' `players_points` in 252/252 and 288/288 roster-weeks. Multi-game starter-weeks: 2025 **2,171, 2,145 = one game, 1 = sum**. 2024 **2,429, 2,386 = one game, 0 = sum**. R2 had 2,153 and 2,386 |
| R2 ratios | same (measured) | ✅ avg-game / credited: 2025 **0.845 (sd 0.055)**, r **0.989**. 2024 **0.878 (sd 0.084)**, r **0.983**. Sum / credited: 2.56 and 2.61 |
| R3: week numbers line up for the 2024 league (`playoff_week_start` 22) | equal-week join above (measured) | ✅ 2,386 of 2,429 multi-game weeks match one game on equal week numbers. `start_week` 1 in both leagues |
| R3: NBA 2025 has games in weeks 22–25 that the league never scored | API (measured) | ✅ league `complete`, `leg 21`, `last_scored_leg 21`. Stats weeks 22–25 exist (2,0xx entries each). Sleeper **also serves matchups for weeks 22–25**, so the ingest's `last_scored_leg` bound (`LeagueHistoryIngestService:253`) is what keeps them out |
| R11: NFL 2026 has 3 final weeks | API `leg 4`, `last_scored_leg 3`; `WeekFinality.isFinal` (measured + read) | ✅ week 3: `lsl ≥ 3 && leg > 3` → final, week 4 not |
| R4 prototype (NBA 2025, k = 6) | own script, same rule (measured) | ✅ steals **43 Murray +198, 114 Clingan +196, 168 Knueppel +181, 110 Okongwu +168, 25 J. Johnson +163**. Busts **57 Kessler −405 (2 wks), 9 Trae −397 (6), 8 Sabonis −367 (8), 120 Beal −316 (3), 109 D. Murray −296 (3)**. Same picks as the contract's `steals`/`busts` |
| Self-exclusion matters (R4, plan's gate table) | same, self included vs excluded (measured) | ✅ up to **41.4 points** per pick. Including self changes the top 5 (123 in, 110 out) |
| R6: 57 picks started for another roster, 32 never for the drafter | API matchups × picks (measured) | ✅ **57 / 32** exactly |
| Drafting team keying is clean today | 7 completed drafts via API: picks, `slot_to_roster_id`, league rosters (measured) | ✅ in every draft: **0 traded picks**, **0** blank `picked_by`, `picked_by` = the roster's current `owner_id` on every pick, **0** orphan rosters, **0** managers with two rosters. One co-owner (West Coast FF 2026, roster 6). See N1 for why this is fragile anyway |
| No keepers | `is_keeper` on all picks (measured) | ✅ null on every pick of all 7 drafts. NBA 2025's settings do have `max_keepers: 1` and `pick_trading: 1` |
| `ScoredWeeks.of(id).finalWeeks()`; complete season → every stored week is final | `ScoredWeeks:52-66` (read) | ✅ |
| Cited line refs | `RosterWeekPointsRepository:129`, `RosterSeasonRepository:84`, `SportRules:65`, `HealthController:39`, `PlayerGameIngestService:248`, `LeagueController:301`, `TransactionAnalysisService:55`, `application.yml:23` (read) | ✅ all point where the plan says |
| `player_game` holds every entry in a week's payload, not only rostered players | `PlayerGameIngestService` javadoc + loop `:216-273` (read) | ✅ so a drafted player who was never rostered is still covered. "Production 0" is truthful, given the week was fetched (see F7) |
| `/board` already carries `status` | `LeagueController:313`, `RealDraftBoard.status` in `api.ts:236` (read) | ✅ the "complete only" toggle needs no backend change |
| Position rule | `BoardEntry.position()` = `player.primary()` (read) | ✅ same rule. But see N11 |
| `Sport` wire form | `@JsonValue code()` → `'nfl' \| 'nba'`, `api.ts:53` (read) | ✅ the TS mirror's `sport: Sport` is right |
| A new GET under `/api/**` works from the browser | `WebConfig` global mapping, `ApiTokenFilter` Bearer already sent by `apiFetch` (read; as in 017's review) | ✅ |
| `Map.of` in health stays safe | four non-null values (read) | ✅ |

## Findings: the plan was wrong or incomplete

Ranked by severity.

**F1 (high). In football, value over slot mostly finds late quarterbacks. The
headline list and the team grade reward whoever took QBs late.** (measured)

Production is raw league points, and the baseline is the median of *all positions*
in a ±k window. In a one-QB league a QB scores far more than the WR/RB around him,
yet replacement QBs are plentiful. So every mid- or late-round QB grades as a steal.
(Foot) Ball Knowers 2025 (roster `QB, RB, RB, WR, WR, TE, FLEX, FLEX, K, DEF`,
`pass_td` 4):

- Top 5 steals: **Drake Maye (142) +215, Stafford (160) +206, Caleb Williams (116)
  +191, Dak (133) +185, Josh Allen (21) +172**. All five are QBs.
- Top 20 steals: **11 QB**, 6 RB, 2 WR, 1 TE. Bottom 20: 9 WR, 9 RB, 2 QB.
- Mean value over slot by position: **QB +94.9 (22 picks)**, RB −3.9, WR −17.8,
  TE +0.1, K +5.9, DEF +1.6.
- 2026, after 3 weeks: top 20 has **10 QBs**, top steal Tyler Shough (180),
  QB mean +24.4.

This is lessons bug class #1's shape: well-formed, passes the pick-10 ordering test,
and points at the wrong thing. SC-003's synthetic test can't catch it, because its
picks have no positions. Basketball doesn't show it (C −7.4 … SG −47.3, smaller and
not one-sided), so it's also lesson #29: "one rule for both sports" holds for the
arithmetic, not for what the number means.

**Amendment**: before building, decide the football baseline. Options, cheapest
first:
(a) a positional baseline: production minus the median of same-position picks
    within a wider window. Thin, but honest.
(b) replacement level from the draft itself: production minus the Nth-best
    drafted production at the position, with N = starters at that position × teams
    from `rosterPositions`. Hand-set, labelled ARBITRARY.
(c) keep raw value over slot, but lead with `positionFinish` vs `positionDrafted`
    for football, and drop the cross-position steals list.

Whichever is chosen, add an ordering test that has positions in it: a late QB
with QB-typical points must **not** be the top steal over a WR who outscored
every WR drafted ahead of him. Re-run the five-steals check on NFL 2025 in
verification.

**F2 (high). In basketball, "counted for you" is bigger than "production" for a
quarter of picks. The part exceeds the whole.** (measured)

Production uses the week's *average* game. Counted-for-you uses Sleeper's
*credited* game, which runs ~18% above the average (R2: 0.845). For a player who
started most weeks for his drafter, the subset comes out bigger than the total.
NBA 2025: **44 of 168 picks** have counted-for-you > production. For example:

| Pick | Player | Production | Counted for you |
| --- | --- | --- | --- |
| 1 | Jokić | 747 | 801 |
| 2 | Dončić | 726 | 824 |
| 3 | Wembanyama | 614 | 712 |
| 10 | Towns | 576 | 670 |

NFL 2025: 0 of 180, as expected, because there the two scales are the same.

A reader takes "points that counted for you" as a part of "his production", and
the PlayerCard puts them side by side. That's bug class #5. It also guts SC-004 /
US3's test in basketball: "the two numbers differ" is true for every pick, so the
test can't tell a roster change from a scale difference.

**Amendment**: put counted-for-you on production's basis. Σ `weekValue(p, w)` over
the weeks he started for the drafting roster (the same average-game rule), so
`countedForYou ≤ production` is an invariant (add it as #8). Keep Sleeper's exact
credited sum as a separately labelled field (`creditedForYou`) if it's wanted, and
never draw it next to production without saying it's a different scale. US3's test
then becomes "a loyal starter's counted-for-you ≈ production, a dropped player's is
far lower".

**F3 (medium). "Counted for you" includes weeks that counted for nothing.**
(measured)

`roster_week_points` has a row with starters and points for every roster in every
playoff week, including top-seed byes and eliminated teams with no matchup.
`matchup_id` is null there:

- NBA 2025 week 19: rosters 1, 2, 3, 7. Week 21: rosters 6, 8, 9, 10.
- NFL 2025 weeks 15 and 17: four rosters each.

FR-005 sums every final week where he was in the starters. **31** NBA 2025 picks
and **44** NFL 2025 picks get points from at least one such week. "Counted" is a
claim, and for those weeks it's false.

**Amendment**: either count only weeks where the roster had a non-null
`matchup_id` (`league_matchup`, written by the same loop:
`LeagueHistoryIngestService:279`), or rename the field to "points as his starter".
Say which one in data-model, and add a test with a playoff-bye week.

**F4 (medium). Team draft value isn't centred, so its sign reads as a verdict it
doesn't make.** (measured)

The median baseline is robust, but production is skewed. Injuries make a long low
tail, so values don't sum to zero:

- NBA 2025: Σ value over slot = **−3,600**. Mean per pick −21.4, median +4.9.
  **8 of 12 teams are negative** (slot 9 −1,105, slot 8 −686, slot 1 −666 …).
- NFL 2025: Σ = +849. NFL 2026: Σ = +294.

The grade is a percentile, so the letters are fine. The number beside the letter
isn't: "−666, B-" reads as "a bad draft that still got a B-". Same family as
lesson #28 and bug class #5.

**Amendment**: either centre team value (subtract the league mean team value, and
say "vs. the average team in this draft"), or send `leagueAverageDraftValue` and
show it in the strip's legend. Don't let a reader assume 0 = average when it isn't.

**F5 (medium). The NBA label states a per-league rule it only has per sport, and
it names a season total as an average.**

- **(read)** The basis comes from `playsMultipleGamesPerScoringPeriod()`, a fact
  about the sport. The legend says "(your league counts one game a week)", a fact
  about *this league*. It was measured in two leagues, and nothing found what sets
  it (R2: not first, last or highest, no settings key). NBA 2026 "Ball Knowers", or
  any NBA league added later, gets the sentence unmeasured. That's lesson #29.
  **Amendment**: word it as what was measured ("in the leagues measured so far,
  Sleeper credited one game per starter per week"). Or compute it per league from
  stored starter-weeks (share of multi-game starter-weeks whose credited points
  equal one game) and fall back to neutral text when it's unknown.
- **(measured)** "avg game per week played" next to **747** (Jokić) reads as a
  per-week average, but it's a 21-week sum of averages. That's the
  label-the-axis rule. **Amendment**: "season points, counting each week's average
  game".

**F6 (medium). The "new" `PlayerGameRepository.forPlayers` already exists, and the
proposed overload adds a bind risk for no benefit.** (read)

`forPlayers(Sport, int season, Collection<String> playerIds)` is on main already
(`PlayerGameRepository.java:171`, spec 008). It binds an `in (?, ?, …)` list, and
the per-game ingest already calls it with every rostered player
(`PlayerGameIngestService:294`). The plan lists `forPlayers(sport, season, ids,
weeks)` as "one new repository read" with `= any(?)` and `createArrayOf`. That's a
second read of the same rows, and it brings in bug class #3 (array binds) on purpose.
R9's 9,775 rows / 22 ms is already the whole-season read, which is what the existing
method returns.

The house pattern for exactly this computation exists too:
`SeasonSuperlativesService.boundedPlayerGames` (`:1104`) takes
`forPlayers(...)` rows and drops `!scoredWeeks.contains(row.week())` in Java.
**Amendment**: reuse the existing method and filter by `finalWeeks` in the pure
core. That core must ignore non-final weeks anyway, and V2 tests it. Remove step 3
from the build order. Also point the builder at `LeagueRepository.scoringOf(leagueId)`
(`:89`) for the scoring map, which the dependency list doesn't name.

**F7 (medium). The counted weeks come from the league's finality and never check
that per-game data exists for them.** (read)

League weeks are final from `league_week_fetch`. `player_game` weeks are a separate
walk with their own state (`sport_week_stats.fin`) and their own failure mode: a
failed week is left un-marked and its rows missing (`PlayerGameIngestService:200-212`).
The chain throws on `weeksFailed > 0` *after* league weeks were already written
(`LeagueRefreshService:190-212`). So a final league week can exist with no per-game
rows. Every player then reads "didn't play" for that week, silently, and production
and `weeksPlayed` drop for everyone at once. The spec's edge case calls 0 a real
"didn't play", which is only true when the week was loaded.

**Amendment**: counted weeks = `finalWeeks` ∩ weeks present in
`SportWeekStatsRepository.forSeason(sport, season)`. If the intersection is smaller,
report it (`weeksMissingGameData`, or a caveat code). Never show a bare 0. Add a
test: a final league week with no `sport_week_stats` row is excluded and named.

**F8 (medium). The UI contract gives one board cell two independent value views,
and the cell has room and styling for one.** (read)

`DraftBoard` draws one `value-delta` span on the meta line, one `value-steal` /
`value-reach` class and one `--vt` tint per cell (`DraftBoard.tsx:158-249`). Cells
sit on a 96px floor at 14 teams, and names are already abbreviated to fit. The
contract says the two toggles are independent and both can be on, so a cell would
need two signed numbers and two tints. Nothing says how. Also, `tintPercent` is
scaled in *picks* (`8 + |Δ|·1.2`, capped at 26, so it saturates at ~15). Value over
slot is in *points* (±400), so reusing it tints every cell at the cap.

**Amendment**: either make the chips mutually exclusive (a segmented "Steals &
reaches / How it played out"), or state the precedence (production wins the cell,
ADP delta moves to the title). Give value over slot its own tint scale, relative to
the draft's spread rather than a picks constant. Add a Vitest test for the
both-on state.

**F9 (low–medium). Clipped windows bias the ends, in basketball measurably.**
(measured)

Production falls steeply in round 1. Pick 1's baseline is the median of picks 2–7
only, so the first picks are compared with picks expected to score less. NBA 2025
mean value over slot: **picks 1–6 +51.9**, picks 7–12 −119.8 (Sabonis and Trae's
injuries), middle −23.2, last 12 +12.0. Pick 1 (Jokić) +152 and pick 2 (Dončić) +150
are steals largely by construction. NFL 2025's first six picks average 0.0 (no
visible bias), so it's sport- and draft-dependent. The plan says "clipped, not
padded" without naming the bias.
**Amendment**: name it in research R4 and in the "How this works" text. Optionally
keep the window size constant at the edges (2k neighbours, shifted inward) rather
than halving it. That's still one rule, still the draft's own picks.

**F10 (low). Unknown weeks in `roster_week_points` aren't handled.** (read)

`breakdownsFor` returns `startersJson` null where Sleeper didn't send starters, and
`playersPointsJson` can be `"{}"`. Its javadoc says callers must treat that as "no
answer for this week", not zero (`RosterWeekPointsRepository:113-117`). FR-005 /
data-model don't mention it, so counted-for-you would quietly count such a week as
0 and `weeksStartedForYou` would undercount.
**Amendment**: those weeks make counted-for-you's coverage partial. Count them
(`weeksUnknownForYou`), or null the field when any counted week is unknown. Test it.

**F11 (low). The access-control IT won't see the new route unless it's added by
hand.** (read)

`AccessControlMvcIT.draftRoutesAre404WithNoIdentity` lists draft routes one by one
(`:153-179`). It doesn't enumerate them. `DraftGradesControllerTest` with a mocked
`visibleDraft` proves the controller calls it, not that the real gate refuses no
header, a blank header and a stranger (lesson #20).
**Amendment**: add `/api/drafts/it-acl-draft/grades` to that IT, for no header,
`"  "` and a stranger → 404, and a member → 200.

## Lower-severity notes

- **N1 (measured + read).** Drafter → roster goes `draft_pick.manager_id`, then
  `roster_season (league, manager_id)`. That's indirect. `manager_id` comes from
  `picked_by` (`PickMapper:24-28`). `roster_season.manager_id` is the roster's
  owner at the last ingest. They agree on every pick today (table above). But:
  - a co-owner or commissioner making the pick breaks it;
  - so does a mid-season ownership change;
  - so does a manager with two rosters, because `forLeague` would then return two
    rows and a `Map` collision silently picks one.

  Sleeper's draft carries the exact `slot_to_roster_id`, and nothing stores it.
  **Suggest**: an ambiguous or missing mapping is `null` plus a counted
  `unmappedPicks`, never a silent pick (lesson #24). Storing `slot_to_roster_id`
  would need a migration, so it's out of scope here. Name it as the known gap.

- **N2 (read).** The signature is `LetterGrades.ranksDescending(items, value,
  ranked)`: three arguments, with a predicate. data-model writes it with two.
  `grade(rank, teamCount)` is an instance method on the `LetterGrades` bean, not
  static.
- **N3 (read).** `DraftGradeProperties` must be added to
  `@EnableConfigurationProperties` in `DraftSimApplication.java:10-22`. That file is
  missing from the plan's file list. Also, a record with a single `Double` field
  binds to a bean whose field is null, not to a null bean. Phrase it as "the field
  is null when the block is absent".
- **N4 (read).** `NoIngestHintsInMessagesTest` scans Java in `api/`, `engine/` and
  `mock/` only (`:29-33`). The reason sentences live in `web/src/draftGrades.ts`,
  which it never reads. The plan's constraint cites it as the guard. Either add a
  Vitest assertion over `draftGrades.ts`'s sentences, or drop the claim.
- **N5 (read).** Naming drift: other payloads call this `gradesEarly` (LeagueAnalysis,
  RosterManagement), and C1 calls it `early`. If the page uses
  `gradesEarlySentence`, it needs the threshold. Send `earlyThresholdWeeks`, or use
  the sentence without a number, rather than a frontend `4` (lesson #25).
- **N6 (read).** `firstWeek`–`lastWeek` in the legend implies a contiguous range.
  `finalWeeks` is a set and can have a gap (a failed fetch). Print `weeksCounted`,
  or the list when it isn't contiguous.
- **N7 (read).** A full window has 2k = 12 neighbours, an even count. Say that the
  median is the mean of the middle two, and test it (a 2-neighbour window).
- **N8 (read).** In a draft with fewer than 10 graded picks, `steals` and `busts`
  can hold the same pick. Exclude steals from busts, or say they may overlap.
- **N9 (read).** A team with no graded picks gets `draftValue` 0 and a rank. That's
  a manufactured 0 (lesson #28). Make it `null`, unranked and ungraded.
- **N10 (read).** `components/TeamStrip.tsx` and the `.team-strip` CSS class already
  exist: the roster-template pills in the live and mock rooms. Give the grade strip
  its own component and class.
- **N11 (read).** `Player.primary()` returns `WR` for an empty positions list. An
  NBA player with no positions would be ranked among "WR" in `positionDrafted` /
  `positionFinish`. Treat empty positions as `position: null`, excluded from the
  positional ranks. The contract already allows null.
- **N12 (read).** The contract's unavailable example has `neighborWindow: 7` for
  (Foot) Ball Knowers 2026, which has 12 teams, so it should be 6.
  `Math.round(teams × 0.5)` rounds 6.5 up to 7 for a 13-team league. State the
  rounding.
- **N13 (read).** Invariant 2 ("`weeksCounted = 0` ⇔ `NO_SCORED_WEEKS`") doesn't
  say what `weeksCounted` holds under `NOT_CONFIGURED` or `DRAFT_NOT_COMPLETE`.
  Define the check order and whether weeks are computed before or after the config
  gate.
- **N14 (read).** The app will have two "player production" rules: superlatives'
  mean per game played (`boundedPlayerGames`) and grades' sum of weekly-average
  games. Both are defensible. Name the difference in research R2 so the next spec
  doesn't merge them by accident.
- **N15 (measured).** Sleeper serves `matchups` for NBA 2025 weeks 22–25 (real
  payloads, ~6 KB each) although the league ended at week 21. The only thing
  keeping them out of `roster_week_points` is the `last_scored_leg` loop bound.
  That's the same lesson as `LeagueHistoryIngestService`'s javadoc. Worth one line
  in R3.
- **N16 (not re-measured: DB down).** These came from the local DB and weren't
  re-checked:
  - `weeksCounted: 21` through `ScoredWeeks` (the API agrees: `lsl 21`, complete);
  - `adp_at_time` coverage (R10);
  - the 672 D/ST rows;
  - the three non-(Foot) NFL leagues in R1;
  - R9's 22 ms;
  - "168 of 168 picks map to a roster" (R6; the API keying table above supports it).
- **N17 (read).** SC-002 ("all 168 picks get a production number") is met
  trivially, because 0 is a number. The API data has **1** NBA 2025 pick with 0
  weeks played (pick 122). Verification should list the 0-week picks by name, so
  "didn't play" is checked rather than assumed.
