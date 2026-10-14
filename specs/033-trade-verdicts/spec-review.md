# Spec 033: adversarial review of spec.md

2026-10-13. I read the spec cold and checked every claim against the local DB (5433) and Sleeper's
live `/league/{id}/transactions/{leg}`. Everything below was executed; nothing is inferred
from docs alone.

## Blockers

### B1. Draft picks are not rare, and SC-003 is wrong

The spec says the NBA 2026 week-1 trade should read "no week played yet". Sleeper says
otherwise. Transaction `1346741394630250496` (accepted 2026-04-09, before the draft) is
**four 2026 picks and no players**: R1, R2 and R9 went from roster 11 to 8, and R1 from 8 to 11.
We store it as `adds = {}`, `drops = {}`, because ingest keeps neither `draft_picks` nor
`waiver_budget`.

- The only 2026 trade in our flagship basketball league is picks-only, so "every league is
  redraft, so this rarely matters" is false for the current season.
- A same-season pick isn't abstract. It became a drafted player, and `draft_pick` stores all
  168 picks of that draft. A pick-for-pick verdict is computable from data already stored, once
  we know which picks moved.
- Older seasons: 0 pick-carrying trades in NFL 2025, NBA 2025 or NBA 2024. Measured by scanning
  legs 0–25 of each.

**Fix:** ingest `draft_picks` (new migration V34, append-only). Then:
- resolve same-season picks to the player taken with them, and grade that player's stretch;
- show future-season picks as moved but "not drafted yet".

SC-003 is rewritten accordingly.

### B2. FAAB is a trade asset in ~~6 of 21~~ 10 of 18 NBA 2025 trades

> **Corrected during the build (2026-10-14).** The backfill stored `waiver_budget` for every
> trade, and **10 of NBA 2025's 18** carry FAAB. The "6" below came from spot-checking only the
> trades already under suspicion, and it was stated as if it were a full count. The 4 missed:
> - 1036, $1500;
> - 8044, $500;
> - 8022, two transfers, $1000 and $1100, both from roster 1 to roster 8;
> - 1804, $12.
>
> "of 21" was also wrong: 21 is all NBA trades across seasons, and NBA 2025 alone has 18.

Sleeper's `waiver_budget` on trades, NBA 2025:

| Stored id | FAAB | Players |
|---|---|---|
| 766 | $67 (8→1) | 2334 (1→8) |
| 1162 | $67 (8→1) | 1438 (1→8) |
| 1426 | $12 (3→1) | a 1-for-1 |
| 1753 | $1500 (11→1) | a 1-for-1 |
| 1455 | $5 (1→12) | **none** |
| 1508 | $15 (4→1) | **none** |

So 766 and 1162 are player-for-FAAB. A started-points verdict would read "roster 8 won by
everything; roster 1 received nothing". That's false, and it's exactly the confident-looking
nonsense AGENTS.md warns about. 1455 and 1508 are FAAB-only. The spec's "a side that received
nothing" edge case is these, misread.

**Fix:**
- Ingest `waiver_budget` too (same V34).
- Show FAAB moved on each side.
- FAAB has no points. When any side's only assets are FAAB or unresolved picks, the verdict is
  **"not comparable by points"**: show what each side got, crown no one.

## Should fix

### S1. The stored trade `week` is the week it was proposed, not accepted

Sleeper's `leg` on a trade is the week of `created` (the proposal). Trade 816 was proposed
2025-11-06 (leg 3) and accepted 2025-11-28. Our `league_transaction.week` stores the leg. Our
`created_at` actually holds Sleeper's `status_updated`, the acceptance time
(`TransactionIngestService.java:128-131`), despite its name.

Mapping `created_at` (as a US Eastern date) to the NBA schedule week (the first game day after
acceptance) matches the first week the player scores for the new roster in **23 of 28** NBA
2025 moves that ever land. *(An earlier draft of this review said "26 of 29". That was a
miscount, corrected after re-running.)*

The 5 misses are trades 704 (3 players) and 805 (2 players). Both were accepted at a week
boundary: 704 at 2025-10-27 01:03 ET, 805 at 2025-11-09 21:04 ET, Sunday night. In both, the
players already show on the new roster for the week that was just ending. See S3.

This explains the spec's "trade 863 stamped week 4, moves in week 6": it was proposed in week 4
and accepted in week 6. It is not a data defect.

**Consequences:**
- FR-005's data-driven rule stands. The acceptance date is a cross-check, not the rule.
- The page's existing "Week N" label on every trade shows the *proposal* week. Label the
  acceptance date, or the effective week.
- FR-013 is worse than stated. The existing per-player grade counts from the proposal week,
  inclusive.

### S2. A trade can never land: commissioner reversal

Trade 1753's two players never score for their receivers. Confirmed from the rows; it's not a
plain commissioner reversal:

1. Trade 1753 is accepted 2026-02-05 22:23 UTC: 1380 goes 1→11 and 2316 goes 11→1.
2. COMMISSIONER 1746 moves 1380 back to 1, and 1745 moves him to 11 again, a minute apart.
3. Trade **1720** is accepted 2026-02-07 and swaps both players back.

The scoring data has 1380 on roster 1 and 2316 on roster 11 in every week 15–18. The trade
lived two days inside week 16 and was undone before the week was scored.

Note that **stored id 1720 < 1753 although 1720 came later**. The timeline must order by
`created_at`, never by id.

"No week played yet" is the wrong label here. Distinguish:

- "no week played yet": the current season, and the effective week isn't scored yet;
- "undone before a scored week": a later transaction returned the assets before any week
  counted (1753);
- "never played for them": the season is over, or the player left before a scored week.

766 and 1162's players also never score for roster 8, in a finished season.

### S3. Weeks are never split, but a boundary week can be duplicated

*(The first draft of this item claimed NBA trade weeks are split between rosters, from the
spec's "21 new / 19 old" figure. That figure was measured against the **proposal** week (S1),
so it doesn't show a split. Re-measured directly, the claim is wrong.)*

Across NFL 2025, NBA 2025 and NBA 2024, a player appears in two rosters' `players_points` in
the same week only **4 times**, all in league 211 (weeks 1 and 3, the boundary weeks of trades
704 and 805). In every case:

- the points are **identical, the full week's**, on both rosters. That's duplication, not a
  split;
- he was started on **exactly one** of the two.

**Rule for FR-003:** a week's points belong to the roster that **started** the player. Bench
points in a duplicated week are shown on both rosters, flagged "also on X's roster this week",
and never summed across rosters. Weeks counted are whole weeks; there are no partial weeks.

The spec's re-trade example also needs correcting. Player 1589 "going back" in week 16 is
trade **1804** (accepted 2026-02-03) reversing 1426, a clean transaction, not a data mystery.

### S4. Three-team trades: none exist

Every stored trade has at most two receiving sides. FR-008 stays, tested by a synthetic fixture
only, and the spec says so.

### S5. Discoverability

The page holding trades is labelled **"Bench points"** in the rail
(`web/src/destinations.ts:286`). A verdict nobody finds is worth little. This is a decision for
Allan, not something to change in passing. Options: a "Trades" anchor on the page, or a relabel.

## Confirmed (the spec's assumptions hold)

- **"Started" is well-defined.** `starters_points` equals the sum of the starters'
  `players_points` in 744 of 744 roster-weeks across NFL 2025, NBA 2025 and NBA 2024. Empty
  starter slots appear as `"0"` (11 rows), harmless.
- **Draft stretches are buildable.** `draft_pick` holds 180 picks for NFL 2025 and 168 for each
  NBA league.
- **Team defenses** (`CLE`, `DET`, `PIT`) appear in NFL trade adds and in `players_points`
  (19 rows). They count like players, as the spec says.
- **Reversals** exist: the NFL week 5/6 pair, NBA 1426→1804, and NBA 1753→1720.
- **The page serves both sports** (`sports: ['nfl', 'nba']`).

## Amendments made to spec.md

B1, B2, S1, S2, S3 and S4 are applied in place under "Amended after review (2026-10-13)". S5 is
left as an open question for Allan.
