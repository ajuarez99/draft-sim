# Implementation Plan: Commissioner score override

**Branch**: `claude/peaceful-panini-70c6a7` (worktree; spec dir `057-commissioner-score-override`) | **Date**: 2026-11-05 | **Spec**: [spec.md](spec.md)

## Summary

Add a V45 `week_score_override` table, a commissioner-only endpoint and panel to fill it one whole week at a time,
and one `WeekScoreResolver` (override → plausible 055 official → feed). The resolver is applied **inside the store
reads that hand out team-week totals**, so every consumer of those reads picks it up with no per-page wiring:

| Read | Consumers that now get resolved scores |
|---|---|
| `LeagueMatchupRepository.pairedWithScores` | Awards close wins/losses, closest game, biggest blowout (standings); `LeagueRecordService` margins + streaks; `ExpectedWinsService` (luck); `WeeklyReportService` records + matchups; `HeadToHeadService`; `RivalryService` |
| `RosterWeekPointsRepository.extremes` | Awards highest/lowest week winners; record book |
| `RosterWeekPointsRepository.allScores` | weekly pot; record book points leaders |
| `RosterWeekPointsRepository.through` | power rankings PPG, league analysis, playoff odds, hall of fame |

`breakdownsFor` (per-player JSON) stays raw (FR-012). Its `startersPoints` total is resolved at the two callers that
use it as a *team score*: `SeasonSuperlativesService.weekScoreStandings` (highest/lowest week standings) and
`WeeklyReportService`'s `pointsByRoster` (and anything in the report that ranks teams by week total). Per-player
shares, bench, lineup efficiency, lock-ins, player trends/stats/ownership keep the feed.

## Technical Context

Java 21 / Spring Boot 3.5 / JdbcClient / Postgres (Flyway); React + TS + Vite. Tests: JUnit 5 + Testcontainers-less
ITs against local Postgres 5433 (skip when down — check skip count), Vitest. Scale: ≤ a few hundred roster-weeks per
league-season; resolver adds 2–4 small queries per read.

## Constitution Check

- Append-only migrations: new `V47__week_score_override.sql`; V44 is the highest on `origin/main` (checked
  2026-11-05, no V45 on any branch or worktree). PASS
- `api.ts` mirrors records in the same change: `WeeklyPotWeek.scoreSource`, superlatives `scoreSources`, weekly
  report `scoreSource`, new override types. PASS (task-level)
- No hand-set constant retuned: 055's 25% `MAX_OFFICIAL_DRIFT` is **moved**, not changed. PASS
- No `Map.of` with nullable values in responses (override `enteredBy` nullable). PASS (task-level)
- Ask before committing. PASS

## Design

### D1. Schema (V47; was V45, see renumber note)

```sql
create table week_score_override (
    league_id   bigint        not null references league(id) on delete cascade,
    week        int           not null check (week >= 1),
    roster_id   int           not null,
    points      numeric(8,2)  not null check (points >= 0),   -- same precision as roster_week_points.starters_points
    entered_by  bigint        null references manager(id),    -- null = configured app owner (no manager row), as V41
    entered_at  timestamptz   not null default now(),
    primary key (league_id, week, roster_id)
);
```
Rows are replaced a whole week at a time (delete week + insert, one transaction). `league_id` is the league-season
row, like the pot.

### D2. `WeekScoreResolver` (store package, `@Component`, own `JdbcClient` — no dependency on the two repositories it
serves, so no cycle)

- `enum Source { COMMISSIONER, OFFICIAL, FEED }`
- `Resolution resolve(Collection<Long> leagueIds)` — batch: overrides for the ids; snapshots for the ids; for leagues
  that have snapshots only, roster ids (`roster_season`) and feed rows (`roster_week_points`) for the plausibility gate.
- Per league, per week: override rows present → COMMISSIONER (whole week by construction); else
  `officialWeekScores` + `plausibleOfficial` (moved here from `WeeklyPotService`, same rules, same 25%) → OFFICIAL;
  else FEED.
- `Resolution.points(leagueId, week, rosterId, BigDecimal feed)` returns the override/official value when the week is
  non-FEED **and** has that roster, otherwise `feed`. `Resolution.source(leagueId, week)`.
- `Resolution.isEmpty()` fast path: no overrides and no snapshots → reads return unchanged rows.

### D3. Applying it

- `pairedWithScores`: map each `PairedGame` replacing `aPoints`/`bPoints`. The SQL needs `a.league_id` added to the
  select (PairedGame has no league id; add a private row type or select it and drop it).
- `allScores`, `through`: same SQL + league id, map points.
- `extremes`: run the `allScores` query (same joins, same week filter), resolve, sort in Java by points (dir), then
  season desc, week asc, roster asc, limit. `starters_points` is `not null` (V5), so no null ordering to mimic.
  One query definition for both, not two.
- `WeeklyPotService`: drop its own official overlay; `allScores` is already resolved; `sourceByWeek` comes from
  `Resolution.source`. `Week.scoreSource` gains `COMMISSIONER`. Existing pot tests that call
  `WeeklyPotService.plausibleOfficial` move to the resolver's test.

### D4. Endpoint (`ScoreOverrideController`, pattern of `WeeklyPotController`)

- `GET /api/leagues/{sleeperId}/score-overrides/{week}` → `{ week, isFinal, canEdit, commissionerKnown, source,
  enteredAt, teams: [{ rosterId, teamName, feedPoints, inUsePoints, overridePoints|null }] }`. Visible to anyone
  who can see the league (`membership.visibleLeague`).
- `PUT` same path, body `{ "scores": [{ "rosterId": 1, "points": 239.0 }, …] }`, read as `JsonNode`:
  403 unless `canCommission`; 400 if week not final (`ScoredWeeks.of(id).isFinal(week)`), if the roster set ≠ the
  week's stored feed rosters (message names missing/extra), if any points is missing, non-numeric, negative, or has
  more than 2 decimals (refuse, never round). Returns the GET body.
- `DELETE` same path: 403 unless commissioner; deletes the week; returns the GET body.

### D5. Provenance in payloads

- `SeasonSuperlativesService.Result` + `scoreSources: List<WeekSource(week, source)>` for every scored week in the
  window (FEED included, so the client never infers).
- `WeeklyReportService.Result` + `scoreSource` (String, null when unavailable).
- `WeeklyPotService.Week.scoreSource` + COMMISSIONER.

### D6. UI

- `WeeklyReport.tsx`: when the week's source is COMMISSIONER/OFFICIAL, a one-line note under the scores
  ("Scores entered by the commissioner from the Sleeper app" / "Sleeper's official totals"). Commissioner-only
  `CommissionerScoresPanel` (collapsed by default) on that page: one number input per team prefilled with the
  in-use score, shows feed and source, Save / Clear; on success refetch the report and bump `leagueDataVersion`.
- `Superlatives.tsx`: a note naming the commissioner-entered weeks (and official weeks) in words, e.g.
  "Weeks 1–2 use scores the commissioner entered from the Sleeper app."
- `WeeklyPot.tsx`: COMMISSIONER weeks get a distinct marker + note, alongside the existing FEED asterisk.

## Not built (and why)

- **Per-player official points**: unknown; bench/efficiency/top-performer stay feed (FR-012).
- **Audit history of edits**: one row per roster-week, latest wins; `entered_by/at` only. A league of 12 friends.
- **Validating entries against Sleeper**: impossible for weeks 1–2, which is the whole reason this exists.
- **Live week overrides**: refused (not final).
- **ESPN leagues**: the endpoint works on any league row, but nothing ESPN-specific was checked.

## Acceptance (verification pass)

1. Unit (resolver): override beats feed — **preference test** (lessons class 1); override beats a plausible official;
   official beats feed; implausible official → FEED; no data → identity.
2. Unit/IT: `pairedWithScores` returns override points when an override row exists (the regression test the brief
   requires), against real Postgres.
3. Controller IT: non-commissioner 403; partial week 400; not-final 400; negative 400; 3-decimal 400; save then clear.
4. Live, local, real NBA league data: enter week 1 with popsharky > jstrobe → Awards close wins no longer lists
   jstrobe's week 1; weekly report week 1 shows popsharky winning with the commissioner note; pot week 1 labelled.
5. NFL league Awards/report/pot payloads byte-identical before vs after (no override, official = feed).

## Project structure (touched)

```
backend/src/main/resources/db/migration/V47__week_score_override.sql
backend/src/main/java/com/ballknowers/draftsim/store/WeekScoreResolver.java          (new)
backend/src/main/java/com/ballknowers/draftsim/store/WeekScoreOverrideRepository.java (new)
backend/src/main/java/com/ballknowers/draftsim/api/ScoreOverrideController.java      (new)
backend/.../store/LeagueMatchupRepository.java, store/RosterWeekPointsRepository.java
backend/.../engine/WeeklyPotService.java, SeasonSuperlativesService.java, WeeklyReportService.java
web/src/api.ts, pages/WeeklyReport.tsx, pages/Superlatives.tsx, pages/WeeklyPot.tsx,
web/src/components/CommissionerScoresPanel.tsx (new)
```

## Amended after review (2026-11-05)

Review: [plan-review.md](plan-review.md). What changed, finding by finding:

- **H1, taken (FR-013).** The GET body carries `checks`:
  - `totals`: the latest `roster_fpts_snapshot` week N. Per roster, Σ in-use scores for weeks 1..N vs `fpts(N)`.
    Status MATCH, MISMATCH (lists the rosters, with both numbers), or UNAVAILABLE (no snapshot).
  - `record`: per roster, the W-L recomputed from in-use paired scores through `last_scored_leg`, plus the median game
    when the league plays one. Same rule as the weekly report's `recordsThrough`, reused, not rewritten. Compared with
    `roster_season` wins/losses/ties. Only computed when the stored final weeks reach that leg; otherwise UNAVAILABLE.
  - Both warn and never block. They are labelled as checks against Sleeper's official numbers.
  - Not built: deriving week 2 from the snapshot minus week 1. It would cut the typing in half, but it hides the one
    cross-check that catches a typo.
- **H2, taken.** The spec's bug numbers are corrected. Verification mirrors prod's stored weeks 1–2
  (`/weekly-report/{1,2}` on api.ballknowers.co, read 2026-11-05) into scratch DB `draftsim_057`. The baseline is jstrobe
  233.0 over popsharky 227.0 in week 1, a close win for jstrobe.
- **H3, taken in part.**
  - The four reads still resolve inside the repositories, because that is the one choke point. Labels come from
    `WeekScoreResolver.sources(leagueId)`, read by the three services that show them.
  - The two reads could disagree if a save lands between them in one request. That window is milliseconds, for a
    commissioner-only write, so it is accepted and stated here.
  - The pot's official-tier tests (`WeeklyPotServiceTest:137-187`) move to `WeekScoreResolverTest`, which keeps
    "official beats feed" and the gate. A new Postgres IT asserts that `pairedWithScores`, `allScores` and `extremes`
    return an override over the feed. Check the IT skip count.
- **M1, taken (FR-012 amended).** In the weekly report, only the team-level numbers use resolved scores: the matchup
  scores, records, high/low score and margins. Per-player ratios ("got away with it" efficiency, one-player carry,
  self-inflicted wound) keep the raw breakdown total on both sides.
- **M2, taken.** SC-005 is restated as NBA 2024/2025 with the new fields excluded.
- **M3, kept as briefed.** This needs Allan's call, raised in the report: should the OFFICIAL tier apply to all pages
  before 055's week-3 check?
- **M4, taken.** The panel calls the endpoint with the report's own `leagueSleeperId`, not the URL's.
- **M5, accepted.** The resolver costs 4 small indexed queries per read, so an Awards load adds about 7 resolves. No
  memo for now. Measure it in verification, and add a request-scoped memo if the page is visibly slower.
- **L1, taken.** A non-FEED week that lacks a roster the feed has resolves the whole week as FEED and logs a warning.
  It never mixes sources.
- **L2, L3, L4, L7, taken.** The tiebreak is reproduced exactly. The "hand-set and arbitrary" javadoc moves word for
  word. L4 is recorded as an edge case. Finality was checked on the scratch ingest.
- **L5, L6, deferred.** Recap is FEATURE_OFF in prod. The pot page refetches on mount.

## Renumbered (2026-11-08)

Spec 056 (Football Time Machine) merged to main first as **V46**. That was Allan's call, because 057 was still
uncommitted. Prod Flyway runs with outOfOrder false, so it would refuse a V45 that lands after V46. The migration is
therefore `V47__week_score_override.sql`. The content is unchanged. Mentions of V45 above and in the review and
verification files are historical.
