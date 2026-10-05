# Trade grades and the trade tree

Status: **design, not built.** 2026-10-05. Gap #3 in
[competitor-gap-research.md](competitor-gap-research.md).

Competitors: StatsGuy "Trade Explorer" (values at transaction time, win/loss,
CSV) and "Trade Tree" (follow a player or pick through every trade); StatChasers
trade grades; ffwrapped Trade Finder (projection-bound, covered in
projection-tools.md, not here).

## What's there now

More than the gap list implied:

- `league_transaction` (V19) stores every trade: `type = 'TRADE'`, `adds` /
  `drops` jsonb keyed by player → roster, `week`, `created_at`. 25 trades in the
  local DB.
- `TransactionAnalysisService` (`engine/TransactionAnalysisService.java:46-63`)
  already turns each trade into `Trade(week, sides)`, and each side's received
  players into `MovedPlayer(postMoveRank, weeksCounted)` — the player's average
  *positional rank* over the weeks played since the move.
- `RosterManagement.tsx:276` renders that rank per player, or "ungraded" when no
  week has been played since.

So each **player** is graded, but the **trade** isn't. There's no side-vs-side
verdict.

**Not stored:** traded draft picks. Sleeper puts them in a trade's
`draft_picks` array, and `TransactionIngestService`
(`ingest/TransactionIngestService.java:107-144`) keeps only `adds`/`drops`. Every
league is redraft today, so this rarely matters. It does make a pick-inclusive
trade tree impossible without new ingest.

## Proposed design

### Trade verdict by realized points

For each side of a trade, from the trade's week forward:

- **Points produced** by what it received, league-scored
  (`roster_week_points.players_points` for that roster).
- **Points started** — only the weeks those players were in that roster's
  starters (V18 `starters`). That's what moved matchups.
- **Matchups swung** (stretch goal): weeks where the received players' started
  points exceeded the margin of a win. Read from `league_matchup`.

Verdict = difference in *points started* between the sides, with
`weeksCounted`. "Won by 41.5 started points over 6 weeks" is the output. A
trade has two sides, not a league-wide ranking, so spec 013's percentile
letter grades (`config/weights.yml:151`) don't apply. There's nothing to take a
percentile of.

Ownership ends when a player is dropped or traded again; points after that
belong to the new owner. This needs the per-player ownership timeline that the
trade tree also needs, so build that first.

### Trade tree

`PlayerOwnershipTimeline`: for one league chain (`previous_league_id`), walk the
draft → adds/drops/trades in `created_at` order to get `(player, roster, from, to,
how)` intervals. Then:

- **Player view:** "Drafted by A (R3) → traded to B wk 5 for X → dropped wk 9 →
  added by C."
- **Trade view:** each traded asset expands into what it *became* (if B later
  flipped X for Y, B's side of the original trade includes Y's downstream
  value). Dynasty sites need this. In redraft it's usually one hop. Show depth,
  and don't invent it.

### Surface

- Extend the trades section of `/leagues/:id/roster-management` with the verdict
  per trade. The page already lists them.
- Player click → ownership timeline drawer.
- CSV export of the trade log (cheap, StatsGuy has it).

## Not building

- **Value-at-time-of-trade** (KTC/FantasyCalc-style "who won on paper"). That
  needs a value source, so it's projection-bound. See projection-tools.md.
- **Pick-inclusive trees** until `draft_picks` is ingested. That's a V27+
  migration (append-only) plus an ingest change, as its own small task if a
  dynasty or keeper league ever shows up.

## Acceptance criteria

1. The ownership timeline for every player in one real league reconciles: the
   roster it says owned him in week W matches `roster_week_points.players_points`
   having his key for that roster in week W, for every week. Run it, report the
   mismatch count.
2. A synthetic trade where side A's received player starts and scores 20/week
   and B's scores 5 must produce a verdict for A (preference-ordering test).
3. A trade made in the current week shows "no week played yet", not 0–0.
4. Live check: the verdict renders on the real Roster Management page for a real
   2025 NFL trade.
