# Contract: football parity check (US3, FR-012)

## GET `/api/ingest/projection-parity?sport=nfl&season=2026&week=7`

Admin token. Read-only. It scores every `player_projection` row for that week that has `stats`
with `GameScoringService.score(fullPprScoring, stripped(stats))` and compares the result to
the row's `pts_ppr` at a tolerance of 0.01.

```json
{
  "sport": "nfl", "season": 2026, "week": 7,
  "scoringSource": "league 1180...  (\"(Foot) Ball Knowers\", rec=1.0)",
  "rowsWithStats": 424, "rowsWithoutStats": 0,
  "matches": 424, "mismatches": 0,
  "firstMismatches": [
    { "sleeperPlayerId": "4046", "ptsPpr": 18.42, "scored": 18.02, "diff": -0.40, "stats": { "...": 0 } }
  ]
}
```

`scoringSource` always says which scoring map was used: a named local league with `rec = 1.0`,
or `"Sleeper default PPR (documented values)"`. `firstMismatches` is capped at 50.

**Decision rule (FR-013):** `draftsim.projections.nfl-exact-scoring` is set true only after
this reports `mismatches = 0` over at least one full week, or every mismatch is explained in
the spec's verification notes. The count is recorded either way (SC-005).
