# Quickstart / verification guide: spec 054

## Prerequisites

- A Postgres reachable at `application.yml`'s defaults (or a scratch container, see
  `claude/environment.md`), migrated by `bootRun`.
- The requested league ingested: `POST /api/ingest/all/1339351318115946496` and
  `POST /api/ingest/league-history/1339351318115946496`. **Local NBA 2024/2025 may hold a partial
  last week** (memory: matchup drift note); for regular-season what-if figures that week is a
  playoff week and is outside the bound, but check before trusting a local anomaly against prod.

## Automated

```bash
cd backend && ./gradlew test --tests '*MedianWhatIf*' --tests '*ExpectedWins*' --tests '*LeagueHistory*'
cd web && npx tsc -b && npx vitest run src/pages/LeagueHistory.test.tsx
```

Check the backend skip count (memory: ITs skip silently with Postgres down).

## Live checks (verification.md records each with actual output)

- **V1 (SC-001)**: `curl localhost:8080/api/leagues/1339351318115946496/history` → 3 seasons, each
  with a `median` block. 2024/2025 `status: WHAT_IF`, 2026 `status: ALREADY_ON`, no team in 2024/2025
  with a null `withMedian`.
- **V2 (SC-002)**: script over the response: invariants 1–3 of data-model.md hold for every row.
- **V3 (SC-003)**: for two managers in 2024 and 2025, recompute median W-L by hand from the stored
  weekly scores (SQL on `roster_week_points`, weeks 1..pws−1) and compare.
- **V4 (research R6)**: for 2026, official wins = re-counted head-to-head + median wins, from stored
  scores. A mismatch is reported as measured, not explained away.
- **V5 (SC-004)**: the 2026 `median`/`allPlay` equal `/expected-wins`' figures for 2026.
- **V6 (UI)**: open `/leagues/1339351318115946496/history` in the browser: 2024/2025 show median,
  vs-all, with-median, wins gained and place movement; 2026 says median games were already on;
  phone width has no horizontal page scroll outside the table wrapper.
- **V7 (SC-006)**: time `history` before and after the change on the same DB (5 runs each, median).
