# Data model: Player spotlight on the home page (spec 015)

No stored data changes. No migration, no new table, no new response type. Everything below is
**client-side state** on the root home page, plus the existing payloads it reads.

## Existing payloads, read unchanged

| Payload | Source | Used for |
|---|---|---|
| `DraftSummary[]` | `cachedDrafts()`, already loaded by `DraftPicker` | The league list. Grouped by `leagueLineages()` and filtered by the rail's sport filter. |
| `PlayerSpotlight` | `getPlayerSpotlight(id)` → `/api/leagues/{id}/player-spotlight` (014 contract) | Trending, Rookie watch, basketball's Top of the night, the period, and every reason. |
| `WeeklyReport` | `getWeeklyReport(id, 0)` | Football's Top players (`topPerformers`). Fetched only when the spotlight needs it (research R2). |

## Client state

### LeagueTab (derived, not stored)

One per visible lineage, in `visibleLineages` order.

| Field | From | Notes |
|---|---|---|
| `leagueId` | `lineage.current.sleeperLeagueId` | The newest ingested season, which may be a past one (research R4). |
| `name` | `lineage.current.leagueName` | Tab label. |
| `sport` | `lineage.current.sport` | **Label only** (the sport pill). Never decides what renders or what is fetched (FR-014). |

### Selection

| State | Type | Rule |
|---|---|---|
| `selectedId` | `string \| null` | Starts as the first tab. If it is not among the visible tabs (filter changed, or not set yet), the first visible tab is used. Not persisted across visits (spec assumption). |
| `visited` | `Set<string>` | Grows when a tab is selected. Visited tabs stay mounted and are hidden when not selected (research R5). Never shrinks during a visit. |

### Per-tab blocks (one set per visited tab)

| Block | Loads when | States |
|---|---|---|
| `spotlight: Block<PlayerSpotlight>` | The tab is first visited, and again when `useLeagueDataVersion(leagueId)` bumps. | `loading` → `ok` \| `error` (including `notFound`) |
| `weekly: Block<WeeklyReport>` | The spotlight is `ok` **and** `applies` **and** `topOfNight` is absent **and** `period != null`. Otherwise `idle`. | `idle` \| `loading` → `ok` \| `error` |

### What a tab panel shows (state → render)

| spotlight | Panel |
|---|---|
| `loading` | Skeleton rows, with the label "Loading player spotlight" (the league home's wording). |
| `error` (any, including 404) | "Couldn't load the player spotlight for {name}." The other tabs are unaffected. |
| `ok`, `applies: false` | "No player spotlight for {season}: it covers the current season only." (R4) |
| `ok`, `applies: true` | `SpotlightLists`, which owns every per-section empty state, worded exactly as on the league home. |

## Validation rules carried from the spec

- Points, owners, periods and reasons are never computed on the home page. They are rendered from
  the same payloads `LeagueHome` renders (FR-004).
- Nothing is combined across tabs. Each tab's blocks hold one league's payloads only.
- A 404 from the spotlight (a league the viewer is not in) shows as that tab's error and is never
  retried in a loop (FR-005, FR-006).
