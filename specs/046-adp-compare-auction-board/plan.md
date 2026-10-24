# Implementation Plan: ADP compare + live auction board

**Branch**: `046-adp-compare-auction-board` | **Date**: 2026-10-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/046-adp-compare-auction-board/spec.md`

## Summary

Capture three ADP sources per day into the existing `adp_snapshot` table:

- Sleeper dynasty and Sleeper redraft, from the season-projection payload the app already fetches
- Yahoo redraft ADP and average price, from a new server-side client

Store auction sale prices and budgets on draft rows (V39). Serve one league-scoped
`adp-compare` endpoint that returns players, source provenance, Yahoo's price curve, and (for
auction drafts) team budgets and sales. A new page `/leagues/:id/adp` renders the compare table
with gap tints and the trust-Yahoo blend, computed client-side so the sliders are instant. For
auction drafts it adds live budget cards and a sales feed, refreshed via the existing live-stream
SSE.

Auction drafts are excluded from every snake-order model:

- profile fitting
- `adp_at_time` backfill
- observed-order board
- grades
- the simulator

`draft_type` becomes a rule, alongside `platform`.

### Amended after review (2026-10-23)

[plan-review.md](plan-review.md) found 1 blocker, 6 high, 7 medium and 3 low issues. All were
accepted; decisions are in research.md's "Amended after review" section.

- **Blocker F1.** The plan refused `GET /drafts/{id}/board`, which is the real completed-draft
  board, not the simulator. Now:
  - That endpoint is unchanged.
  - The refusals go on `/api/sims`, fork-to-mock and manual pick.
  - Auction leagues are routed to the ADP page from every entry point. `DraftSummary` gains
    `draftType` (F2).
- **Storage.** V39 also adds `draft_pick.player_name` and `sleeper_player_id`, plus an
  `adp_unmatched` table (F4, F5).
- **Blend.** Monotonicity is restated for values, not ranks (F6). One Yahoo reliability rule
  (≥ 50% drafted) applies to both the tint and the blend (F7). Costs are floored at $1, there's no
  Blend $ without Yahoo, and the honest label wording is in place (F9, F10).
- **Sleeper capture.** It runs as its own pass and daily step, not gated on an in-progress season
  (F11).
- **Writes.** Write semantics and overloads are corrected, and the poller gets `updateBudget`
  (F12).
- **Identity and platform.** `X-Sleeper-User` header, and no platform gate (F13).
- **Budgets** are keyed by draft slot (F15).
- **Yahoo client** details are pinned down (F16). The `linear` and Yahoo-only rules are recorded
  (F17).
- **The quickstart** gains V6b and V10 for the criteria it missed (F14).

The Source Code list below is updated to match.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, virtual threads), TypeScript (React + Vite, strict)

**Primary Dependencies** (all existing):

- `SleeperProjectionClient.season`
- `SeasonProjectionIngestService`
- `PlayerMatcher`
- `BoardRepository`
- `DraftRepository` / `PickMapper`
- `LiveDraftPoller` + `/live-stream` SSE
- `useLiveDraft`
- `DailyRefreshService`
- `LeagueMembership`

(`LeagueAvailability` / `PlatformGate` were dropped by R8's amendment: there's no platform gate.)

New: `YahooAdpClient` (RestClient), `YahooAdpService`, `AdpCompareService`,
`AdpCompareController`, `web/src/pages/AdpCompare.tsx`.

**Storage**: Postgres. One migration, **V39** (highest on main is V38; no in-flight branch claims
V39, checked 2026-10-23). It adds nullable columns only: `adp_snapshot.avg_cost`,
`adp_snapshot.percent_drafted`, `draft.budget`, `draft_pick.amount`.

**Testing**:

- JUnit + the existing Postgres ITs
- Vitest + RTL
- the live checks in [quickstart.md](quickstart.md)

**Target Platform**: Railway (backend + web services), browsers down to 375px

**Project Type**: web application (`backend/` + `web/`)

**Performance Goals**:

- The compare endpoint is under 300 ms locally for about 900 players. It's one query per source
  plus the draft. Measured in verification, not assumed.
- Slider changes re-render in under 50 ms for about 900 rows (client-side sort only).

**Constraints**:

- Yahoo can't be called from a browser.
- Sleeper's public API doesn't expose the current nomination.
- Nothing is fitted to the one real auction.

**Scale/Scope**: about 900 NBA players and 10–14 teams, with one live auction at a time in
practice.

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | PASS. V39, new nullable columns only. |
| `api.ts` mirrors Java records field-for-field | PASS, planned. `AdpCompareResponse` and its nested records are added in the same task as the record. Tasks pair them explicitly. |
| Never retune a hand-set constant to fit a measurement | PASS. Blend defaults (75%, 30) and the gap default (20) are labelled hand-set. Rescaling is proportional (R6). The measured top-end miss is reported, not tuned away. |
| `Map.of` throws on null | PASS, planned. Every compare-row field is nullable, so responses use records or mutable maps (contract note). |
| Ask before committing | PASS. Nothing is committed without Allan's go-ahead. |
| Planning doc's scope isn't a verified spec | PASS. Reading the code found US4's real reader list (R5) and that the stub rows hold the dynasty ADPs (R1). Both changed the plan. |

Re-checked after Phase 1: no new violations.

## Project Structure

### Documentation (this feature)

```text
specs/046-adp-compare-auction-board/
├── spec.md
├── plan.md            # this file
├── research.md        # R1–R8
├── data-model.md      # V39, E1–E4
├── quickstart.md      # V1–V9 live checks
├── contracts/adp-compare-api.md
├── plan-review.md     # adversarial review (next step)
└── tasks.md           # /speckit-tasks
```

### Source Code

```text
backend/src/main/resources/db/migration/V39__adp_compare_auction.sql      (new: adp_snapshot cols, draft.budget,
                                                                            draft_pick amount/player_name/sleeper_player_id, adp_unmatched)
backend/src/main/java/com/ballknowers/draftsim/
  ingest/SleeperAdpCaptureService.java        (new) own pass over the raw season payload → sleeper_dynasty/redraft (R1 amended)
  ingest/SeasonProjectionIngestService.java   hands its fetched payload to the capture before line validation
  ingest/YahooAdpClient.java                  (new) paged pub-api-ro fetch, exact URL, "-" parsing, shape check
  ingest/YahooAdpService.java                 (new) match, write yahoo capture + adp_unmatched, shrink guard
  ingest/PickMapper.java                      read metadata.amount, first/last name, player_id
  ingest/LeagueIngestService.java             read settings.budget into upsert (explicit param, no default)
  ingest/LiveDraftPoller.java                 updateBudget when settings.budget present (no draft upsert)
  ingest/BoardService.java                    auction filter in adp_at_time backfill + observed-draft guard
  store/BoardRepository.java                  write/read avg_cost, percent_drafted; source keys; adp_unmatched
  store/DraftRepository.java                  modelsSnakeOrder(), DraftRow/DraftSummary.draftType, budget/amount/name cols,
                                              allCompletedPicks filter, updateBudget, = excluded semantics
  engine/AuctionGuard.java                    (new) one 409 auction_draft helper for sims / fork / manual pick
  engine/AdpCompareService.java               (new) rows (incl. unmatched), sources, curve, per-slot budgets, sales
  engine/DraftGradesService.java              AUCTION_DRAFT reason, returned before work
  engine/DraftAndAdpJoin.java                 null pickNo/round for auction picks
  engine/SimulationService.java (or its controller)  AuctionGuard
  mock/MockDraftService.java                  AuctionGuard on createSessionFromDraft
  api/AdpCompareController.java               (new) GET /api/leagues/{id}/adp-compare (X-Sleeper-User)
  api/IngestController.java                   POST /api/ingest/adp/yahoo, POST /api/ingest/adp/sleeper
  api/LeagueController.java                   AuctionGuard on recordPick (realBoard unchanged)
  refresh/DailyRefreshService.java            ADP_CAPTURE + YAHOO_ADP best-effort steps
web/src/
  api.ts                                      AdpCompare types, DraftSummary.draftType, DraftGradesReason AUCTION_DRAFT
  draftGrades.ts                              sentence for AUCTION_DRAFT
  pages/AdpCompare.tsx                        (new) table, tints, sliders, reliability greying, auction mode
  adpBlend.ts                                 (new, pure) blend, gap tint, reliability, rescale: unit-tested (budgets server-side)
  destinations.ts                             rail "ADP & prices" (NBA); auction branch for board/live; hide Mock it
  App.tsx                                     route /leagues/:sleeperLeagueId/adp
  pages/DraftPicker.tsx, LeagueHome.tsx       auction drafts link to the ADP page
  pages/DraftView.tsx, LiveDraftView.tsx      redirect to the ADP page when seats.draftType === 'auction'
  pages/StatLeaderboard.tsx                   "auction" in the pick cell when pickNo is null
```

**Structure Decision**: the existing web-app layout. The pure math (blend, rescale, budgets) lives
in one TS module so it's unit-tested without the DOM, as `pickInsight.ts` is. Budgets aren't part
of that module. The backend computes
team budgets too (E3), so the replay IT (V4) can check them server-side. The client renders the
server's `teamsState` and doesn't recompute it, so the rule exists once.

## Complexity Tracking

None at first draft. *After review*: the auction exclusions now reach about 10 files, not 3 (F1–F3).
That's still filters and guards beside existing ones, sharing one rule (`modelsSnakeOrder`) and one
409 helper (`AuctionGuard`), so it's not a second implementation of the rule.
