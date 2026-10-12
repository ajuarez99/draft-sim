# Contracts: spec 030

Every new or changed Java record gets mirrored field for field in `web/src/api.ts` in the
same commit (constitution rule 2). Any response map that can hold a null is built mutably,
never with `Map.of` (rule 4). Reasons go on the wire as **codes**, and the sentences live in
the web client (the spec 018 convention).

## C1. `GET /api/leagues/{sleeperId}/season-start` (new)

Membership-scoped like the other league routes. It's computed on request. The only heavy
part is the strength pass, which is the same pass as C2, so it's shared, not duplicated.

```jsonc
{
  "sport": "nba",
  "season": 2026,
  "state": "DRAFTED_UNSCORED",          // E2: PRE_DRAFT | DRAFTED_UNSCORED | IN_SEASON | COMPLETE
  "firstWeek": {                        // E7; null unless state is DRAFTED_UNSCORED
    "week": 1,                          // nullable
    "startDate": "2026-10-20",          // nullable: the page says "not known yet"
    "gamesThisWeek": { "mine": 4, "byRoster": { "1": 4, "2": 3 } }  // nullable (NFL, or schedule not loaded)
  },
  "strength": {                         // null when state is PRE_DRAFT or COMPLETE
    "available": true,
    "reason": null,                     // code when unavailable: PROJECTIONS_NOT_LOADED | ROSTERS_EMPTY | NOT_BUILT_FOR_SPORT
    "unit": "PER_WEEK",
    "top": [ /* first 3 RosterProjection rows (C2 shape), bench and starters omitted */ ],
    "me":  { /* the caller's RosterProjection row if not in top, else null */ },
    "teams": 12,
    "favorite": {                       // E5; null when unavailable
      "leaders": [ { "rosterId": 7, "teamName": "…", "username": "…", "avatarId": "…", "total": 251.4 } ],
      "tie": false,
      "marginPoints": 6.2,              // pts/week, always
      "marginPct": 2.5,
      "nearTiePct": 1.0                 // the configured, ARBITRARY threshold, so the page can state it
    },
    "provenance": { "source": "rotowire", "sourceModifiedAt": "…", "fetchedAt": "…" },
    "unprojectedCategories": ["bonus_ast_15p", "bonus_pt_40p", "bonus_pt_50p", "bonus_reb_20p", "ff", "tf"],
    "basisNote": "ONE_AVERAGE_GAME_PER_STARTER"
  },
  "draft": {                            // E6
    "draftId": "…",                     // nullable
    "grades": null                      // DRAFTED_UNSCORED: always null (R6). IN_SEASON: { best, worst, gradesEarly }
  }
}
```

Errors: 404 for an unknown league or one that isn't the caller's (the shared not-found
convention). Anything else is a 200 with `available: false` and a reason code inside the
affected block. One missing input never fails the whole response (FR-014).

## C2. `GET /api/leagues/{sleeperId}/analysis` (changed)

- For `nba`, `projections` is now computed instead of returning `NOT_BUILT`. The
  `projectionsNotBuiltReason` path stays for any other sport.
- `Projections` gains `unit`, `basisNote`, `provenance` and `unprojectedCategories`
  (data-model E4). For NFL: `unit: REST_OF_SEASON`, `basisNote: null`,
  `unprojectedCategories: []`, and `provenance` from `player_projection`'s newest
  `fetched_at` and `source` in the window.
- For `nba`, `RosterProjection.byWeek` is `[]`.
- For `nba`, `matchups` (the next week's pairings valued) uses the same per-game
  `pointsByPlayer` map, so the home's strength numbers and the matchup numbers can't
  disagree.
- `rankingScores` and `scores` are unchanged. They need scored weeks and already say so.

**Unchanged for NFL**: every existing NFL field keeps its value (SC-005). This is checked by
comparing the JSON before and after on the live NFL league, the way spec 021 did.

## C3. `POST /api/ingest/season-projections?sport=nba&season=2026[&force=true]` (new, admin)

Behind `AdminGateInterceptor` (`X-Admin-Token`), like the other ingest routes. It returns
`{ fetched: bool, fresh: bool, rowsStored, rowsDeleted, skippedNoStats, source,
sourceModifiedAt, rejectedReason }`. *Amended after code review (R4/R5):* `rejectedReason` is null when
the fetch was fine, and a sentence when nothing was stored and the existing rows were kept: a `gp != 1.0`
row, no body, no row with `pts`, or a fetch whose kept-row count is under 50% of the stored count (a
hand-set, ARBITRARY constant). The daily step maps a non-null `rejectedReason` to
`DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT` with the reason as detail. `sport=nfl` gets a 400 with a sentence saying NFL season projections
aren't wired up. The NFL path keeps using weekly `player_projection`.

## C4. Daily refresh step (changed)

`DailyRefreshService.runAll` gains a step `season-projections` for each sport that has a
league in `PRE_DRAFT` or `DRAFTED_UNSCORED`/`IN_SEASON` with a current season. That's NBA
only for now. The step reports through the existing `StepResult`
(`OK | SKIPPED_FRESH | FAILED` + detail), and one step failing doesn't fail the others
(spec 009's convention).

## C5. UI contract: league home (`LeagueHome.tsx`)

Driven by `season-start.state`, never re-derived client-side:

| State | Shown | Removed |
|---|---|---|
| `PRE_DRAFT` | today's page, unchanged | — |
| `DRAFTED_UNSCORED` | header "{season} · draft complete · first games {date or 'date not known yet'}"; **Strength** block (favorite headline + top 3 + you, link to Analysis); **Next matchup** (NBA, existing) with your week-1 game count; **Draft** block linking the completed board; Spotlight (existing) | Latest matchup, Power headline, Awards, empty Standings |
| `IN_SEASON` | today's page, unchanged, **plus** the draft-grades summary block | — |
| `COMPLETE` | today's page, unchanged | — |

Strength block copy rules (FR-007a, FR-013): the headline reads "Projected strongest
roster: {team}, by {marginPoints} pts/week ({marginPct}%)". A tie reads "Projected dead
heat: {a} and {b}, within {nearTiePct}%". Every number carries "projected". The footer
names the source, its update time, "accuracy for this league not measured", and the
unprojected categories in words. No "odds", "likely" or "% to win" anywhere: a test
asserts it on the rendered block.

## C6. UI contract: Analysis page (`LeagueAnalysis.tsx`) for basketball

- `destinations.ts`: Analysis `sports` becomes `['nfl', 'nba']`. `analysisOffered` follows.
- Projection numbers are labelled with `unit`: "pts/week (projected)" for `PER_WEEK`, and
  today's wording for `REST_OF_SEASON`.
- The week-by-week chart/strip is hidden when `byWeek` is empty, and nothing gets drawn
  in its place.
- The ranking-score and scores-grid blocks keep their existing "needs N scored weeks"
  empty states.
- The NBA home keeps `NextMatchupBlock` (spec 017 F7). It doesn't switch to the analysis
  next-opponent block.
