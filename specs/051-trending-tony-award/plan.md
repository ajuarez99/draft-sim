# Implementation Plan: The Trending Tony Award

**Branch**: `051-trending-tony-award` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/051-trending-tony-award/spec.md`

## Summary

A 14th award: the manager with the most completed pickups of players in the top 15 of Sleeper's platform-wide
trending-adds list at the time of the pickup. The trending list is overwritten on every fetch today, so the
feature has two halves: (1) start **keeping every fetched list as a dated snapshot** (V42, written in the
existing `replace` transaction), and (2) **judge each pickup** against the snapshot nearest its time within
±24h (research R1), counting per manager, with an explicit "not judged" bucket and a coverage line so a
season mostly before history began never looks like a real result. No backfill is possible.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite)

**Primary Dependencies**: existing — JdbcTemplate, Flyway; no new libraries

**Storage**: Postgres; new migration V42 (two tables, research R3)

**Testing**: JUnit 5 (pure unit tests for the rule; `*IT` against Postgres for the repository), Vitest for UI

**Target Platform**: Railway (backend + web services), browsers incl. phone width

**Project Type**: web application (`backend/` + `web/`)

**Performance Goals**: Awards page within 0.5 s of today's load (SC-005). Two extra queries per request:
snapshot headers in the season's pickup range, then entries filtered to rank ≤ 15 and players actually
picked up (amended after review, F4).

**Constraints**: append-only migrations; `api.ts` mirrors Java records in the same change; no `Map.of` with
nullable values in the response path (`position`, `trendingCoverage` are nullable)

**Scale/Scope**: 2 real leagues; ~100–300 pickups per league-season; ≤25 entries per snapshot

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | PASS — new V42; V41 is highest on main and on every remote branch (checked 2026-10-27). Re-check before writing and before merge. |
| `api.ts` mirrors Java records | PASS (planned) — `SuperlativeKind`, `trendingCoverage`, `TrendingCoverage`, `TRENDING_PICKUP` detail in the same task as the Java change (T-tasks group them). |
| Don't retune constants to match guesses | PASS — SC-006's wrong guess is named wrong in spec amendment A2, not tuned toward. TOP_N=15 is the user's clarified value, labelled hand-set. |
| `Map.of` with nulls | PASS (planned) — controller builds `LinkedHashMap` as it already does; detail row has nullable `position`. |
| Ask before committing | PASS — nothing committed without Allan's go-ahead. |
| Planning doc ≠ verified spec | PASS — scope came from reading `TrendingRefresh`, `SportTrendingRepository`, `WaiverPickupAttribution` and the refresh callers; R2 corrected the spec's own cadence assumption. |

Post-design re-check: same table, no new violations.

## Project Structure

### Documentation (this feature)

```text
specs/051-trending-tony-award/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R8
├── data-model.md
├── quickstart.md        # verification guide V1–V7
├── contracts/superlatives-api.md
├── checklists/requirements.md
├── plan-review.md       # adversarial review (next stage)
└── tasks.md             # /speckit-tasks
```

### Source Code (files touched)

```text
backend/src/main/resources/db/migration/
└── V42__sport_trending_snapshot.sql                 # NEW

backend/src/main/java/com/ballknowers/draftsim/
├── store/SportTrendingRepository.java                # replace(): also insert snapshot; NEW snapshotsBetween()
├── engine/TrendingPickups.java                       # NEW pure rule (judge, rank, constants)
├── engine/SeasonSuperlativesService.java             # Kind.TRENDING_TONY, TrendingPickupDetail, TrendingCoverage,
│                                                     #   Superlative component, rebuilders, trendingTonySuperlative()
└── api/SuperlativesController.java                   # TRENDING_PICKUP detail row, trendingCoverage map

backend/src/test/java/com/ballknowers/draftsim/
├── engine/TrendingPickupsTest.java                   # NEW: window rules, top-15 boundary, buckets sum, ties, DEF, null createdAt
├── engine/SeasonSuperlativesTrendingTonyTest.java    # NEW: states table (ESPN/no tx/no history/top<2/winner), standings, contributions
├── store/SportTrendingRepositoryIT.java              # extend: replace() writes snapshot; failure writes none; snapshotsBetween bounds
└── (existing superlatives tests)                     # must stay green unchanged — rebuilders pass trendingCoverage through

web/src/
├── api.ts                                            # types (same change as Java)
├── awardTitles.ts                                    # TRENDING_TONY: 'The Trending Tony'
├── components/AwardWinners.tsx (+ test)              # TRENDING_PICKUP lines ("#2 trending · week 3"), coverage line
├── components/SuperlativeStandings.tsx (+ test)      # figure for contributions = "#rank"; nothing structural
└── pages/Superlatives.tsx (+ test)                   # value text "N trending pickups", How-this-works paragraph, Sleeper credit
```

**Structure Decision**: existing web-application layout; the award follows Jabari Smith Jr.'s path (one
pure class + one service method + controller row + UI branches), and the snapshot store extends the
spec 014 repository rather than adding a new one.

## Design notes

1. **Snapshot write is inside `replace`'s transaction.** A crash between the current-list write and the
   snapshot write can't leave the two disagreeing.
2. **One extra query, bounded.** `forLeague` already loads `txRows` once for the two pickup awards; the new
   method reuses them, computes `[min(createdAt)-24h, max(createdAt)+24h]`, and loads snapshots for the
   league's sport in that range only.
3. **Rebuilders.** `withUsernames` and `withStandings` reconstruct `Superlative` with the canonical
   constructor; both must pass the new trailing component, and a test asserts `trendingCoverage` survives
   `withUsernames` (spec 040 plan-review F2 is the precedent for this exact drop).
4. **Order.** `TRENDING_TONY` appended last to `Kind` so every existing card keeps its position.
5. **`hueForIndex(i, superlatives.length)`** in `Superlatives.tsx` shifts every card's hue when a 14th card
   is added. Accepted as cosmetic, noted for review.

## Amended after review (2026-10-27, plan-review.md)

13 findings: 0 BLOCKER, 1 HIGH, 5 MEDIUM, 7 LOW. All were taken into the plan, research, data model,
contract, quickstart and spec in place, with notes there. Summary:

| Finding | What changed |
|---|---|
| F1 HIGH | `completedAdds` includes TRADE and COMMISSIONER adds. The type filter is now explicit, through one predicate shared with `MostAddedPlayers` (`PickupRules`), with trade and commissioner fixtures. |
| F2 | There is one unavailable condition (nothing judged), with two reasons. An available card never has a null `judgingSinceAt`. |
| F3 | Judging is after-first: the snapshot whose window contains the pickup wins. Spec amendment A3. |
| F4 | The read path loads headers plus entries filtered to rank ≤ 15 and players picked up, never every entry. There is now one volume figure, stated as a bound. |
| F5 | R5's ESPN rationale was wrong and has been corrected visibly. The exclusion stays, as a product choice. |
| F6 | `SuperlativeStandings` gets a separate `contributionFigure` prop. This is structural; "nothing structural" was wrong. |
| F7 | Added the `'PICKUPS'` unit, the Home `unitLabel` and `formatValue` cases, and all five fixture files. |
| F8 | The trophy case hides an unavailable Trending Tony. The Awards page always shows it. |
| F9 | The coverage copy names its exclusions (team defenses, pickups after the last scored week). The V2 hand count now excludes DEF. |
| F10 | The `fetched_at` skew is documented and not changed, because changing it would alter spec 014's staleness gate. |
| F11 | New record names: `TrendingSnapshotHeader`, `TrendingSnapshotRank`. |
| F12 | Added a 14-arg overload. Both rebuilders move to the 15-arg constructor, and each has a test. |
| F13 | An empty snapshot judges nothing. "Guaranteed" became "intended floor". `judgingSinceAt` is an instant, shown as a local date. The single-transaction trade-off is noted under FR-001. |

Files added to the touch list by the review: `engine/PickupRules.java` (new), `engine/MostAddedPlayers.java`
(refactor only), `web/src/components/TrophyCase.tsx` (+ test), `web/src/pages/LeagueHome.tsx` (+ test).

## Not built (and why)

- **Backfill** — impossible; Sleeper's trending endpoint only answers "now".
- **A denser fetch schedule** (e.g. 3-hourly GitHub Actions) — wakes Railway more often; a cost call for
  Allan, not needed for R1's ±24h rule at a daily cadence.
- **League-local trending** ("most added in this league this week") — that's Jabari Smith Jr.'s territory.
- **Share-based winner** — clarified away (count wins).
- **Snapshot pruning** — volume is small (research R3).

## Complexity Tracking

No constitution violations to justify.
