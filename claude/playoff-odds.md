# Playoff odds: making `makesPlayoffsPct` real

Status: **built 2026-09-14** (V13 + `PlayoffOddsSimulator` / `PlayoffOddsService`,
verified live). The wire field, three render sites and a ladder column had existed
since the reskin and had always shown `--`; this brief is the backend that fills
them in, the UI decisions that came with a number that is finally not null, and
(at the bottom) the two things live verification found that no test would have.

## What was there before this shipped

| Piece | Where | State |
|---|---|---|
| `makesPlayoffsPct` on the entry type | `web/src/api.ts:719` | client-side type only — **no backend field exists** |
| `pctLabel()` | `PowerRankings.tsx:259` | every site degrades to `--`; "no data" and "0%" can never be confused |
| Hero "MAKES PLAYOFFS" stat | `PowerRankings.tsx:787` | renders `--` |
| Your-team strip "`--` to make it" | `PowerRankings.tsx:806` | renders as a broken fragment today |
| Ladder column "Makes playoffs" | `PowerRankings.tsx:928` | Box score + Commissioner only; a full column of `--` with an empty bar |
| Footnote "Playoff odds come from simulating the rest of the season" | `:1054`, `:1360` | **false today** — nothing simulates anything |

The League-vote ladder no longer has this column at all: its slot was recycled
into the ballot-spread row, and as of `a8afa79` that cell is the "Ballot range"
pill. Putting odds back on that tab is a layout decision, not a revert — see
[Where the number goes](#where-the-number-goes).

## The one real gap: we do not store the schedule

Everything else the simulation needs is already in Postgres. The schedule is not:

- `roster_week_points` (V5) stores `starters_points` and `players_points` per
  (league, season, week, roster) but **drops `matchup_id`** — so we know what
  every team scored and not who they played.
- `LeagueHistoryIngestService.ingestWeeklyPoints` only walks weeks
  `1..last_scored_leg`, so nothing about *future* weeks is ever fetched.

Verified live against (Foot) Ball Knowers (`1346366555759341568`) on 2026-09-14:
`GET /league/{id}/matchups/5` for an unplayed week returns all 12 rosters with
`matchup_id` set and `points: 0.0`. **The full remaining schedule is one call per
week**, and the pairing is the `matchup_id` grouping, exactly as it is for a
played week.

Also verified on that league: `playoff_teams: 6`, `playoff_week_start: 15`
(regular season = weeks 1-14), `playoff_seed_type: 0`, `league_average_match: 0`,
`divisions: null`. All of it already lands in `league.settings_json` (V1).

## Storage (V13)

```sql
-- The schedule, including weeks not yet played. Separate from
-- roster_week_points on purpose: that table is a cache of RESULTS keyed to
-- last_scored_leg, this one is a cache of FIXTURES that extends past it.
create table league_matchup (
    id          bigserial primary key,
    league_id   bigint not null references league (id) on delete cascade,
    season      int    not null,
    week        int    not null,
    roster_id   int    not null,
    matchup_id  int,               -- null = no game that week (bye/odd count)
    unique (league_id, season, week, roster_id)
);
create index league_matchup_lookup_idx on league_matchup (league_id, season, week);

-- One odds snapshot per (league, season, week), same snapshot discipline as
-- power_ranking: "up 8 points since last week" needs history, and the inputs
-- (records, the schedule, a team's own scoring) move underneath you.
create table playoff_odds (
    id          bigserial primary key,
    league_id   bigint not null references league (id) on delete cascade,
    season      int    not null,
    week        int    not null,
    iterations  int    not null,
    model       text   not null,   -- version tag, e.g. 'shrunk-normal-v1'
    created_at  timestamptz not null default now(),
    unique (league_id, season, week)
);

create table playoff_odds_entry (
    id             bigserial primary key,
    odds_id        bigint not null references playoff_odds (id) on delete cascade,
    roster_id      int    not null,
    made_pct       numeric(5,2) not null,
    bye_pct        numeric(5,2),          -- null unless the format has byes
    seed_one_pct   numeric(5,2),
    proj_wins      numeric(5,2) not null,
    proj_points    numeric(9,2) not null,
    unique (odds_id, roster_id)
);
```

Storing `proj_wins`/`proj_points` costs nothing and is what a "projected finish"
column or a future story card would need; `bye_pct`/`seed_one_pct` are nullable
because not every league has a bye.

## The model

**Strength.** For roster *i*, from that season's `roster_week_points`:

- `mu_i` = mean weekly `starters_points`, shrunk toward the league mean:
  `mu_i = w*mean_i + (1-w)*leagueMean`, `w = n/(n+4)`.
- `sigma_i` = per-roster stdev shrunk the same way toward the league-wide stdev
  (a 1-2 game sample has a meaningless stdev of its own).

`k = 4` is the knob: at n=4 a team is half its own average, at n=12 it is 75% its
own. No mapping from the week-0 board baseline into points-per-week; that mapping
would be invented, and inventing it is exactly the kind of thing this page refuses
to do elsewhere.

**A league with no scored games at all gets no snapshot.** The original plan here
said n=0 would leave every team at the league average and let schedule and
standings do the talking. Run against a real preseason league, that is wrong: with
no scoring there is no mean and no variance either, every simulated game ends 0-0,
every team finishes identical, and the tiebreak hands the playoff spots to whoever
sorted first. The honest answer before week 1 is scored is no answer -- see
[What live verification found](#what-live-verification-found).

**Simulation.** Per iteration:

1. Seed each roster with its Sleeper record and `points_for` from `roster_season`
   — Sleeper's own numbers, not a recount from matchups. A recount silently
   disagrees in median-scoring and hand-corrected leagues.
2. For each remaining regular-season week (`currentWeek..playoff_week_start-1`),
   for each `matchup_id` pair, draw both scores from `Normal(mu, sigma)` clamped
   at 0, award the win to the higher score, accumulate points. A roster with a
   null `matchup_id` that week does not play.
3. If `league_average_match == 1`, each team plays a second "game" against that
   week's simulated median — the setting exists on every league object and
   silently doubles the number of games when it is on.
4. Sort by wins (ties = 0.5), then `points_for`. This is Sleeper's default
   standings order; it is **not** every league's seeding rule (divisions, H2H
   tiebreaks, `playoff_seed_type != 0`), so Honesty below says what we do about it.
5. Top `playoff_teams` made it; track seed 1 and (when the bracket has them) byes.

**Cost.** 10k iterations x 12 rosters x ~13 weeks is about 1.6M draws — tens of ms
in Java, nothing like the draft sim's 500-iteration cap
(`claude/reactive-resimulation.md`). Computed on the existing commissioner
`POST /power/compute` path only -- never on a page load, and never lazily on read:
a GET that writes a snapshot is a GET that can disagree with itself under two
concurrent readers, and this page is read far more than it is recomputed.

## Honesty rules

These are the same rules that made `pctLabel()` exist, applied to a number that
is now real:

- **Never show odds for a league we cannot seed correctly.** If
  `settings.divisions` is set, or `playoff_seed_type != 0`, we do not model that
  league's tiebreaks — store nothing and keep rendering `--`. A wrong 78% is
  worse than a dash.
- **Say what the number is made of.** The footnote gets rewritten from the
  current false sentence to the actual one, including the iteration count and
  how many games of scoring the strength estimate is standing on
  ("10,000 seasons, from 2 weeks of scoring" reads very differently from
  "from 11 weeks", and it should).
- **0% and 100% are real answers** once a team is mathematically in or out, and
  must not be confused with the dash's job — `pctLabel` already keeps them
  distinct.
- Regular season over (`currentWeek >= playoff_week_start`): the question is
  answered, not simulated. This falls out for free -- there are no remaining
  fixtures, so the standings decide every iteration and the stored numbers are
  0/100. Verified on the 2025 season.
- **Refusing also deletes.** Whatever a week is refused for -- an unmodelable
  format, no scoring yet -- any snapshot already stored for that week is removed.
  A stale answer outlives every reason it was written.

## Where the number goes

The League-vote ladder is at seven columns and was just cleaned up precisely
because its right end was unreadable; an eighth column undoes that work.

**Decided (Allan, 2026-09-14):** odds ride in the **Record** cell, on all three
tabs — `0-0` with a small tinted odds pill under it. Record and odds are both
"how this team's season is going", neither is about the ranking mode, the column
already exists on every tab, and it costs zero horizontal space. The standalone
"Makes playoffs" column on Box score/Commissioner **goes away** rather than being
a second place the same number lives (the multi-sport lesson from
`claude/multi-sport-landmines.md`: two implementations of one rule is the class
of bug to avoid, and two renderings of one number is the same shape).

That frees a ~150px track on the Box score and Commissioner templates. It goes
back to the note column ("Where the room had them"), which truncates today.

Either way the hero stat and the your-team strip (`-- to make it`) light up for
free — they already read the field.

## Sport gate

`PowerRankingService.nflState()` hardcodes `sleeper.state("nfl")`, and
`LeagueHistoryController:421` already refuses basketball for the computed mode
because of it. Playoff odds inherit that gate — NFL first. When basketball comes,
the fix is to make *that* method take the league's sport (`/state/nba` returns the
same `week`/`leg`/`season` shape — verified 2026-09-14), **not** to add a second
odds path for NBA.

## Not building

- Anything player-level: injuries, bye weeks, trades, waiver adds, or a roster's
  remaining strength of schedule by opponent quality. The model is team-level
  scoring, and the footnote will say so.
- The playoff bracket itself (championship odds). `bye_pct`/`seed_one_pct` are
  stored so it can come later without a migration.
- Backfilling odds for past weeks/seasons — snapshots start the day it ships.

## What live verification found

Two bugs, both invisible to the unit tests, both caught by pointing the thing at a
real league (the pattern `claude/power-rankings-ballots.md` ends on, for the same
reason):

1. **100% and 0%, from nothing.** The first real run was against a preseason
   league whose week 1 is not scored yet. Every roster got `mu = 0, sigma = 0`, so
   every simulated game was a 0-0 tie, all twelve teams finished identical, and the
   comparator handed six of them 100% and six 0% -- stated with total confidence,
   from zero games of evidence. The percentages even summed to 600, so acceptance
   criterion 2 passed while the output was meaningless. Fixed by refusing to write
   a snapshot when no week has been scored; pinned by
   `PlayoffOddsServiceTest.aLeagueWithNoScoredGamesGetsNoSnapshot`.
2. **`BigDecimal` is not `Double`.** `numeric` columns come back as `BigDecimal`,
   and `(Double) rs.getObject(...)` on the nullable `bye_pct`/`seed_one_pct`
   columns threw `ClassCastException` -- which took out the entire power-rankings
   endpoint (500 on every league, not just leagues with odds) the moment the first
   snapshot existed to read back.

A third, smaller: the odds summary was looked up with the CURRENT NFL week, so a
past season's page asked "any odds at or before week 1?" about a season whose odds
it was displaying, and answered no. It now asks for the newest snapshot in that
season, unbounded.

## Acceptance criteria

1. `GET /leagues/{id}/power` returns a real `makesPlayoffsPct` per entry for an
   NFL league mid-season, and the three existing render sites show it with no
   client change beyond the Record-cell layout.
2. The odds for a league sum to `playoff_teams * 100` (within rounding) — the
   single cheapest check that the seeding step is not double-counting.
3. A league with `divisions` set stores no snapshot and still renders `--`.
4. Re-running compute for the same week replaces that week's snapshot and leaves
   earlier weeks untouched.
5. The footnote names the iteration count and the weeks of scoring behind it.
6. Backend suite passes with Postgres actually up — `claude/lessons.md`: BUILD
   SUCCESSFUL with 52 skipped means the ITs never ran.

All six hold as of 2026-09-14: 372 backend tests, 0 skipped, 0 failures, and 206
web tests; odds for (Foot) Ball Knowers 2025 summed to exactly 600.0 across 12
rosters with 6 playoff spots; the 2026 league (no scored week yet) stores nothing,
renders `--`, and says nothing about a simulation.

## Decisions (Allan, 2026-09-14)

1. **Record cell, not a dedicated column** — and the standalone "Makes playoffs"
   column comes out of Box score/Commissioner when this lands.
2. **Division and non-default-seed leagues get `--`.** No snapshot is stored for
   them at all, so there is never a stale wrong number to explain later.
3. **`k = 4`** stands as the shrinkage default — it is one constant in one place
   and the model tag (`shrunk-normal-v1`) exists so a retune is a new snapshot,
   not a silent rewrite of history. Revisit once there is a real mid-season
   league to look at.
