# Data model: Fan-first redesign

There's **no migration.** No table changes. Everything below is either a new field
on an existing JSON response (mirrored in `web/src/api.ts` in the same change) or a
frontend-only value derived from data the client already has.

## Backend response additions

### LeagueHistory (`GET /api/leagues/{id}/history`)
| Field | Type | Rule |
|---|---|---|
| `canCommission` | boolean | `LeagueMembership.canCommission(league, caller)`, the same helper Power/Forecast/Superlatives use. Gates History's Compute button (R1). |

### ExpectedWinsTeam (`GET /api/leagues/{id}/expected-wins`)
| Field | Type | Rule |
|---|---|---|
| `allPlay` | `{ wins, losses, ties }` ints | Each scored regular-season week: teams outscored / outscored by / tied. Summed. |
| `median` | `{ wins, losses, ties }` ints | Each scored week: above / below / equal to that week's median score. |

**Invariants (tested):** per team, `allPlay.wins + losses + ties = weeks × (n − 1)`.
League-wide, `Σ allPlay.wins = Σ allPlay.losses`. Median wins per week =
`floor(n/2)` for even `n` with no ties at the median.

### RosterManagement rows and Analysis ranking-score rows
| Field | Type | Rule |
|---|---|---|
| `grade` | `'A+' \| 'A' \| … \| 'F'` or null | `LetterGrades.forRank(rank, teamCount)` using `draftsim.grades` cutoffs. Null when the row has no rank (unavailable). Tied ranks → same grade. |

Each payload also gets `gradesEarly: boolean`: `weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS`.

### Config (`config/weights.yml`)
```yaml
draftsim:
  grades:        # ARBITRARY: rank percentile (0 = best) → grade. Not fitted.
    cutoffs: [...]   # ordered list of {maxPercentile, grade}; values chosen at build, recorded in the PR
```
`SeasonWindow.EARLY_THRESHOLD_WEEKS = 4` is the existing value, moved (not copied)
out of `SeasonSuperlativesService`.

## Frontend-only values

### Destination (existing, `web/src/destinations.ts`), gains:
| Field | Type | Values |
|---|---|---|
| `group` | enum | `home` · `thisWeek` · `season` · `draft` · `history` |
| `label` | string / fn | fan name (see below). The current label becomes `formerLabel`, used only in jump-to search so old names still find the page. |
| new row `home` | | `/leagues/:id`, group `home`, all sports |

Fan names: League home · Matchups & awards (weekly report) · Power rankings ·
Standings (history) · Team strength (analysis) · Luck (expected wins) · Bench points
(roster management) · Playoff odds (forecast) · Awards (superlatives) · Draft room /
Draft board · Mock draft · Scouting report (/managers, outside the league block).

### PlayerFace
Input: `sport`, `sleeperId`, `team | null`, `position`, `name`.
States, in order: `photo` → `logo` → `initials`. `DEF` starts at `logo`; a null
`team` goes from `photo` straight to `initials`. The transition is only ever
forward, driven by image error.

### DraftTier
Derived from the available list sorted by ADP. A new tier starts when
`adp[i] − adp[i−1] > TIER_ADP_GAP`. ADP 999 goes to a final "Unranked" group.
*Amended at task generation:* `TIER_ADP_GAP` is an exported constant in
`web/src/tiers.ts`, labelled arbitrary, not a `weights.yml` value. It only groups
a display list, and no backend code reads it, so keeping it in the frontend is
still one source of truth, without adding it to a response.

### Archetype (`managerBehaviour.ts`)
Input: the existing `BehaviourInputs`. Output: `{ label, basis: 'reach' | 'tilt' | 'none' }`.
- `basis: 'reach'` only when `picksScored > 0` and |relativeReachBias| exceeds a
  labelled cutoff beyond its std-err.
- Otherwise the strongest positional tilt above a labelled cutoff (`'tilt'`).
- Otherwise "Not enough history" (`'none'`). All NBA managers land in tilt/none
  today (R9).

### Color tokens (`styles.css :root`)
| Token | Meaning (one only) |
|---|---|
| `--bg`, `--panel`, `--row-hover` | the two surfaces, plus hover |
| `--crimson` / new `--crimson-fill` | you (ring/text / fill behind text, ≥ 4.5:1) |
| `--teal` | interaction: hover, focus, selected |
| new `--volt` | the page's one primary action |
| new `--up` / `--down` | better / worse |
| `--fitted` | earned: fitted history, awards, trophies |
| position tokens | unchanged |
