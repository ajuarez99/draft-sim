# Implementation Plan: Trade verdicts from an ownership timeline

**Branch**: `033-trade-verdicts` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)
(amended by [spec-review.md](spec-review.md))

**Input**: Feature specification from `specs/033-trade-verdicts/spec.md`

## Summary

Every trade on Roster Management gets a verdict: started points the received assets produced
for the receiving roster, side against side. Three rules come from measurement, not from the
design doc:

1. **Ownership comes from the weekly scoring rows**, by starter (R1–R3), not from a
   transaction's week. That week is the *proposal* leg (lessons #42).
2. **Picks and FAAB are trade assets.** A new V32 stores them. Same-season picks resolve to
   the drafted player (verified on the real 2026 trade). FAAB makes a trade "not comparable
   by points" when a side gets nothing else.
3. **A reconciliation count** of transactions against the scoring rows rides on the wire as
   numbers.

No new route: `GET /transactions` grows. The CSV is built client-side from the same payload.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5); TypeScript (React + Vite)

**Primary Dependencies**: Existing only. Nothing new.

**Storage**: Postgres. One append-only migration, `V32__trade_assets.sql`, adds two nullable
jsonb columns.

**Testing**: JUnit (unit tests on the pure `OwnershipTimeline`, plus ITs against Postgres);
Vitest for the page and CSV

**Target Platform**: Railway (two services), local dev on Windows

**Project Type**: Web application (`backend/`, `web/`)

**Performance Goals**: The `/transactions` response stays interactive. Inputs are small: at
most 288 roster-weeks and 168 picks per season. Measure the response before and after; budget
under 300 ms locally.

**Constraints**:
- Honesty rules (AGENTS.md): no "0–0", no winner for FAAB/picks-only sides, and "not loaded"
  stated when V32 data is missing.
- Constitution: `Map.of` is not used where values can be null; `api.ts` mirrors the records.

**Scale/Scope**: 26 stored trades across 4 league-seasons; both sports

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | ✅ V32 is new; V31 is untouched |
| `api.ts` mirrors records in the same change | ✅ Planned as one task with the controller body ([contracts/api.md](contracts/api.md)) |
| No retuned constants | ✅ None introduced. The only threshold is EVEN at \|margin\| < 0.05, a rounding guard, not a tunable. |
| No `Map.of` with nullable values | ✅ The contract lists the nullable fields; bodies are `LinkedHashMap` |
| Ask before committing | ✅ Nothing is committed without Allan's OK |
| A planning doc's scope isn't a spec | ✅ Applied: the design doc's "no picks, rarely matters" and the spec's "split weeks" were both overturned by measurement |

**Post-design re-check:** still passes. No violations, so Complexity Tracking is empty.

## Design (detail in [research.md](research.md) and [data-model.md](data-model.md))

1. **Ingest** (`TransactionIngestService.toRow`): store `draft_picks` and `waiver_budget`
   verbatim, writing `[]` when Sleeper sends null. Self-healing backfill (R6): a stored-final
   week is refetched when it holds a TRADE with `draft_picks IS NULL`.
2. **Repository** (`LeagueTransactionRepository`):
   - read and write the two columns;
   - `forSeason` orders by `created_at`, never by id;
   - new `tradeWeeksMissingAssets(leagueId)`.
3. **Engine**:
   - **`OwnershipTimeline`** (new, pure): attribution, stretches, trade windows, causes, and
     reconciliation (R1–R3, R5).
   - **`TradeVerdictService`** (new): loads the inputs, resolves picks (R4), and builds
     `TradeResult`s.
   - **`TransactionAnalysisService.forLeague`**: delegates trades to `TradeVerdictService`.
     Adds and counts are unchanged.
4. **Controller** (`RosterManagementController.transactionsBody`): emits the contract. The
   `week` field is kept.
5. **Web**:
   - `api.ts` types.
   - The `RosterManagement.tsx` trades section:
     - a verdict line, with the acceptance date in place of "Week N";
     - per-side totals;
     - asset chips (player, pick → player, FAAB);
     - an expandable stretches panel;
     - a "Download CSV" button;
     - a one-line "realized started points only; no projections or trade values" note
       (FR-012);
     - a "picks and FAAB not loaded" note when `tradeAssetsLoaded` is false;
     - the reconciliation line under "How this works".
   - A "Trades" jump link at the top of the page (R10 default).

## Testing strategy (bug classes 1, 2, 3 and 6 in AGENTS.md)

- **Preference ordering** (SC-002): a synthetic 20-vs-5 trade fixture gives the 20 side the
  win, and the mirrored fixture flips it. Bench-only points don't count.
- **Real-row fixtures, copied from the DB into unit tests:**
  - 816: window starts at week 6;
  - 1753 → 1720: UNDONE;
  - 1426 → 1804: windows of week 15 and from week 16;
  - 704: duplicate week, started on one side only;
  - the 2026 picks trade, resolved to the four players;
  - 766: NOT_COMPARABLE.
- **IT against Postgres:**
  - V32 applies;
  - the upsert round-trips `draft_picks` / `waiver_budget` jsonb (bug class 3: run the real
    bind);
  - the `tradeWeeksMissingAssets` SQL executes (bug class 2).
- **Live verification:** quickstart V1–V10 and the browser pass. The suites alone aren't the
  bar.

## Project Structure

### Documentation (this feature)

```text
specs/033-trade-verdicts/
├── spec.md, spec-review.md, checklists/requirements.md
├── plan.md, research.md, data-model.md, quickstart.md
├── contracts/api.md
├── plan-review.md        # next: adversarial review of this plan
└── tasks.md              # /speckit-tasks
```

### Source Code (repository root)

```text
backend/src/main/resources/db/migration/V32__trade_assets.sql                 (new)
backend/src/main/java/com/ballknowers/draftsim/
├── ingest/TransactionIngestService.java                                      (store picks/FAAB, backfill)
├── store/LeagueTransactionRepository.java                                    (columns, order, missing-assets query)
├── engine/OwnershipTimeline.java                                             (new, pure)
├── engine/TradeVerdictService.java                                           (new)
├── engine/TransactionAnalysisService.java                                    (delegate trades)
└── api/RosterManagementController.java                                       (wire)
backend/src/test/java/com/ballknowers/draftsim/engine/OwnershipTimelineTest.java   (new)
backend/src/test/java/com/ballknowers/draftsim/engine/TradeVerdictServiceIT.java   (new)
web/src/api.ts
web/src/pages/RosterManagement.tsx (+ .test.tsx), web/src/lib/tradeCsv.ts (+ test, new)
```

**Structure Decision**: The existing web-application layout. New engine logic sits in the pure
`OwnershipTimeline`, so the hard rules are unit-testable without Spring or a DB.

## Rollout

1. Deploy both services.
2. Allan runs the four `POST /api/ingest/transactions/{id}` calls with `X-Admin-Token` (R6).
   Until then, finished seasons say "picks and FAAB not loaded".
3. Prod check: the V1 picks trade and V2/V3 against the live page.

## Complexity Tracking

None.
