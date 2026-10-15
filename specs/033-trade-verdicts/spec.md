# Feature Specification: Trade verdicts from an ownership timeline

**Feature Branch**: `033-trade-verdicts`

**Created**: 2026-10-13

**Status**: Draft

**Input**: Roadmap item 2.5 ([claude/competitor-gap-roadmap.md](../../claude/competitor-gap-roadmap.md)),
design doc [claude/trade-grades-and-trade-tree.md](../../claude/trade-grades-and-trade-tree.md).
User description: "Ownership timeline → trade verdicts. Each trade gets a side-vs-side verdict by
realized league-scored points the receiving roster STARTED, from the trade forward, ending when the
player leaves that roster. Show it on Roster Management's trades section, with a player ownership
timeline drawer and a CSV export of the trade log. Both sports. A trade with no week played yet
says so, not 0–0."

## Measured before writing (2026-10-13, local DB)

These numbers shaped the requirements below. They were measured, not assumed.

- **26 trades are stored**, all `complete`: NFL 2025 "(Foot) Ball Knowers" 5, NBA 2024 2,
  NBA 2025 18, NBA 2026 1 (already, in week 1).
- **A trade's stored week is not when its players start scoring for the new roster.** Of the
  14 player moves in NFL trades, 12 still score for the *old* roster in the trade's own week and
  are on the new roster the week after. Of 38 NBA moves, the trade week is split (21 new, 19 old,
  with some players on both). A week later, 9 are *still* on the old roster: trade 863 is stamped
  week 4 (made 2025-11-24) but its players change rosters in week 6.
- **Some traded players move back.** NBA trade 1426's player 1589 scores for the new roster in
  week 15, then for the old one in week 16. Trade 1753 sits beside two COMMISSIONER moves in the
  same week. The NFL league has an A→B trade in week 5 reversed by a B→A trade in week 6.
- The existing per-player grade on this page (`TransactionAnalysisService.moved`) counts from
  the trade week *inclusive*. By the first point, that credits the receiver with a week most
  traded players spent elsewhere. That rule was set for waiver adds, where it is correct.

Consequence: **the verdict follows where the weekly scoring data actually places each player.**
It never infers ownership from a transaction's week alone. Where the two disagree, the
disagreement is counted and shown.

> **Amended after review (2026-10-13, [spec-review.md](spec-review.md)).** Three of the
> measurements above were misread:
>
> - **The "stored week" is the week the trade was *proposed*** (Sleeper's `leg`). The stored
>   `created_at` is the *acceptance* time. Trade 863 was proposed in week 4 and accepted in
>   week 6, so it moved when it should have. The "21 new / 19 old" figure was measured against
>   the proposal week, and doesn't show split weeks.
> - **Weeks are never split.** Re-measured directly: a player shows on two rosters in one week
>   only 4 times, always with identical full points, and started on exactly one side.
> - **Player 1589 "moving back" is trade 1804** reversing 1426. It's a clean transaction.
>
> The review also found two asset types this spec ignored: **draft picks** (the NBA 2026 trade
> is four picks and no players) and **FAAB** (6 of 21 NBA 2025 trades, 2 of them
> player-for-FAAB). Neither is ingested today. FR-015 to FR-019 below were added for them.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Who won this trade (Priority: P1)

A league member opens Roster Management and sees, beside every trade, which side has gotten more
out of it so far. The measure is points the receiving team actually started from the players it
received, and how many weeks that covers ("Team A +41.5 started points over 6 weeks").

**Why this priority**: The page lists trades today but grades only players, one at a time, by
positional rank. "Who won the trade" is the question people open the page to answer, and the
competitors answer it.

**Independent Test**: Load Roster Management for NFL 2025 "(Foot) Ball Knowers" and for NBA 2025
"Ball Knowers". Every trade shows a verdict or an explicit "not graded yet" state. Hand-check
one trade per sport against the stored weekly data.

**Acceptance Scenarios**:

1. **Given** a trade where side A's received player is started and scores 20 per week for A, and
   side B's received player is started and scores 5 per week for B, **When** the verdict is
   computed, **Then** side A is the winner by the summed difference. This is a preference-ordering
   test, not just a structural one.
2. **Given** a received player who sits on the bench for a week, **When** the verdict is
   computed, **Then** that week's points do not count toward the started total. They are still
   visible as "scored on the bench".
3. **Given** a trade whose players have not yet played a scored week for their new rosters,
   **When** the page loads, **Then** the trade reads "no week played yet", never "0–0" or a tie.
4. **Given** a received player who is later dropped, traded on, or traded back, **When** the
   verdict is computed, **Then** only the weeks he scored for the receiving roster count.
   Points after he leaves belong to whoever has him.
5. **Given** a trade where one side's players produce exactly as much as the other's, **When**
   the verdict is computed, **Then** it reads as even, not as a win for either side.

---

### User Story 2 - A player's ownership timeline (Priority: P2)

Clicking a player in a trade opens a drawer showing his path through the season. Examples:
drafted by A in round 3, traded to B in week 5, dropped in week 9, added by C. Each stretch shows
the points he scored and started for that roster.

**Why this priority**: It's the evidence behind a verdict. When a reader doubts "B won by 40",
the timeline shows where each point came from. It also reconciles the transaction log against
the scoring data, which is the check that makes the verdict trustworthy.

**Independent Test**: Open the drawer for a player in the NFL week-5/week-6 trade-and-reverse
pair. It shows both hops, and each stretch's weekly points match the stored weekly data.

**Acceptance Scenarios**:

1. **Given** a player drafted, traded, then dropped, **When** the drawer opens, **Then** it lists
   each stretch in order, with the roster, how it began (draft, trade, waiver, free agent,
   commissioner), the weeks covered, and points scored and started in it.
2. **Given** a week where the transaction log says roster X held the player but the scoring data
   places him on roster Y, **When** the drawer opens, **Then** that week is marked as a
   disagreement. The scoring data decides whose points they are, and the drawer says so.
3. **Given** a player with no transactions (drafted and kept all season), **When** the drawer
   opens, **Then** it shows one stretch.

---

### User Story 3 - Export the trade log (Priority: P3)

A league member downloads the season's trades as a CSV, one row per player moved. Each row
carries the trade's date, both rosters, the player, and his started and bench points for the
receiver, so they can be analyzed elsewhere.

**Why this priority**: Cheap, and a competitor has it. It adds no new judgment.

**Independent Test**: Download the CSV for NBA 2025. It has one row per player move across all
18 trades, and its started-points columns sum to the same totals the page shows.

**Acceptance Scenarios**:

1. **Given** a season with trades, **When** the user exports, **Then** the file opens in a
   spreadsheet with a header row, and the per-side totals match the on-page verdicts.
2. **Given** a season with no trades, **When** the user looks for the export, **Then** it is not
   offered, or it produces a header-only file. It never errors.

---

### Edge Cases

- **Trade made before any week is scored**: "no week played yet". *(The NBA 2026 trade first
  cited here is a pre-draft pick swap; see SC-003.)*
- **Trade reversed** a week later (the NFL week 5/6 pair): each trade is graded on its own window.
  The first trade's window ends when the players go back.
- **Player traded back mid-trade-window** (NBA 1589, by trade 1804; *corrected after review*,
  it is a clean move): his stretch for the receiver ends when he leaves.
- **Trade undone inside one week** (NBA 1753, two commissioner moves, then reversed by 1720
  two days later): its assets never scored for the receivers, and it reads "undone before a
  scored week" (FR-019). Name the commissioner moves in the timeline; don't hide them.
- **Player-for-FAAB and FAAB-only trades** (NBA 766, 1162, 1455, 1508): "not comparable by
  points" (FR-018).
- **Team defenses and other non-player assets** (NFL `CLE`, `DET` keys in trade adds) are real
  assets with real points. They count like players.
- ~~**A side that received nothing**: it shows zero received, and the verdict is still
  computed against the other side.~~ *Amended after review:* every such side in the data
  received FAAB, which we don't ingest yet. See FR-018.
- **Three-team trades**: more than two sides. The verdict ranks all sides; it isn't a pair
  difference.
- **A player whose scoring rows are missing** for a week the transaction log says he was rostered:
  this counts as a disagreement week, not as zero points silently.
- **Prior seasons** (NBA 2024, 2025, NFL 2025): fully played seasons get final verdicts. The
  current season's verdicts are "so far".

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST build, per league-season, each player's ownership stretches (roster,
  start week, end week, how acquired) from the draft and every stored transaction, in the order
  they happened.
- **FR-002**: The system MUST reconcile those stretches against the weekly scoring data, which
  records which roster each player scored for each week. It MUST count weeks where the two
  disagree, per league-season, and expose that count. Disagreements MUST NOT be silently
  resolved in the transaction log's favor.
- **FR-003**: For attributing points, the weekly scoring data MUST decide which roster a player's
  points belong to in a given week.
- **FR-004**: For each trade side, the system MUST compute, over the weeks the received assets
  scored for that roster after the trade: points started, points scored on the bench, and the
  number of weeks counted.
- **FR-005**: A trade's window for a received asset MUST start at the first scored week in which
  that asset scores for the receiving roster on or after the trade. It MUST end when the asset
  stops scoring for that roster. It MUST NOT start at the transaction's stored week by
  assumption (see "Measured before writing").
- **FR-006**: Each trade MUST carry a verdict:
  - a winning side and a margin in started points, or "even";
  - or "no week played yet" when no received asset on any side has a counted week.
  The verdict MUST state its weeks counted beside it.
- **FR-007**: A verdict for the current, unfinished season MUST read as "so far", and a finished
  season's as final.
- **FR-008**: Trades with three or more sides MUST rank every side by started points. They MUST
  NOT be reduced to a two-way difference.
- **FR-009**: The Roster Management trades section MUST show each trade's verdict, each side's
  started total, and each received asset's started and bench points, in both sports.
- **FR-010**: Clicking a traded asset MUST open its ownership timeline for that season (US2),
  including any disagreement weeks.
- **FR-011**: Users MUST be able to download the season's trade log as CSV, one row per moved
  asset per trade, with totals that match the page.
- **FR-012**: The verdict MUST NOT use projections, rankings or trade-value charts. It is
  realized, started points only, and the page MUST say so in one line.
- **FR-013**: The existing per-player positional-rank grade on trades MUST either move to the same
  window as FR-005, or be visibly labelled as counting from the trade week. It MUST NOT silently
  disagree with the verdict beside it. (Its inclusive-week rule stays correct for waiver adds.)
- **FR-014**: Access follows the existing Roster Management rules (league members). No new
  access rule.

*Added after review (2026-10-13):*

- **FR-015**: A week's points for a player MUST belong to the roster that **started** him that
  week. In the rare week where he appears on two rosters (measured: 4 player-weeks, at trade
  boundaries), bench points MUST be shown on both, flagged, and never summed across rosters.
  This refines FR-003.
- **FR-016**: The system MUST store the draft picks and FAAB that move in each trade. It MUST
  backfill them for every stored league-season, by re-ingesting transactions.
- **FR-017**: A same-season pick MUST resolve to the player drafted with it. That player's
  stretch on the drafting roster counts toward the side that received the pick. A
  future-season pick MUST show as moved but "not drafted yet".
- **FR-018**: When any side's only assets are FAAB or picks that aren't drafted yet, the
  verdict MUST read "not comparable by points". It MUST list what each side got and crown no
  winner.
- **FR-019**: A trade's assets that never score for the receiver MUST be labelled by cause:
  - "no week played yet": current season, effective week not scored;
  - "undone before a scored week": a later transaction returned them first (NBA 1753 → 1720);
  - "never played for them": everything else.
- **FR-020**: A trade's displayed date MUST be when it was accepted, not the week it was
  proposed. Transactions MUST be ordered by acceptance time, never by stored id (1720 was
  accepted after 1753).

### Key Entities

- **Ownership stretch**: one player, one roster, a run of weeks, how it began (draft, trade,
  waiver, free agent, commissioner) and how it ended. Built from transactions and checked against
  weekly scoring.
- **Trade side**: one roster in one trade, with the assets it received, and per asset its window,
  started points, bench points and weeks counted.
- **Trade verdict**: the sides ranked by started points; a margin, "even", or "no week played
  yet"; "so far" or "final".
- **Reconciliation summary**: per league-season, how many player-weeks the transaction log and
  the scoring data agree on, and how many they don't.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For every player in NFL 2025 "(Foot) Ball Knowers" and NBA 2025 "Ball Knowers",
  every (player, week) pair is checked against the weekly scoring data. The mismatch count is
  reported as a number, per league. A non-zero count is an acceptable result if each mismatch is
  explained; an unmeasured count is not.
- **SC-002**: The synthetic 20-vs-5 trade (US1 scenario 1) produces a verdict for the 20 side,
  and the reversed fixture produces one for the other side.
- **SC-003**: ~~The NBA 2026 week-1 trade reads "no week played yet" on the live page.~~
  *Amended after review:* that trade is four 2026 picks (R1, R2 and R9 from roster 11 to 8, and
  R1 from 8 to 11). On the live page it shows each pick resolved to the player drafted with it.
  It reads "no week played yet" until NBA week 1 is scored. The player-for-FAAB trades 766 and
  1162 read "not comparable by points".
- **SC-007** *(added after review)*: Every NBA 2025 trade carrying FAAB shows the amount and
  direction, matching Sleeper's `waiver_budget` for all 6.
- **SC-004**: One real trade per sport, hand-summed from the stored weekly data, matches the
  page's started totals to within 0.1 point.
- **SC-005**: The verdict renders on the real Roster Management page for a real NFL 2025 trade
  and a real NBA 2025 trade, at desktop and phone widths, with no horizontal scroll.
- **SC-006**: The CSV's per-side started totals equal the page's for every trade in NBA 2025.

## Assumptions

- **"Started" is the measure**, as the design doc chose: started points moved matchups, and bench
  points didn't. Bench points are shown, not counted.
- **Weekly scoring data is the ground truth for whose points they are.** It's what the league's
  matchups were scored from. The transaction log is the claimed history, checked against it.
- **No value-at-time-of-trade.** That needs a projection or trade-value source (Phase 3,
  projection-bound).
- ~~**No traded draft picks.** `draft_picks` isn't ingested. Every league is redraft, so this
  rarely matters.~~ *Amended after review:* this was false. The only NBA 2026 trade is
  picks-only. Picks and FAAB are now in scope (FR-016 to FR-018).
- **Three-team trades:** none are stored (every trade has at most 2 receiving sides). FR-008 is
  tested by a synthetic fixture only.
- **Open for Allan (review S5):** the page that holds trades is labelled "Bench points" in the
  rail. Should verdicts get a "Trades" anchor, or should the page be relabelled?
- **No downstream "trade tree" value** (B flips X for Y, and Y counts toward the original
  trade). In redraft it's almost always one hop. The timeline shows the hops; the verdict
  doesn't chain them. Revisit if a dynasty league appears.
- **"Matchups swung"** (did the received points exceed a loss margin) is a stretch goal, built
  only if it falls out of the same data cheaply.
- **NBA 2025 weekly data** was found correct against official totals (memory: Sleeper NBA 2025
  matchup drift, resolved 2026-10-12). It's not re-ingested here; Sleeper's live view of that old
  season is degraded.
