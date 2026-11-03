# Historical draft game ("Draft a random year")

> **Superseded (2026-11-02)** by `specs/053-historical-draft-game/`. Three points
> here turned out wrong or changed. Sleeper's ADP failed a hindsight-leak check, so
> every season uses the fallback ranking. The era rule became "tracked by the league
> and ≥ 90% coverage, gaps as 0". The board ranks by per-game points, best of two
> seasons. See the spec's plan.md and research.md.

Status: **design only, nothing built.** Written 2026-11-02 from a conversation with
Allan and a data-availability pass run the same day. Every number in "What the data
actually supports" was measured in that pass unless it says otherwise; the measuring
scripts were throwaways and live outside the repo.

## The idea, in one paragraph

You get a random NBA season (1970-71 to now). You draft against bots in the normal
Ball Knowers draft room (snake order, your league's roster slots and scoring), but
the only thing you can see about each player is what a real drafter knew going in:
**the previous season's stats** (plus age and team). The season you're drafting for
stays hidden. After the draft, the real season replays as best ball: each week your
top scorers start automatically, scored from the actual box scores, and you get H2H
results, a final standings table, and how your draft compared with the bots and a
perfect-hindsight draft.

## Decisions already made (Allan, 2026-11-02)

- **Same draft style as the existing draft room** — snake, real roster slots, bots.
  Not a new format.
- **Player cards show previous-season stats.** Showing the drafted season's own
  stats would make the game "sort by points."
- **Best ball after the draft**: no lineup decisions, top-N by slot per week.
- **All years back to 1970**, not only the seasons Sleeper covers.
- **Real ADP where it exists** (Allan suggested Yahoo for recent seasons; Sleeper
  turned out to have its own, below).

Added later the same day:

- **Sleeper's default lock-in league settings** (roster slots and scoring) for
  every game, not the user's own league settings. The exact defaults are not
  written down here yet. Read them from a fresh default Sleeper NBA lock-in
  league before building, and don't guess them.
- **Best ball, lock-in style:** each starter's score for a week is his **single
  best game** that week, auto-locked with hindsight. Games are not summed. This
  matches how the real league works: one game counts per starter per week (see
  spec 044 lock-ins and "NBA one game per week is correct" in project memory).
- **2019-20 stops at the 2020-03-11 suspension.** The bubble games are dropped,
  since that stretch was mostly playoffs.

- **Named mode ships first.** Cards show real player names. Blind mode (cards
  anonymised to "Player 14: 24yo C, 26.6/11/2.9", names revealed after the season)
  is a later "hard mode" toggle over the same game. Blind mode is also the better
  source for crowd ADP, because remembering how a season went can't help anyone.

## What's there now

The draft half mostly exists already; the season half doesn't.

- `MockDraftEngine.advanceUntilUserOrEnd`
  (`backend/.../engine/MockDraftEngine.java:59` on main) runs bot seats until it's
  the user's turn. Bots score candidates around ADP:
  `PickScorer.java:104` — `raw = (pickNo - candidate.adp() + reachBias) / adpScale`.
  So the bots need *some* ADP per player; that's the main input this feature has to
  supply per historical season.
- `RealizedLineupService.bestLineup` (`engine/RealizedLineupService.java:65`) picks
  the best legal lineup from a week's player points — which is exactly best ball.
- `GameScoringService.score` (`engine/GameScoringService.java:42`) applies a scoring
  map to a stat map, so a league's Sleeper NBA points scoring can score any box score.
- `SleeperPlayerStatsClient.week` (`ingest/SleeperPlayerStatsClient.java:54`) and
  `SleeperProjectionClient` (`ingest/SleeperProjectionClient.java:57,77`) already
  call the two Sleeper endpoints measured below.

Line numbers are from `main` at 2df1682; the working tree this doc was written in
was on an older branch, so re-check before building.

## What the data actually supports (measured 2026-11-02)

### Sleeper (already integrated)

| Endpoint | Coverage |
|---|---|
| `/stats/nba/{season}/{week}` (weekly per-game logs) | 2017–2025. 2016 and earlier return an empty array for every week tried. |
| `/v1/stats/nba/regular/{season}` (season totals) | Players with `gp` back to 2013 (~480–610/season). 2012 and earlier: player list, no stats. |
| `/projections/nba/{season}` → `stats.adp_std` | 2019–2025 (~550–700 players). None for 2017–2018. |

The ADP spot check: 2020's top five by `adp_std` are Davis, Antetokounmpo, Harden,
Dončić, Jokić — consistent with a *preseason* ADP, not a hindsight one. **One spot
check, not proof.** Before trusting it, check a player hurt in preseason to make
sure post-tip-off drafts didn't leak into the number.

### Kaggle `loganlauton/nba-player-box-score-data` (CC0 public domain)

One CSV, 158 MB, ~1.4M player-game rows, regular season only (every `Game_ID`
starts `002`). Columns: `Season, Game_ID, PLAYER_NAME, Team, GAME_DATE, MATCHUP,
WL, MIN, FGM…PTS, PLUS_MINUS`. Fetched anonymously via
`https://www.kaggle.com/api/v1/datasets/download/loganlauton/nba-player-box-score-data`.

**Game counts per NBA season match the real schedule** (grouped by the season code in
`Game_ID` — see traps below): 1970-71 = 697, 1975-76 = 738, 1990-91 = 1107,
1998-99 = 725, 2010-11 = 1230, 2011-12 = 990, 2019-20 = 1059, 2020-21 = 1080 — all
exact. 1980-81 = 943; I had guessed 902 and was wrong (forgot Dallas joined, 23 × 82
/ 2 = 943) — the data was right. **2022-23 has 1196 of 1230**: the snapshot was taken
in early April 2023; Sleeper covers that season anyway.

Share of rows with each stat present, by NBA season:

| Seasons | REB | AST | STL | BLK | 3PM | TOV |
|---|---|---|---|---|---|---|
| 1965-66 | 92% | 86% | 0 | 0 | 0 | 0 |
| 1970-71 | 96% | 94% | 0 | 0 | 0 | 0 |
| 1973-74 | 91% | 90% | 27% | 30% | 0 | 0 |
| 1977-78 | 99% | 98% | 67% | 71% | 0 | 50% |
| 1979-80 | 98% | 98% | 71% | 81% | 99% | 65% |
| 1981-82 | 98% | 98% | 54% | 75% | 99% | 56% |
| 1982-83 | 99% | 99% | 82% | 91% | 99% | 81% |
| 1983-84 → | ~99–100% across the board |

Before ~1965 rebounds/assists fall off fast (1955-56: 43% / 48%).

### Rejected or unverified sources

- **Basketball-Reference**: complete history, but its terms (as archived) prohibit
  copying a materially significant portion of the data; it sells custom datasets.
  Not usable for bulk ingest without a license.
- **Kaggle `deocheng/nba-data-1946-2026`**: current, has IDs and positions, labelled
  Apache 2.0 — but its own description says its primary source is
  Basketball-Reference. Treat the license label as doubtful; don't build on it.
- **NBA.com stats API directly**: not probed. The attempt used spoofed browser
  headers and was blocked by the session's permission check; not retried.
- **Yahoo historical ADP**: not checked. Only needed for 2017–2018 now that Sleeper
  has 2019+.

## Proposed design

### Season tiers

| Tier | Seasons | Cards from | Weekly scoring from | ADP from |
|---|---|---|---|---|
| Modern | 2019-20 → last complete | Sleeper season totals (prior year) | Sleeper weekly logs | Sleeper `adp_std` |
| Recent | 2017-18, 2018-19 | Sleeper / Kaggle totals | Sleeper weekly logs | **fallback** (below) |
| Classic | 1983-84 → 2016-17 | Kaggle, summed to prior-season totals | Kaggle box scores bucketed into weeks | fallback |
| Early era | 1970-71 → 1982-83 | Kaggle | Kaggle | fallback, **era scoring** |

Where Kaggle and Sleeper overlap (2017–2022), Sleeper wins for consistency with the
rest of the app; the overlap is a free cross-check of the Kaggle ingest.

### Era scoring (the honesty rule this feature needs)

A stat counts in a given season only if **≥ 95% of that season's player-game rows
have it** (threshold is a hand-set constant — label it as such in `weights.yml`).
Partially recorded stats are dropped, not zero-filled: in 1981-82 only 54% of rows
carry steals, so scoring steals would silently penalise whoever's box scores went
unrecorded. The draft screen says which stats count that year ("1975-76: points,
rebounds, assists — steals and blocks weren't fully tracked yet"), and the same
reduced scoring is used for the prior-season cards so cards and results agree.

### ADP fallback

For seasons with no real ADP: rank players by **prior-season fantasy points per game ×
games played under that season's era scoring**, and feed that rank in as `adp`. The
UI labels it "ranked by last season's points," never "ADP".

Explicitly **not** a sim-generated ADP: the bots reach and tilt *around* the ADP
input (`PickScorer.java:104`), so Monte Carlo drafts seeded with last season's rank
average back to last season's rank plus noise. Calling that ADP would look more
certain than it is.

Long-term, the game's own picks are real ADP for historical seasons ("crowd ADP"),
especially in blind mode — new data nobody else has. Not in v1.

### Weeks

Bucket each season's games into Monday–Sunday weeks from `GAME_DATE`, starting with
the week containing opening night. For each player-week, the score is the **max
single-game score** that week under the season's era scoring (lock-in best ball,
decided 2026-11-02). Those per-player numbers then go into
`RealizedLineupService.bestLineup` to fill the slots. Shortened seasons (1998-99,
2011-12) simply have fewer weeks. 2019-20 ends with the week of the 2020-03-11
suspension, and the bubble games are excluded.

The highest single game is a noisier statistic than the sum or the average, and
it rewards volatile players more than steady ones. That is the real lock-in game
as it's actually played, so it is intended. But it means a card's prior-season
per-game average undersells boom-or-bust players. If cards later show a "best
games" stat, it should be computed the same way.

### Ingest

One-off loader for the Kaggle CSV into new tables (`historical_player_game`,
`historical_player_season`; migration number = next free on main at build time —
main was at V37 when this was written but other branches hold V38–V42).
Static data: load once, never re-fetched.

## Known data traps

1. **`Season` is the calendar year, not the NBA season.** `Season=1970` runs
   1970-01-01 → 1970-12-31. Group by `Game_ID` characters 4–5 instead (`0024600034` →
   46 → 1946-47; `≥46` → 19xx, else 20xx).
2. **No player IDs — names only.** Same-name players (fathers/sons, two Gerald
   Hendersons) collide. Disambiguate by (name, team, season) runs, and link to
   Sleeper IDs by name for the overlap years — measure the match rate, don't assume it.
3. **No positions.** Needed for roster slots. Candidate source: Kaggle
   `sumitrodatta/nba-aba-baa-stats` season files — **license not checked.** Old-era
   positions are G/F/C only, which maps onto Sleeper's NBA slots imperfectly.
4. **`MIN` is "present" but often 0 in early rows** (1970: 88% of rows have
   minutes > 0). Don't use minutes as a "played" signal pre-1980.
5. Empty-string, not 0, for unrecorded stats. Keep the distinction through ingest —
   it's what era scoring keys on.

## Not building (v1)

- Seasons before 1970-71 (rebounds/assists too incomplete).
- Playoffs.
- Trades, waivers, in-season moves — it's best ball.
- Blind mode (planned as the next step after v1).
- Daily shared challenge / leaderboards / crowd ADP.
- League-mate tendency bots ("draft 1995 against your real league") — good hook,
  later.
- Yahoo ADP for 2017–2018.

## Open questions

- ~~Blind vs named~~ Decided 2026-11-02: named first, blind later.
- ~~2019-20 bubble~~ Decided 2026-11-02: excluded.
- ~~League settings~~ Decided 2026-11-02: Sleeper's default lock-in settings.
  The values are still to be read from Sleeper.

## Acceptance criteria (for the verification pass)

- For a sampled season per tier, games loaded == the counts above; Sleeper vs Kaggle
  per-player season points agree within rounding for one overlap season.
- No card or result in an early-era season shows a stat the era rule excluded, and
  the draft screen lists the counted stats.
- Seasons using the fallback say so wherever ADP would be shown.
- A full draft → season replay runs end to end in the real UI for one season of
  each tier, and the user's weekly best-ball totals match a hand calculation for
  at least one week.
- The ADP-leak check on Sleeper `adp_std` has been run and its result written here.
