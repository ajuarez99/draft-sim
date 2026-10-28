# Data model: Site analytics (spec 052)

Nothing is stored by this site: no migration, no backend change, no `api.ts` type change.
All records live in the hosted service. This file defines exactly what the site sends — the
whole of it. Anything not listed here must not leave the browser (FR-006, SC-005).

## Page view (one per route change)

| Field | Value | Notes |
|---|---|---|
| `website` | `VITE_UMAMI_WEBSITE_ID` | |
| `url` | route template, e.g. `/leagues/:league/power` | From `routeTemplates` (see contract). Unmatched paths → `/(not-found)`. Signed out → `/(landing)` + template (plan F1). Never `location.pathname`; never a query string or hash. |
| `title` | the template's label, e.g. `League power rankings` | Never `document.title`. |
| `referrer` | outside origin only, first view of the document only | e.g. `https://www.google.com`; omitted otherwise (R3). |
| `hostname`, `language`, `screen` | browser values | Sent so the dashboard's host/language/device views work; none identify a person. |

The service derives visitors and visits itself, without cookies (its own salted daily hash).

## League view (named event `league-view`)

| Property | Value |
|---|---|
| `route` | same template as the page view |
| `sport` | `nfl` or `nba` (the `Sport` wire form) |
| `league` | lineage's current-season `sleeperLeagueId` |

Sent once per route change, only when the rail resolves a league for that route (R4).
A signed-out page or a page with no league sends none — the dashboard's page views minus
league views is "no league" (US3 scenario 2).

## Key action (named events)

| Event | Properties | Fired when |
|---|---|---|
| `sign-in` | none | Sleeper username recognised and saved |
| `league-added` | `via`: `sleeper` \| `id` \| `espn` | any of the three add-league paths succeeds |
| `mock-started` | `from`: `setup` \| `live` | the mock session is created |

Fired after success only (US2 scenario 2), once per user action (scenario 1).

## Forbidden in any field

Sleeper username, Sleeper user id, display name, manager/owner id, draft id, player id,
mock session id, ESPN cookies, full URLs of this site, league names, page titles.

*Amended after review 2026-10-28*: `live-draft-followed` dropped (F5); `league-added` covers three paths (F6); signed-out views prefixed `/(landing)` (F1); every send is object-form, never `track(name, data)` (F4).
