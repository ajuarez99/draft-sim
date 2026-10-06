# Data model: NFL scoring check and draft grades

> **Amended after review, 2026-10-06.** [plan-review.md](plan-review.md) F1–F11 and N1–N17
> changed this file substantially. Dispositions are in
> [plan.md](plan.md#amended-after-review-2026-10-06). The main changes:
> - the baseline is now **positional** neighbours (F1, Allan's choice), and it's shifted
>   inward at the ends (F9);
> - counted-for-you is on production's basis, and Sleeper's credited sum is its own field
>   (F2). Only weeks with a matchup count (F3), and unknown weeks are counted, not zeroed
>   (F10);
> - team value is centred on the draft's average team (F4);
> - counted weeks must also have per-game data (F7);
> - there's no new repository method (F6).

> **Amended after code review, 2026-10-06** ([code-review.md](code-review.md)):
> - **B1, the fit group is a per-sport rule.** Sleeper sorts NBA `fantasy_positions`
>   alphabetically (all 1,679 multi-position NBA lists in the DB). Its single `position` field
>   isn't reliable either: Quentin Grimes is "PG" there but SG-only in `fantasy_positions`. So
>   "first position" made Edwards, Booker and Mitchell PGs, left 1–2 SGs per draft below the
>   8-pick minimum, and dropped SG-only picks from team value. NBA lineups are 4 flexible slots
>   of 9 (G, F, UTIL×2), so a basketball player has no single position to group by.
>   **Football groups by position** (F1's QB problem is a football problem). **Basketball fits
>   one curve over the whole draft.** That's a new no-default `SportRules.draftGradeGroup(Player)`:
>   football returns `positions[0]` or null, basketball returns `"ALL"`. Positional ranks follow
>   the same group. In basketball they're labelled "drafted Nth, finished Mth among this
>   draft's picks", and `position` shows his eligibility joined ("PG/SG"), as display only.
> - **B3:** a week counts only if `sport_week_stats` has it **final** (`fin`), not just present.
> - **B4:** positional-finish ties compare values rounded to 2 dp, then the lower pick.
> - **B5:** a roster with no matchup row in a week other rosters played is a bye (no game, not
>   counted). It isn't counted as unknown, because `LeagueMatchupRepository.between` filters out
>   null `matchup_id`, so null-matchup and missing rows look the same through it, and F6 rules
>   out a new method. A week with no fixture for any roster is unknown for everyone. This
>   replaces F10's "no league_matchup row → unknown".

**No migration.** Everything is computed on read from tables that already exist. Nothing
new is stored (research R9).

## Read from

| Table / method | Used for |
|---|---|
| `DraftRepository.bySleeperId` / `picks` | the draft (`league_id`, `season`, `teams`, `status`) and its picks (`pick_no`, `round`, `draft_slot`, `manager_id`, `player_id`) |
| `PlayerRepository` (by ids) | `sleeper_id`, `name`, `positions` |
| `LeagueRepository.byId`, `scoringOf(leagueId)` (`:89`) | sport, season, scoring map |
| `PlayerGameRepository.forPlayers(sport, season, ids)` (`:171`, **existing**, F6) | every game of the graded players this season. Weeks are filtered in Java, as `SeasonSuperlativesService.boundedPlayerGames` (`:1104`) does |
| `ScoredWeeks.of(leagueId).finalWeeks()` | the league's final weeks |
| `SportWeekStatsRepository.forSeason(sport, season)` | which weeks have per-game data (F7) |
| `RosterSeasonRepository.forLeague(leagueId)` | manager → roster |
| `RosterWeekPointsRepository.breakdownsFor(leagueId, season)` (`:129`) | starters and credited points per roster-week |
| `LeagueMatchupRepository.between(leagueId, season, from, to)` (`:63`) | whether a roster had a game that week (F3) |

## Counted weeks (FR-003, F7)

```
counted     = finalWeeks ∩ { w : sport_week_stats has a row for (sport, season, w) }
missingGame = finalWeeks \ counted          → weeksMissingGameData (a sorted list, usually empty)
```

A final league week with no per-game data is never read as "everyone didn't play". It's
left out and named. The NBA weeks after the league's last scored leg are never in
`finalWeeks` (research R3, N15).

## Production (FR-002, R2)

```
weekValue(p, w) = mean over p's player_game rows in week w of GameScoringService.score(scoring, stats)
                  (no rows → p didn't play in w; player_game holds only played games)
production(p)   = Σ_{w ∈ counted} weekValue(p, w)
weeksPlayed(p)  = |{ w ∈ counted : p has rows in w }|
```

`productionBasis` = `WEEKLY_AVERAGE_GAME` when `SportRules.playsMultipleGamesPerScoringPeriod()`
is true, else `WEEKLY_GAME`. That's an existing no-default rule (`SportRules.java:65`). The
code is the same either way.

**Not the superlatives rule (N14).** `SeasonSuperlativesService` ranks by mean points per
game played. Grades use a season sum of weekly-average games, so a missed week costs here
and doesn't there. Both are deliberate. Don't merge them.

## Position (R5, N11)

`position(p)` = the first entry of `player.positions`, or **null** when the list is empty.
This deliberately isn't `Player.primary()`, which falls back to WR. A pick with a null
position is still graded on production, but gets no baseline and no positional ranks
(below), and is counted in `unpositionedPicks`.

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

## Value over slot: per-position log fit (FR-004, F1, F9; amended during build)

```
m          = minPicksPerPosition                    (config, ARBITRARY, 8)
P(pos)     = graded picks with position = pos       (non-null positions only)
fit(pos)   = ordinary least squares of production on ln(pickNo) over P(pos):
             b = cov(ln pickNo, production) / var(ln pickNo),  a = mean(production) − b·mean(ln pickNo)
             no fit when |P(pos)| < m, or var(ln pickNo) = 0
slotBaseline_i  = a + b·ln(pickNo_i)              null when pos has no fit
valueOverSlot_i = production_i − slotBaseline_i   null when the baseline is null
```

The pick itself is part of its position's fit, and that's deliberate. Within each fitted position
the values sum to 0 (OLS residuals), so no end of the draft is favoured by construction. Pick 1
isn't compared only with later picks.

*Superseded, kept as a record (positional neighbours):*

## (superseded) Value over slot: positional neighbours (FR-004, F1, F9, N7)

```
n          = neighborsPerSide                       (config, ARBITRARY, 3)
samePos(i) = graded picks j ≠ i with position(j) = position(i), ordered by pickNo
window(i)  = the n same-position picks drafted just before i and the n just after.
             At an end, the window shifts inward to keep 2n picks when samePos has them
             (F9: no half-window at the ends). If |samePos(i)| < 2n, it's all of them.
slotBaseline_i  = median(production over window(i)); the median of an even count is the
                  mean of the middle two. null when |window(i)| < 2.
valueOverSlot_i = production_i − slotBaseline_i      (null when the baseline is null)
```

A pick is compared with the players **at his position** drafted around him, so a late QB is
measured against other late QBs, not the WRs taken beside him (review F1: NFL 2025's top 5
"steals" were all QBs under the all-positions rule). Counting neighbours by position, not
by pick distance, also handles sparse positions (QB, K, DEF), whose nearest same-position
picks can be rounds away.

Positional ranks: among graded picks with the same non-null position, `positionDrafted` =
1-based order by pick number, and `positionFinish` = 1-based order by production, descending
(ties → lower pick number).

## Counted for the drafting team (FR-005, F2, F3, F10, N1)

```
roster(i)   = the one roster_season row with league_id = draft.league_id and manager_id = pick.manager_id
              null when manager_id is null, or when there's no row or more than one row  → unmappedPicks (N1)
started(i,w)= w ∈ counted, roster(i)'s league_matchup row for w has a non-null matchup_id (F3),
              and p ∈ that roster's starters for w
unknown(i,w)= w ∈ counted, and the roster-week's starters are null or its points are "{}",
              or there's no league_matchup row for (roster, w)            (F10)

countedForYou_i      = Σ_{w : started(i,w)} weekValue(p, w)    production's basis, so countedForYou ≤ production (F2)
creditedForYou_i     = Σ_{w : started(i,w)} players_points[p]   Sleeper's credited number, exact
weeksStartedForYou_i = |{ w : started(i,w) }|
weeksUnknownForYou_i = |{ w : unknown(i,w) }|
```

All four are null when `roster(i)` is null. `creditedForYou` is never drawn next to
`production` without saying it's on a different scale. In football it equals
`countedForYou` (R1). In basketball it runs ~15–18% higher (R2).

**Known gap (N1).** Sleeper's draft has an exact `slot_to_roster_id` that isn't stored.
Going through `manager_id` matched every pick in every completed draft today (review,
measured), but a co-owner's pick or an ownership change would fall out as unmapped. Storing
it would need a migration, so it's out of scope here.

## Team (FR-006, F4, N9)

```
rawValue(team)  = Σ valueOverSlot over the team's picks with a non-null value
                  null when the team has none (N9: unranked, ungraded)
average         = mean rawValue over teams with a non-null rawValue
draftValue(team)= rawValue(team) − average           "vs. the average team in this draft" (F4)
rank            = LetterGrades.ranksDescending(teams, t -> t.draftValue, t -> t.draftValue != null)
grade           = letterGrades.grade(rank, rankedTeamCount)    (instance method, N2; null without cutoffs)
bestPick / worstPick = max / min valueOverSlot (ties → lower pickNo)
```

Centring changes no rank and no letter. It makes the sign mean what a reader assumes:
above or below this draft's average team. Before centring, NBA 2025 summed to −3,600, and 8
of 12 teams were negative (review F4, measured). Teams are keyed by `draft_slot`.

## Steals and busts (US4, N8)

`steals` = top 5 graded picks by `valueOverSlot` (nulls excluded; ties → lower pickNo).
`busts` = bottom 5, **excluding any pick already in `steals`**.

## States and check order (FR-010, N13)

Checked in this order. The first match answers.

| # | Condition | `available` | `reason` | weeks fields |
|---|---|---|---|---|
| 0 | draft not visible to the caller | — | 404, same as `/board` | — |
| 1 | `draft-grades` config field absent | false | `NOT_CONFIGURED` | `weeksCounted` 0, lists empty |
| 2 | `draft.status` ≠ `complete` | false | `DRAFT_NOT_COMPLETE` | 0, empty |
| 3 | `counted` is empty | false | `NO_SCORED_WEEKS` | 0, `weeksMissingGameData` filled if final weeks exist but lack game data |
| — | otherwise | true | null | |

Weeks are only computed from step 3 on. Reasons are codes. The sentences live in
`web/src/draftGrades.ts`, and a Vitest asserts they contain no ingest route (N4: the Java
`NoIngestHintsInMessagesTest` doesn't scan web code).

## Config (N3)

```yaml
draftsim:
  draft-grades:
    # ARBITRARY: not fitted. A fit group (a football position) with fewer graded
    # picks than this gets no baseline. (Amended during build; was neighbors-per-side.)
    min-picks-per-position: 8     # amended during build: replaces neighbors-per-side
```

`DraftGradeProperties(Integer minPicksPerPosition)` (amended during build; validated 3 ≤ m ≤ 30) is registered in
`DraftSimApplication`'s `@EnableConfigurationProperties`. `weights.yml` is an optional
import (`application.yml:23`), so when the block is absent the bean exists with a **null
field**, startup succeeds, and C1 answers `NOT_CONFIGURED`. There's no silent default. A
present value is validated (3 ≤ m ≤ 30, amended during build) and a bad one fails startup. `/api/health` adds
`draftGradesLoaded` (a boolean, so its `Map.of` is safe).
