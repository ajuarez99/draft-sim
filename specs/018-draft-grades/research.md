# Research: NFL scoring check and draft grades

All measurements are against the local DB (Postgres 5433) on 2026-10-05, `origin/main` @
`c450f67`. Each item says whether it was **measured** or **reasoned**. The SQL used is
summarised inline. It isn't committed, because the Java checks in FR-001 replace it.

## R1. NFL scoring: does `player_game` × `scoring_json` reproduce `players_points`? (measured)

**Method.** For every NFL starter-week in `roster_week_points` (starters ≠ `"0"`, present in
`players_points`), sum `scoring_json[k] × stats[k]` over the league's scoring keys for that
player's `player_game` row(s) in the same season and week. That's the rule
`GameScoringService.score` implements (iterate scoring keys, skip non-numbers, round to
cents), re-written in SQL.

| League | Season | Starter-weeks | Exact | Mismatch | No game row |
|---|---|---|---|---|---|
| (Foot) Ball Knowers (5) | 2025 | 2,039 | 2,023 | 0 | 16 |
| West Coast FF (9466) | 2025 | 2,158 | 2,120 | 0 | 38 |
| (Foot) Ball Knowers (4) | 2026 | 360 | 360 | 0 | 0 |
| West Coast FF (9465) | 2026 | 420 | 413 | 0 | 7 |
| fantasy😍 (3) | 2026 | 420 | 418 | 0 | 2 |

All 63 starter-weeks with no game row were credited **0.0** (29 WR, 15 RB, 12 TE, 6 K,
1 QB): inactive or bye. D/ST rows exist in `player_game` (672 rows keyed by team code) and
match. No NFL player-week had more than one game row. That fits how the table is written:
`player_game` holds only played games, and non-plays go to `ENTRY_WITHOUT_PLAY` absences via
`SportRules.playedIn` (`PlayerGameIngestService.java:248`).

**Decision.** The design doc's acceptance #1 ("≥10 sampled starter-weeks") is met 500×
over, in SQL. But a SQL copy of a rule is a second implementation, and that's the thing
this repo keeps getting bitten by. So FR-001 still requires the **Java** to pass:

1. `NflScoringParityTest`: a pure test over a committed fixture of real rows (one league's
   `scoring_json`, ~40 starter-weeks covering QB/RB/WR/TE/K/DEF and a 0-point inactive,
   with Sleeper's credited points). Can't skip.
2. `NflScoringParityIT`: every stored starter-week through `GameScoringService`, asserting 0
   mismatches and printing the three counts. Skips without Postgres, so check the skip
   count (memory: backend suite skips ITs silently).

**Alternatives rejected.** Treat the SQL as the check: rejected for the reason above.
Sample 10: no reason to sample when all 5,334 run in under a second.

## R2. Basketball: what is a player's "production"? (measured, then decided)

**Measured.** In both NBA leagues (2024 id 212, 2025 id 211), `starters_points` equals the
sum of the starters' `players_points` in **540 of 540** roster-weeks. Each starter's
`players_points` is one game's score:

| League | Multi-game starter-weeks | = one of the games | = sum | = first | = last |
|---|---|---|---|---|---|
| 211 (2025) | 2,171 | 2,153 | 0 | 660 | 935 |
| 212 (2024) | 2,429 | 2,386 | 0 | 835 | 997 |

So the league credits **one game per starter per week**, and which game isn't a fixed rule
(memory had found the same: not first, last or highest). The 18 + 43 that match no game are
probably stat corrections, but that's a guess.

Three candidate production numbers, compared on players who started ≥ 8 weeks, against the
sum of their credited points:

| Candidate | 211: ratio to credited | corr | 212: ratio | corr |
|---|---|---|---|---|
| Sum of every game | 2.54 | 0.982 | 2.60 | 0.977 |
| Sum over weeks of the week's average game | 0.84 (sd 0.06) | 0.987 | 0.88 (sd 0.08) | 0.983 |

The credited game beats the week's average by ~14–19%. Something picks a better-than-average
game (maybe the manager, maybe a rule), and this research didn't find out what.

**Not the superlatives rule (review N14).** Superlatives rank by mean per game played, and a
missed week costs nothing there. Here it costs the week. Keep them separate.

**Decision.** Production = Σ over final weeks of the player's **average** league-scored game
that week (weeks with no game add nothing). One rule for both sports:

- Football: one game a week, so the average *is* the game, and R1 shows it's exactly what
  Sleeper credits.
- Basketball: the closest roster-independent stand-in for "one credited game a week" that
  can be computed. It tracks credited points at r ≈ 0.98–0.99, but runs ~12–16% low.

The payload carries `productionBasis`: `WEEKLY_GAME` (NFL) or `WEEKLY_AVERAGE_GAME` (NBA).
The UI labels the NBA column "season points, counting each week's average game" (amended
after review F5, from "avg game per week played", which read as a per-week figure). It never
calls it league points, and it states one-game crediting as measured in two leagues, not
as a rule of every basketball league.
Value over slot compares picks within one draft, and every pick is scaled the same way, so
the ~0.86 factor doesn't change who's a steal.

**Alternatives rejected.** Sum every game: ~2.5× what the league awards, and it rewards
4-game weeks that these leagues don't credit. Credited points only (`players_points`):
exact, but only for weeks a player sat on some roster, so a waiver breakout's free-agent
weeks vanish. That number is kept as counted-for-you (R6), not production. Model which
game Sleeper picks: not determined, and guessing would be bug class #8.

**Side finding, outside this spec.** `claude/nba-schedule-grid-and-streaming.md` and spec 017
US1 say a player's week is "roughly per-game value × games played" and a 4-game team is
"worth ~2×" a 2-game one, citing this same memory. The measurement says a starter's week is
credited one game. More games may still help (more chances at a good credited game: the
credited game beats the average by ~16%), but "~2×" isn't right for these leagues. This
branch adds a dated amended note to that doc. Whether the grid's framing changes is a
separate decision for Allan, not this spec.

## R3. Which weeks count? (reasoned, with one measurement)

> **Amended after review (F7, N15):** counted weeks are `finalWeeks` **∩ weeks with
> `sport_week_stats` rows**, so a failed per-game fetch can't read as "nobody played".
> Sleeper also serves matchups for NBA 2025 weeks 22–25. Only the history ingest's
> `last_scored_leg` loop bound keeps them out of `roster_week_points`.

**Decision.** `ScoredWeeks.of(leagueId).finalWeeks()`: the league's final, stored weeks,
regular season and playoffs.

- NBA 2025 has `player_game` weeks 1–25, but the league's last scored leg is 21. Weeks 22–25
  are real games the league never scored, so they're excluded.
- NBA week numbers in `player_game` and `roster_week_points` line up: the R2 join on equal
  week numbers matched 2,153 of 2,171 multi-game starter-weeks. (measured)
- An in-progress NFL or NBA week is excluded. That's the same rule as "N weeks scored"
  everywhere else (`ScoredWeeks` javadoc).

**Alternatives rejected.** Regular season only: fantasy playoffs are when picks matter most.
Every `player_game` week: counts games the league never scored.

## R4. Slot baseline (reasoned; one prototype run)

> **Amended after review, 2026-10-06 (F1, F9).** The all-positions ±k window below was
> replaced by **positional neighbours**: the median of the 3 same-position picks drafted
> just before and the 3 just after, shifted inward at the ends. The review measured the
> all-positions rule on NFL 2025: the top 5 steals were all QBs (Maye, Stafford, Caleb
> Williams, Dak, Allen), 11 of the top 20 were QBs, and QB picks averaged +94.9. In
> basketball it also biased the ends (picks 1–6 averaged +51.9). Allan chose positional
> neighbours over replacement level and over keeping the rule. The original decision and
> prototype are left below as a record. Their numbers are no longer expected values.

**Decision.** `baseline(pick) = median production of the other graded picks with
|pick_no − pick| ≤ k`, where `k = round(teams × neighborWindowFraction)`. The fraction is
**0.5** (the design doc's `teams/2`), in `config/weights.yml` under `draftsim.draft-grades`,
labelled ARBITRARY. Self is excluded, so a pick isn't measured against itself. Windows are
clipped at the draft's ends, not padded.

**Prototype (NBA 2025, k = 6), measured in SQL.** Top steals: Jamal Murray (pick 43, +198),
Donovan Clingan (114, +197), Kon Knueppel (168, +181). Busts: Walker Kessler (57, 2 weeks
played, −405), Trae Young (9, 6 weeks, −397), Sabonis (8, 8 weeks, −367). Busts are mostly
injuries. That's intended: production counts missed weeks as zero. `weeksPlayed` is shown
beside it so the reader can tell an injury from a bad season.

**Alternatives rejected** (originally; see the amendment above). A fitted points-by-pick curve: one draft is 168–210 points, so
the fit is mostly noise (design doc). Mean instead of median: one injury drags a whole
window.

> **Amended during build, 2026-10-06: the baseline is a per-position log fit** (Allan's
> choice, after measuring three rules on real data). Built as specified, the positional
> neighbours rule made the first player taken at each position a steal by construction: his
> whole window is later, worse picks. NBA 2025 had Jokić (#1) and Dončić (#2) as the 2nd and
> 4th biggest steals, and picks 1–6 averaged **+106.7**, worse than the +51.9 the review
> measured under the original rule. A rank-matched rule (the k-th drafted at a position vs.
> the k-th best finisher) flipped the bias the other way: NBA's last 12 picks averaged +227.
> The per-position fit `production ≈ a + b·ln(pickNo)` kept both ends near balanced (NBA
> picks 1–6 +14.7, last 12 +56.7; NFL picks 1–12 +0.9, last 12 −21.8, QB mean 0.0, all
> measured in SQL). The design doc ruled out "a fitted points-by-pick curve" as noise. That
> was one curve over a whole draft. This is two parameters per position, with a minimum-picks
> guard. `neighborsPerSide` is replaced by `minPicksPerPosition`.

## R5. Positional view (reasoned)

**Decision.** Two ranks per pick among same-position graded picks in this draft: the order
drafted (`positionDrafted`) and the order by production (`positionFinish`). Position is
`Player.primary()`, the rule the board already uses. Basketball multi-eligibility makes this
coarse, and the label says "by primary position". It's a pair of integers, not a third score.

## R6. Counted for the drafting team (measured)

> **Amended after review (F2, F3, F10, N1):** counted-for-you now uses production's weekly
> rule, not `players_points`. In NBA, credited > average made it exceed production for 44 of
> 168 picks. Sleeper's credited sum is kept as `creditedForYou`. Weeks with a null
> `matchup_id` (playoff byes, eliminated teams) don't count, and that affected 31 NBA 2025
> and 44 NFL 2025 picks. Unknown starter-weeks are counted as unknown. A manager who maps
> to zero or several rosters is null, never a silent pick. The paragraph below is the
> original decision.

**Decision.** Map the pick's `manager_id` to `roster_season (league_id = draft.league_id,
manager_id)`. Counted-for-you = Σ `players_points[pid]` over final weeks where `pid` is in
that roster's `starters`. That's Sleeper's own credited number, exact in both sports. It's
null (unknown) if the manager is null or has no roster row.

NBA 2025, measured: 168 of 168 picks map to a roster. 57 picks started for a different
roster at least once. 32 never started for their drafter. So US3's test has plenty of cases.

**Alternatives rejected.** Count bench weeks too: bench points don't count for anyone.
Follow trades back to the drafter: that's roadmap 2.5's ownership timeline, not this.

## R7. Team grade (reasoned; reuse)

**Decision.** Team draft value = Σ value over slot of its graded picks. *Amended after review (F4): minus
the draft's average team, because skewed production made NBA 2025 sum to −3,600.*
`LetterGrades.ranksDescending` (ties share a rank) then `LetterGrades.grade(rank, teams)`
from the existing `draftsim.grades` cutoffs. `early = SeasonWindow.isEarly(weeksCounted)`.
Best and worst pick per team by value over slot (ties → lower pick number). Nothing new is
hand-set except R4's fraction.

## R8. Surface (reasoned)

**Decision.** A new `GET /api/drafts/{sleeperDraftId}/grades`, not new fields on
`/board`.

- `/board` and the live stream share `PickNaming`. Adding a ~10k-row read and a league
  lookup to it would cost every live tick. A separate endpoint costs only the view.
- The page fetches grades when the "How it played out" toggle is first turned on, then
  keeps them.
- The toggle shows only when `status` is complete. When the endpoint says unavailable, the
  view shows its reason, not an empty table.

Controller: a new `DraftGradesController` beside `LeagueController`, using
`membership.visibleDraft` exactly as `realBoard` does (`LeagueController.java:301`).

## R9. Cost (measured)

NBA 2025: the graded players' `player_game` rows for the season are 9,775 rows, read in
22 ms (`psql`, warm). The 210-pick NFL drafts read fewer (one game a week). Computing on
read, without storing anything, is the decision. SC-005 asks verification to time the real
endpoint.

## R10. Reach and ADP (measured)

`adp_at_time`: NBA 2024/2025 0/168, NFL 2025 0/180 each, NFL 2026 drafts 180/180, 210/210,
210/210. The existing steals & reaches toggle already hides itself with no ADP
(`hasAnyAdpAtDraft`). FR-008 leaves it as it is. The two views are separate toggles because
they answer separate questions (draft-day value vs. outcome).

## R11. Early season (reasoned)

`SeasonWindow.EARLY_THRESHOLD_WEEKS = 4` (ARBITRARY, already labelled). NFL 2026 has 3 final
weeks today, so every NFL 2026 grade is early until week 4 is final. The view leads with
"early — N weeks played", and the team grade column carries `GradesEarlyBadge`.
