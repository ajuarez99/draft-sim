# Spec 033 research

All against the local DB (5433, the same data as prod for these leagues) and Sleeper's live
API, 2026-10-13/14. Leagues: NFL 2025 "(Foot) Ball Knowers" (id 5), NBA 2025/2024 "Ball
Knowers" (211/212), NBA 2026 (210). Earlier measurements, with their corrections, are in
[spec-review.md](spec-review.md).

## R1. Who owns a player in a week: the scoring rows, by starter

**Decision.** A player's week belongs to the roster that **started** him. If no roster started
him, it belongs to the single roster listing him in `players_points`. If two rosters list him
and neither started him, the week is a *duplicate*: it shows on both, flagged, with 0 started.

**Rationale.**
- `starters_points` equals the sum of the starters' `players_points` in 744/744 roster-weeks.
- Two rosters list one player in a week only 4 times, at trade boundaries (704, 805). The points
  are identical, and he was started on exactly one side (review S3).

**Alternatives.**
- *Replaying transactions to decide ownership.* Rejected: `week` is the proposal leg (lessons
  #43), and NFL 2025 has no stored schedule to map an acceptance time to a week.

## R2. A gap in the weekly rows means the player left

**Measured.** A player who disappears from a roster's `players_points` for 1+ weeks and later
reappears on the same roster:

| League | Gaps | …player has no move at all | …player on no roster during the gap |
|---|---|---|---|
| NFL 2025 | 31 | 0 | 23 |
| NBA 2025 | 70 | 0 | 38 |
| NBA 2024 | 39 | 0 | 26 |

Every gap belongs to a player with stored moves, and none to a player who never moved.
`players_points` doesn't drop a rostered player silently, so IR and bench players are listed.

**Decision.** An ownership *stretch* is a maximal run of consecutive weeks attributed to one
roster (R1). A gap ends it.

**Caveat.** This is the measured behavior of three finished seasons. The reconciliation (R5)
re-checks it every time it runs.

## R3. A trade asset's window

For a trade T with stored leg L, a side R and a received player P:

1. Find the first week W ≥ L that R owns (R1).
2. In weeks [L, W), P may be attributed only to a giving roster of T, or to nobody. If P was
   attributed to any other roster first, he went somewhere else before landing, so the window
   belongs to a later move and T's asset has **no window**.
3. The window is the stretch that starts at W (R2), from W to the stretch's end.

**Checked against the review's cases:**
- 816 (leg 3, accepted wk 6): weeks 3–5 are on the giver, so W = 6 ✓.
- 1426 → 1804: 1426's windows are week 15 only; 1804 gives W = 16 ✓.
- 1753 (undone by 1720): no week ≥ 16 is ever on the receiver, so no window ✓.
- NFL week 5/6 pair: ~~expected "undone"~~. *Amended after plan review, F3, executed:* 2403's
  assets count for weeks 5–6 for roster 7, then 2428 counts them from week 7. They are two
  trades, each with its own window.

*Amended after live verification (F8):* if R already held P before L, that is, the window's stretch starts before L, T did not move P in scored terms. The asset is `ALREADY_HELD`, with nothing counted and no window credited, and this check runs before the counting and the cause list below. NBA 1720 restored the status quo after 1753 was undone before any scored week: Reaves was on roster 11 and Giannis on roster 1 for weeks 1-21, so crediting either side +62.0 was false. NFL 2428, which reverses 2403 after 2403 scored weeks 5-6, is unaffected (the players were not held in the week before its leg).

**A window that doesn't exist gets a cause (FR-019), in this order.** *Amended after plan
review, F2: "undone" was too broad.*
1. *Amended after live verification:* only the **first** complete move strictly after T (by
   `created_at`, then id) that takes P off R decides. If it is a TRADE or COMMISSIONER move that
   puts P on a giving roster of T → `UNDONE` ("undone before a scored week"; 1753 → 1746/1720,
   1426's 2156 → 1804).
2. Otherwise, if such a move exists (a drop, waiver/free-agent churn, a trade to someone else) →
   `LEFT_BEFORE_PLAYING` (NFL 2464's 4039; NBA 766's 2334, 1162's 1438, 1036's 1779, each dropped
   by the receiver first). A later return by the giver does not make it UNDONE (1036: roster 4
   dropped 1779 before roster 11 re-claimed him), and a same-leg move created *before* T is not
   "later" (1162: move 1163). The first draft scanned for any later return and mislabelled 1036
   and 1162.
3. The season isn't complete, and no week ≥ L is scored yet → `NO_WEEK_YET`.
4. Otherwise → `NEVER_PLAYED_FOR_THEM`.

**Alternative.** NBA's schedule maps acceptance to a week correctly in 23 of 28 moves. It's kept
as a cross-check only, because NFL 2025 has no schedule and the 5 misses fall at week
boundaries.

## R4. Draft picks and FAAB

- **Sleeper payload.** `draft_picks: [{season, round, roster_id (original owner), owner_id,
  previous_owner_id}]` and `waiver_budget: [{sender, receiver, amount}]`. Ingest drops both today
  (`TransactionIngestService.toRow`).
- **Storage.** V34 adds two nullable `jsonb` columns to `league_transaction`:
  - `null` means the row was stored before V34, so its picks and FAAB are unknown;
  - `[]` means none.
  The distinction is surfaced: a season with any TRADE row still null says "picks and FAAB not
  loaded".
- **Pick resolution, verified on the real 2026 trade.** Pick (season, round, original roster X):
  1. X → manager via `roster_season`, then → draft slot via `draft.slot_to_manager`.
  2. `draft_pick` at (round, draft_slot) gives the player.
  3. Check: that pick's `manager_id` must equal the receiving roster's manager. A mismatch is
     shown as "pick not matched", never guessed.

  Results: roster 11's R1 (slot 10) → Şengün, drafted by manager 50 (roster 8, the receiver) ✓;
  R2 → Haliburton ✓; R9 → Jabari Smith ✓; roster 8's R1 (slot 2) → Wembanyama for roster 11 ✓.
- **A resolved pick's window.** The drafted player's first stretch on the receiving roster
  (normally from week 1).
- **Unresolved picks.** A future season's pick, or a draft not complete, reads "not drafted yet".
- **FAAB has no points value.** It's shown as an amount and a direction only (FR-018).

## R5. Reconciliation (FR-002, SC-001)

> **Amended after plan review (F1), executed on real rows.**
> - As first written, count 2 reported **20 / 528 / 118** (NFL 2025 / NBA 2025 / NBA 2024):
>   free-agent streams added and dropped within one leg. That's noise.
> - Now a move whose player's next move has leg ≤ its own leg is counted separately as
>   `sameWeekInAndOut`.
> - What's left, `neverOnAScoredRoster`, is **5 / 2 / 1**. Every one is a pickup dropped the
>   next leg before a scored week. These are listed, never called errors.
> - Count 1 (`unexplainedStretches`) is **0 / 0 / 0** over 3,191 / 3,894 / 4,166 player-weeks.

Counts per league-season, all reported as numbers:

1. **Unexplained roster changes.** For each stretch start, look for an inbound move of P into
   that roster: the draft, or a transaction adding P to R with leg ≤ the stretch's first week,
   after the previous stretch's start. Count the stretches that have none.
2. **Moves not reflected.** Count transactions that add P to R where no stretch of P on R begins
   at or after the move's leg, before P's next move. Excluded: moves already labelled "undone
   before a scored week", and moves after the last scored week.

**Why this shape.** It's sport-agnostic, and it needs only `created_at` order plus the leg as a
lower bound. Leg ≤ effective week always holds, because a proposal comes before its acceptance.

## R6. Backfill: final weeks are never refetched

- `TransactionIngestService.ingest` skips stored-final weeks (`WeekFinality.mayStopRefetching`).
- The daily refresh skips `loaded_complete` seasons entirely (spec 009).
- So no existing path refetches NBA 2025/2024 or NFL 2025 transactions.

**Decision.** In the week loop, a final week is refetched anyway when
`league_transaction` holds a TRADE in that week with `draft_picks IS NULL`. This is a new
repository query, it heals itself, and it touches about 20 weeks across all leagues.

**Production.** The finished seasons heal only through a manual
`POST /api/ingest/transactions/{sleeperLeagueId}`, once per league-season with trades (NFL
2025, NBA 2024, NBA 2025; four calls with NBA 2026). It's the narrowest existing route, and
`/league-history` would also rewrite standings and points. It needs `X-Admin-Token`, so Allan
runs it after deploy. Until then the page says "picks
and FAAB not loaded" for those seasons. It doesn't render them as absent.

**Alternative.** A V34 that deletes `league_week_fetch` TRANSACTIONS rows. Rejected: it would
refetch every week of every league to fix about 20.

## R7. What the page shows today

- The trades section (`web/src/pages/RosterManagement.tsx:226-251`) renders "Week N" and names
  only. It doesn't render `postMoveRank` for traded players, even though the wire carries it.
- Trades are built from `adds` only (`TransactionAnalysisService.java:285-300`). As a result:
  - FAAB-only trades (1455, 1508) and the 2026 picks trade **don't appear at all**;
  - player-for-FAAB trades (766, 1162) show **one side**.

  That's an existing defect, fixed by this spec.
- "Week N" is the proposal leg (lessons #43). It's replaced by the acceptance date, shown in
  the league's timezone convention (US Eastern, as spec 017 uses).
- **FR-013.** A traded player's `postMoveRank` is recomputed over the R3 window. Waiver adds keep
  the inclusive rule, which is correct for them.

## R8. Where the timeline lives (US2)

**Decision.** An expandable "how this was counted" panel on each trade row. Each received
asset's season stretches come from the trades payload, which carries only traded players'
timelines.

**Rejected:**
- A new drawer component. None exists to reuse, and `PlayerLink` already goes to the player
  page.
- Putting it on the player page, which has no league-ownership context. Possible later.

## R9. CSV

**Decision.** Built client-side from the same payload, one row per (trade, asset). Totals match
the page by construction, and no route is added.

**Rejected:** a CSV endpoint. It would be a second implementation of one number.

## R11. Which weeks count (added after plan review, F4/F5)

- **F5, scored weeks only.** Only weeks ≤ the league's `settings_json.last_scored_leg` count
  (absent → 0, as in NBA 2026 today). An in-progress week's live partial points never enter a
  verdict, and "so far" verdicts read "through week N".
- **F4, boundary week.** Sleeper's weekly row can list a player on the receiver for a week
  played *before* the trade was accepted. Measured: NFL 2464, accepted Tuesday 2025-10-21; 5012
  sits on roster 3's week-7 row, unstarted, 0.0.
  - Started points can't leak, because lineups lock and R1 prefers the starter.
  - `weeksCounted` therefore starts at the first week the receiver **started** the asset.
  - Leading unstarted weeks show in the stretch panel, marked "listed, not started", and their
    bench points are excluded from `benchPoints`.

## R10. Discoverability (review S5): open for Allan

**Default if Allan doesn't decide:** add a "Trades" jump link at the top of the Roster
Management page, and leave the rail label "Bench points" alone. A rail relabel is a one-line
change in `destinations.ts`, but it changes every league's navigation, so it's his call.
