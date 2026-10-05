# Keeper calculator

Status: **reconciliation doc. Recommendation: don't build.** 2026-10-05.
Gap #11 in [competitor-gap-research.md](competitor-gap-research.md).

Competitors: DraftSharks "Keeper Calculator", Hashtag Basketball "Keeper Tool",
The Front Office keeper cost calculators.

## What it would be

Each candidate keeper's value = projected value minus keeper cost. Cost is
usually the round he was drafted in (or that round minus N), or an auction
price + X. Pick the best K.

## Why not now: no league needs it (measured)

Local DB, 2026-10-05, every ingested league: `settings.type = 0` (redraft).
`max_keepers = 1` everywhere, but that's Sleeper's default for a redraft league
and not evidence of keepers. Draft picks carry Sleeper's `is_keeper` flag, but
`draft_pick` (`V1__init.sql:71`) doesn't store it, so "no keepers ever used" is
inferred from league type, not checked pick by pick. If it matters, check the
raw `/draft/{id}/picks` responses for `is_keeper: true` first.

Same reasoning as ad-hoc league sizing, which was demoted to `ideas/` because no
real league needed it (memory: league size last).

## If a keeper league shows up, this is the design

- Ingest: store `is_keeper` on `draft_pick` (V27+ — V26 is the highest on main as of 2026-10-05; append-only) and Sleeper's
  keeper-related league settings.
- **The simulator must honor kept players first.** A keeper is a locked pick in
  a known slot. The engine already supports locked picks via `startState`
  (memory: reactive resimulation; also the locked-pick leak fix). That
  integration is the actually valuable part. A keeper calculator alone is a
  spreadsheet.
- Value: [projection-tools.md](projection-tools.md)'s per-player season value,
  minus the value of the pick surrendered. Use the board's expected value at
  that pick, which the board already implies.
- Cost rules vary per league and must be **stated by the commissioner**, not
  defaulted (feedback: optional params that encode rules).

## Acceptance criteria (if ever built)

1. A mock of a keeper league never offers a kept player to another seat.
2. Keeper cost rule is required input; there's no silent default.
3. Recommendation ordering test: a player with value 100 at cost 60 beats value
   110 at cost 90.
