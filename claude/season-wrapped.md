# Season Wrapped

Status: **design, not built.** 2026-10-05. Gap #8 in
[competitor-gap-research.md](competitor-gap-research.md).

Competitors: ffwrapped (the name and the concept), Dynasty Daddy's season
recap. ffwrapped's own version says "come back after the season is complete",
and so does this one.

## Why now, when spec 004 said "build last"

`specs/004-ffwrapped-feature-parity/spec.md:274` called Wrapped "a presentation
layer over everything above", to be built once the rest existed. It now does:
Roster Management, Expected Wins, superlatives, career profiles, H2H, and the
record book all shipped. And there's a **complete season to build against**:
NFL 2025 for both football leagues and NBA 2025 for "Ball Knowers", all with
`league.status` complete (V21).

## What's there now (all reusable, none of it new math)

| Wrapped slide | Source |
|---|---|
| Final record and finish | `roster_season`, `final_placement` |
| Points for, rank in league | `roster_season.points_for` |
| Lineup efficiency (points vs optimal) | `RosterManagementService` / `points_possible` |
| Luck (real vs all-play wins) | `ExpectedWinsService` |
| Best and worst week | `roster_week_points` |
| MVP (most started points) | `roster_week_points.starters` × `players_points` |
| Best pickup | `TransactionAnalysisService` adds + `WaiverPickupAttribution` |
| Superlatives won | `SeasonSuperlativesService` |
| Biggest rival this season | `HeadToHeadService` (season-scoped) |
| Draft: best pick / bust | [draft-grades.md](draft-grades.md) (if built; slide omitted with reason if not) |
| Career context ("3rd title in 4 seasons") | `ManagerCareerService` |

## Proposed design

`/leagues/:id/wrapped/:managerId`: a vertical sequence of full-width cards, one
fact per card, big number plus one sentence. Plus a league-level card set
(champion, the season's records broken). It's only offered when
`league.status` is complete. Before that the route says "the season isn't over",
the same gate the champion write uses (V21). One implementation of "is the
season over".

`GET /api/leagues/{id}/wrapped/{managerId}` aggregates existing services. **No
new computation lives here.** If a slide needs math no page already does, that
math belongs in its own service first. Otherwise Wrapped becomes a second
implementation of a number some other page shows differently.

Shareable: a static image per card (OG image) is the obvious ask. It's deferred
and listed below.

Design: card/avatar dark UI, matching the existing look (memory: design
aesthetic). Not uniform flat cards: each slide's layout follows its content
(memory: avoid flat uniform cards).

## Not building

- An LLM-written narrative. That's [ai-recap-and-historian.md](ai-recap-and-historian.md),
  and Wrapped must stand without it.
- Image export or social sharing in v1.
- Wrapped for an in-progress season.

## Acceptance criteria

1. Every number on every slide equals the same number on the page it came from,
   for one real manager in each of NFL 2025 and NBA 2025. Diff them in a test
   that calls both services. This is the main risk.
2. A season with `league.status` not complete returns the "not over" state
   (test with the 2026 leagues).
3. A slide whose source is unavailable (e.g. draft grades not built) is omitted
   with a stated reason, never shown empty.
4. Live click-through on a phone-width viewport.
