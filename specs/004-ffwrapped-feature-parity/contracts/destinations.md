# Contract: frontend route declaration

**Applies to**: every page added by this feature.

`web/src/destinations.ts` is the single declaration of what pages a league has. Its own header records
why it exists: the same knowledge previously lived in three places, drifted, and produced a rail that
linked you to Analysis and then deleted the section that linked you — *"Same class as the multi-sport
landmines: two implementations of one rule, disagreeing quietly."*

Four consumers read it: `railLeague.ts`, `LeagueRailSection`, `searchIndex.ts`, and the league switcher.
`destinations.test.ts` fails if `App.tsx` grows a league-scoped route without a matching row, which is
what stops this from rotting again (SC-006).

## Required rows

| `key` | Route | `sports` | Story |
|---|---|---|---|
| `rosterManagement` | `/leagues/:sleeperLeagueId/roster-management` | `['nfl', 'nba']` | US2 |
| `expectedWins` | `/leagues/:sleeperLeagueId/expected-wins` | `['nfl', 'nba']` | US3 |
| `forecast` | `/leagues/:sleeperLeagueId/forecast` | `['nfl', 'nba']` | US4 |
| `weeklyReport` | `/leagues/:sleeperLeagueId/weekly-report` | `['nfl', 'nba']` | US5 |

Transactions (US6) extends the Roster Management page rather than adding a route, matching ffwrapped,
where they are sections of one view.

## Obligations

1. **Every new route gets a row.** Adding one to `App.tsx` alone must fail `destinations.test.ts`
   (FR-012, SC-006).
2. **`sports` is written out, never defaulted.** The field's own javadoc gives the reason: *"a defaulted
   sport list asserts a rule rather than a value, which is exactly how a football-only page gets offered
   to a basketball league."* All four pages here are genuinely both-sport, so all four say so explicitly
   — an explicit `['nfl', 'nba']`, not an omission that happens to behave the same way today.
3. **`match` and `idKind` are set** so the rail keeps league context on the new routes. Omitting them
   recreates the exact 003 defect — a page the rail links to and then strands you on.
4. **A projection-bound page added later declares `sports: ['nfl']`** (R9, FR-005).

## Acceptance

- Each new page appears in the rail's League section, marked current when it is the active route.
- Each is reachable from the command palette, labelled with its league.
- Each survives a league switch to another league that offers it, and falls back gracefully to History
  for one that does not — the behaviour 003 already specified for Analysis.
- An NBA league shows all four; none is hidden by a sport gate, because none is projection-bound.
