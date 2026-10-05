# Contract: the two reader-facing messages

No endpoint, record or TypeScript type changes. Only the **value** of two
existing strings changes. Exact text is a review point, so it's pinned here.

## M1: Analysis projection reason

**Where**: `GET /api/leagues/{sleeperId}/analysis` → `projections.reason` **and**
`matchups.reason`. `LeagueAnalysisService.unavailable` (`:479-482`) puts the
same reason on both blocks. Rendered by `LeagueAnalysis.tsx`'s `NotYet`.

**When**: `league.sport() != NFL`, after the playoff-week and season-over
guards (`:431-439`), before the projection cache is read. Unchanged.

| | Text |
|---|---|
| Before | `roster projections are football-only: the projection source wired up is Sleeper's own weekly points (pts_ppr and friends), which has no nba equivalent. See claude/league-analysis.md's non-goals.` |
| After | `Roster projections aren't built for nba leagues yet: so far this app only has football projections.` |

Built as `"Roster projections aren't built for " + sport.code() + " leagues yet: so far this app only has football projections."`
via `LeagueAnalysisService.projectionsNotBuiltReason(Sport)` (package-private, static).

**Invariants (tested)**:
- contains `aren't built` and the sport code
- does not contain `equivalent`
- does not contain `/api/ingest` or `POST /api/`, nor the word `ingest` at all. `NoIngestHintsInMessagesTest` fails any sentence-like literal using it (amended 2026-10-05, see research "Amended during implementation")
- `projections.available == false`, `projections.positionGroups` unchanged, `rosters == []`

## M2: Projection-ingest refusal

**Where**: `POST /api/ingest/projections?sport=nba&season=…&fromWeek=…&toWeek=…`
→ `IllegalArgumentException` → `ErrorHandler.badRequest` → `400 {"error": "<message>"}`.

| | Text |
|---|---|
| Before | `projections are football-only: the stat keys Sleeper returns (pts_ppr, pts_half_ppr, pts_std) have no basketball equivalent. See claude/league-analysis.md's non-goals.` |
| After | `projections are football-only for now: the stored columns are Sleeper's pts_ppr / pts_half_ppr / pts_std, which are football scoring totals. Basketball projections need per-game stat lines scored with each league's settings, which isn't built yet (claude/projection-tools.md).` |

**Invariants (tested)**:
- still 400 for any non-NFL sport, and `ProjectionIngestService.refresh` is never called
- does not contain `equivalent`
- does not contain `/api/ingest`, `POST /api/` or the word `ingest` (same scanner rule)
- NFL path unchanged
