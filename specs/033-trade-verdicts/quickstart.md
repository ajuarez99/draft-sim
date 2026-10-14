# Spec 033 quickstart: verifying it end to end

**Prerequisites**
- The local Postgres on 5433 (`docker-compose.yml`), holding leagues 5, 210, 211 and 212.
- A backend restarted on this worktree's build. Check the bootRun classpath; see memory
  "worktree preview serves main".

## 1. Migrate and backfill

One call per league-season with trades: NFL 2025, NBA 2024, NBA 2025 and NBA 2026. Add the
`X-Admin-Token` header if one is configured locally.

```bash
for L in 1254190892974084096 1141438340626231296 1229352720222134272 1339351318115946496; do curl -s -X POST localhost:8080/api/ingest/transactions/$L; echo; done
```

Expect `league_transaction.draft_picks` to be non-null on all 26 TRADE rows. The refetch is
limited to trade weeks (R6), so the log shows about 20 week fetches for finished seasons, not
every week.

## 2. Spot checks against the review (V-items)

| # | League | Check | Expected |
|---|---|---|---|
| V1 | NBA 2026 | the picks trade | 4 PICK assets. R1 slot 10 → Şengün (roster 8), R2 → Haliburton, R9 → Jabari Smith, R1 slot 2 → Wembanyama (roster 11). State is `NO_WEEK_YET` until NBA week 1 is scored. |
| V2 | NBA 2025 | 766, 1162 | `NOT_COMPARABLE`, each with a FAAB $67 asset; the players are `LEFT_BEFORE_PLAYING` |
| V3 | NBA 2025 | 1455, 1508 | Present (they're absent today), `NOT_COMPARABLE` |
| V4 | NBA 2025 | 1753 | Both assets `UNDONE`, and 1720 is listed after it |
| V5 | NBA 2025 | 816 | Windows start at week 6, not 3 |
| V6 | NFL 2025 | 2403 / 2428 | *(Amended after plan review, F3.)* 2403: 5870 and CLE count weeks 5–6 for roster 7. 2428: they count from week 7 for roster 10. |
| V7 | all three finished | `reconciliation` | *(Amended after plan review, F1; the script result to reproduce.)* `unexplainedStretches` is 0 / 0 / 0 and `neverOnAScoredRoster` is 5 / 2 / 1 for NFL 2025 / NBA 2025 / NBA 2024. Any difference is explained in `verification.md`. |
| V11 | NFL 2025 | 2464 | 4039 → `LEFT_BEFORE_PLAYING`. 5012's week 7 is shown "listed, not started" and isn't counted (F2, F4). |
| V12 | NBA 2025 | 8044 | "Even: neither side started what it got" (F6) |
| V8 | both | one trade per sport | Started totals hand-summed in SQL from `roster_week_points` match to 0.1 (SC-004) |
| V9 | NBA 2025 | the CSV | Per-side totals equal the page's for all 21 trades (SC-006) |
| V10 | NBA 2025 | FAAB | All 6 FAAB trades show Sleeper's amount and direction (SC-007) |

## 3. Browser (real UI, both sports)

- Open Roster Management at 1280 px and 375 px.
- Check:
  - each trade shows a verdict line or its state's wording;
  - the date is the acceptance date;
  - the panel expands to stretches;
  - the CSV downloads;
  - no horizontal scroll at 375 px (`scrollWidth` ≤ `innerWidth`);
  - no "0–0" anywhere.
- Compare against a known-good page measured on the same emulated device (lessons #41).

## 4. Suites

```bash
cd backend && ./gradlew test
```

```bash
cd web && npx tsc -b && npm test && npm run build
```

Check the backend skip count. "BUILD SUCCESSFUL" with ITs skipped means Postgres wasn't
reachable (memory).
