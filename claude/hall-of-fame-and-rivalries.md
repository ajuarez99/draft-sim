# Hall of fame and rivalries

Status: **design, not built.** 2026-10-05. Gap #5 in
[competitor-gap-research.md](competitor-gap-research.md).

Competitors: League Tycoon's Hall of Fame (all-time standings, H2H, weekly high
scores, trophy case; regular-season and playoff H2H split), ffwrapped "Manager
Profiles & Rivalries", League Tycoon's trophy shop (champion, toilet bowl, last
place).

## What's there now

Most of the data, and most of the views:

- `LeagueRecordService.RecordBook` (`engine/LeagueRecordService.java:113`):
  highest and lowest weeks, margins, all-time points leaders, win and loss
  streaks.
- `HeadToHeadService` (`engine/HeadToHeadService.java:57-81`): per-sport H2H
  between two chosen managers, at `/managers/:a/versus/:b`.
- Champions: a 🏆 in a standings row (`LeagueHistory.tsx:441`,
  `ManagerHistory.tsx:196`), from `roster_season.final_placement` with the V21
  `league.status` gate.
- Superlatives (spec 008): 13 per-season awards, shown since spec 013 as a
  one-row-per-award "trophy list". That's *season* awards, not the all-time
  trophy case proposed below.
- Standings (spec 013): record vs all and vs the weekly median per season
  (`LeagueHistory.tsx:429-432`).

What's missing is the **gathered view** and **discovery**. You have to already
know which two managers to compare. There's no single trophy case, nothing tells
you who your rival is, and H2H doesn't split regular season from playoffs.

## Proposed design

### Trophy case

One page section per league chain, one row per completed season: champion,
runner-up, last place, regular-season points leader. Sources are
`final_placement` and `roster_season`. Last place is the highest
`final_placement`. If a league's placements don't cover every roster (Sleeper
only places playoff teams in some formats), the row says "last place not
reported by Sleeper" rather than inferring it from regular-season record. Two
definitions of "last" would be the two-implementations-of-one-rule bug.

Past superlative winners appear here too, once a season is final.

### Rivalries, discovered

From every pair's H2H across the chain, per sport (never summed across sports,
per spec 006):

- **Most-played**: the pair with the most meetings.
- **Closest**: lowest |W−L| among pairs with ≥ N meetings. N is hand-set and
  labelled.
- **Nemesis** (per manager): the opponent they have the worst record against, at
  ≥ N meetings.
- **Victim**: the inverse.
- **Playoff meetings** listed separately. This needs the regular-season/playoff
  split added to `HeadToHeadService.Meeting`, using the league's
  `playoff_week_start`.

With 2–3 seasons of history most pairs have met 2–5 times. Every rivalry claim
shows the meeting count beside it, and a pair under N doesn't qualify. A 2–0
"nemesis" isn't one.

### Surface

- A "Hall of fame" section on `/leagues/:id/history` (trophy case + rivalries).
- Nemesis and victim on each manager's page, linking to the existing versus page.

## Not building

- Physical trophies or belts (League Tycoon's shop), which are commerce.
- Cross-league rivalries. H2H is per league chain, and two managers in two
  leagues are two rivalries.

## Acceptance criteria

1. The trophy case for "(Foot) Ball Knowers" names the same champions as the
   existing 🏆 rows. Assert it, don't eyeball it.
2. The regular-season and playoff split sums to the existing H2H totals for every
   pair.
3. No rivalry renders with fewer than N meetings (test).
4. Both sports render, with the NBA chain never mixed into NFL totals.
