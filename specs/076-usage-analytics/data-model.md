# Data model: Usage analytics we own (spec 076)

## V51 migration (append-only; one file, `V51__usage_analytics.sql`)

### New table `usage_daily`

| Column | Type | Notes |
|---|---|---|
| `day` | `date not null` | The day in `America/New_York` the send arrived. |
| `site` | `text not null` | `timemachine`, `leagues` or `combined` (check constraint). |
| `kind` | `text not null` | `view`, `league-view`, `event`, `rejected` (check constraint). |
| `route` | `text not null default ''` | Route template for `view` / `league-view`; `''` otherwise. |
| `name` | `text not null default ''` | Event name for `event`; `''` otherwise. |
| `props` | `text not null default ''` | Canonical `key=value` pairs, keys sorted, joined by `;`. Built by the server from validated values only. |
| `league_id` | `text not null default ''` | Sleeper league id for `league-view`; `''` otherwise. |
| `sport` | `text not null default ''` | From the `league` table for `league-view`; `''` otherwise. |
| `count` | `bigint not null` | |

Primary key `(day, site, kind, route, name, props, league_id)`. Empty strings, not nulls, so the key works
and `on conflict` has one target. A send is
`insert ... values (..., 1) on conflict (...) do update set count = usage_daily.count + 1`.

No column holds an address, a user agent, a hash, a guest id, a user id, a game id or a timestamp finer
than the day (FR-002, FR-007).

### New column `historical_game.started_as_guest boolean` (nullable, no default)

*Amended after review 2026-11-17 (F1)*: was NOT NULL with the default dropped, which would have refused
every insert from code that does not know the column (an old container during a deploy, a rollback, other
branches' ITs on the shared local database).

Added nullable, then existing rows backfilled `= (sleeper_user_id like 'guest:%')`. Set by
`HistoricalGameRepository`'s insert via `Identity.isGuest`. Never updated — claiming a guest's games leaves
it alone, which is the point (research R1). **Null means not recorded**; the funnel counts those as
`unknownStarted` instead of calling them signed-in.

Known inexactness: a game a guest started and claimed before this migration ran reads as signed-in.

## What the browser sends (the whole of it)

`POST /api/usage`, JSON body, one of:

| kind | Fields |
|---|---|
| `view` | `site`, `route` |
| `league-view` | `site`, `route`, `league` |
| `event` | `site`, `name`, `props` (object, may be empty) |

Event vocabulary:

| name | props | Fired when |
|---|---|---|
| `sign-in` | none | as spec 052 |
| `league-added` | `via`: `sleeper` \| `id` \| `espn` | as spec 052 |
| `mock-started` | `from`: `setup` \| `live` | as spec 052 |
| `result-shared` | `sport`: `nba` \| `nfl`; `who`: `guest` \| `member`; `daily`: `yes` \| `no` | the share sheet resolved, or the clipboard write succeeded. Not on `AbortError`, not on the manual-copy fallback. |

Headers: `Content-Type`, and `Authorization` when the build has an API token. Never `X-Sleeper-User`,
`X-Guest-Id`, `X-League-Session`, `X-Admin-Token`.

Forbidden in any field: everything spec 052's data model forbids, plus the guest id and the Redraft game id.

## What the owner page reads

`GET /api/admin/usage?from=YYYY-MM-DD&to=YYYY-MM-DD` → `UsageReport` (see contracts/usage-api.md). Counter
sections come from `usage_daily`; the funnel comes from `historical_game` grouped by start day (ET) and
sport.

Funnel row, per start day and sport (`nba` / `nfl`, lowercase everywhere in the report): `started`,
`finished` (status `COMPLETE`), `abandoned`, `drafting`, `guestStarted`, `guestFinished`, `unknownStarted`
(null `started_as_guest`), `dailyStarted`, `dailyFinished`, `ownerStarted`, `ownerFinished` (rows owned by
the configured owner id; 0 when none is configured). `finished / started` is a real cohort rate here: the
same rows. *(Amended after review: F5, F11.)*

The wire `route` is the `url` form of a template (`/leagues/:league/power`), and the vocabulary is every
string `routeTemplate()` can return for a site, `/(landing)…` and `/(not-found)` included (F2). The send
passes `referrerPolicy: 'no-referrer'` (F12).

## Hosted service (Umami) additions, only if it is ever configured

One named event, `result-shared`, with `sport`, `who`, `daily`. *Amended after review (F6)*:
`game-started` and `draft-finished` are not built — the saved games already count them, and a second count
under the same name in a service that has never run is not worth four new call sites.
