# Spec 033 verification

2026-10-14, locally. This worktree's own build was served by `draft-sim-033-api-8133` (process
command line checked: `033-trade-verdicts\backend\build\classes`), with `draft-sim-033-web-5233`
proxied to it, against the shared dev DB on 5433. Signed in as popsharky (a member of every
league below).

**Everything below was executed.** Where a result differs from what a doc predicted, the
prediction is named as wrong, not adjusted.

## Backfill (quickstart §1, R6)

`POST /api/ingest/transactions/{id}` (local `X-Admin-Token`) on the four league-seasons stored
NFL 2025: 149, NBA 2024: 87, NBA 2025: 972, NBA 2026: 26 transactions.

After it, every TRADE row has non-null `draft_picks` and `waiver_budget`:

| League | Trades | Still missing | With picks | With FAAB |
|---|---|---|---|---|
| NFL 2025 (5) | 5 | 0 | 0 | 0 |
| NBA 2026 (210) | 1 | 0 | 1 | 0 |
| NBA 2025 (211) | 18 | 0 | 0 | **10** |
| NBA 2024 (212) | 2 | 0 | 0 | 0 |

- `tradeWeeksMissingAssets` is empty everywhere, so the self-healing refetch terminates (the
  check added after the Phase 2 diff review).
- **Wrong guess corrected:** spec-review B2 said "6 of 21". It's **10 of 18** NBA 2025 trades,
  corrected in spec-review.md.

## V-items

| # | Check | Result |
|---|---|---|
| V1 | NBA 2026 picks trade | ✅ Roster 11's R1/R2/R9 resolve to Alperen Şengün, Tyrese Haliburton and Jabari Smith for roster 8 (picks 10, 15, 99). Roster 8's R1 is Victor Wembanyama for roster 11 (pick 2). State NO_WEEK_YET (`throughWeek` 0). |
| V2 | NBA 766, 1162 | ✅ NOT_COMPARABLE. The FAAB is "$67 FAAB from …", and the players are LEFT_BEFORE_PLAYING. 1162 was **UNDONE in the first live run, a bug**: the cause logic scanned for any later return. Fixed, re-verified after a server restart. |
| V3 | NBA 1455, 1508 | ✅ Present (they were absent before this spec) and NOT_COMPARABLE, one side each, with FAAB naming the sender |
| V4 | NBA 1753 | ✅ UNDONE, "Undone before a scored week" |
| V4b | NBA 1720 | ✅ after F8. **The first live run read "+62.0 started points over 6 weeks", a false claim:** both players were on their receivers all season in the scored data, because 1753 was undone inside one week. Now RESTORED, both assets ALREADY_HELD. The misfire scan over all four leagues flips only these two assets (permanent IT `onlyTrade1720HasAlreadyHeldAssets`). |
| V5 | NBA 816 | ✅ Windows start at week 6 (leg 3); roster 12 wins by +175.5 |
| V6 | NFL 2403 / 2428 | ✅ 2403: 5870 and CLE count weeks 5–6 for roster 7 (+15.2). 2428: from week 7 for roster 10 (+23.4). 5870 counts from week 8, because week 7 was the unstarted boundary week. |
| V7 | Reconciliation | ✅ with a correction, below |
| V8 | SC-004 hand sums | ✅ NBA 816 and NFL 2428 started totals equal a hand SQL sum over `roster_week_points` within 0.1 (`TradeVerdictServiceIT`) |
| V9 | CSV, NBA 2025 | ✅ Captured in-page (no file written): `trades-nba-2025.csv`, 43 data rows. The per-side started sums equal the page's `startedTotal` for **all 34 sides, 0 mismatches**. |
| V10 | FAAB amounts and direction | ✅ All 10 NBA 2025 FAAB trades match Sleeper's `waiver_budget`, including 8022's two transfers ($1000 + $1100, roster 1 → 8) |
| V11 | NFL 2464 | ✅ Cooper Kupp LEFT_BEFORE_PLAYING. Mark Andrews (5012) LEFT_BEFORE_PLAYING; his week 7 is the unstarted boundary week, not counted. Roster 1 wins by +126.4. |
| V12 | NBA 8044 | ✅ EVEN, "Even: neither side started what it got" |

### V7: reconciliation

| Season | Player-weeks | Unexplained stretches | Same-week in-and-out | Never on a scored roster |
|---|---|---|---|---|
| NFL 2025 | 3,191 | 0 | 19 | **6** |
| NBA 2025 | 3,894 | 0 | 634 | 2 |
| NBA 2024 | 4,166 | 0 | 182 | 1 |

**My plan-review number was wrong:** NFL is 6, not 5. My throwaway script walked only players
present in the weekly rows, so Kameron Johnson (claimed in week 6, dropped in week 7, on no
scored roster ever) was never checked. The build agent's first explanation (that Kupp was
excluded) was also wrong. Both are recorded in plan-review.md.

## Browser (quickstart §3)

**NBA 2025 at 1280 px:**
- Verdict pills, accepted dates (US Eastern) and per-side "started X · bench Y" all render.
- Chips: players, pick → player, "$N FAAB from {team}".
- The "How this was counted" panel lists each player's season by team.
- The boundary-week label sits only on the trade's own receiving stretch. Checked on Bobby
  Portis in 1804: the label is on wk 16–19, not his earlier wk 7–14 stint with the same team.
  It was on the wrong stretch in the first two passes; fixed.

**NBA 2025 and NFL 2025 at 375 px:**
- `scrollWidth` 375 = `innerWidth`;
- no "0–0" anywhere;
- the Trades jump link and the CSV button are present.

**Stale tab, as AGENTS.md warns:** after the UI agent's RESTORED change, the open tab rendered
an empty verdict pill. The source was correct, and a hard reload fixed it. That was not a bug.

## Performance (T025)

`GET /transactions`, 5 warm calls each:

| Server | NBA 2025 | NFL 2025 |
|---|---|---|
| This branch (8133) | 78–89 ms | 48–57 ms |
| 8080 | 71–107 ms | 54–63 ms |

Under the 300 ms budget. **Caveat:** the 8080 server belongs to another session, so its exact
build isn't known. It's a rough "before" baseline only.

## Suites

Run after the final code-review fixes (R1, R2):

- **Backend** `./gradlew test` (reported by the build agent): 1,703 run, 1 failed, 1 skipped.
  - The failure is `BoardRepositoryLatestBeforeIT`, already known on `main` (a stale pre-draft
    start-time literal; memory, spec 030 notes). It isn't from this spec.
  - 1 skipped: `PlayerStatsLeaderboardReadIT`, 1 of 11.
  - The low skip count means Postgres was reachable and the ITs really ran.
- **Web** (run by me): `npx tsc -b` clean; `vitest` 124 files / **2,116 tests passed**;
  `npm run build` OK.
- **After a final restart** on the final build, all four leagues' verdicts and reconciliation
  counts are unchanged.

## Open for wording (Allan)

The verdict's "over N weeks" counts weeks across **both** sides. For NFL 2403 it reads
"+15.2 over 13 weeks", while the winner's players counted only weeks 5–6 before 2428 sent them
back. It isn't false, but it could be read as "the winner earned it over 13 weeks". Left as is
pending a decision.
