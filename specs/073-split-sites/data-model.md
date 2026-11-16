# Data Model: Split the website into a Time Machine site and a league site

No database tables, no API records and no stored data change. The only "model" is build configuration.

## Site (build-time value)

| Field | Values | Source | Rule |
|---|---|---|---|
| `site` | `combined`, `timemachine`, `leagues` | `VITE_SITE` | unset or empty is `combined`; any other string fails the build |
| `tmOrigin` | absolute origin, no trailing slash | `VITE_TM_ORIGIN`, default `https://www.ballknowers.co` | used for canonical tags on `combined`/`timemachine`, and as the redirect target from `leagues` |
| `leaguesOrigin` | absolute origin, no trailing slash | `VITE_LEAGUES_ORIGIN`, default `https://leagues.ballknowers.co` | used for canonical tags on `leagues`, and as the redirect target from `timemachine` |

Derived per site: canonical origin, Time Machine home path, Time Machine game path, shell, front door, delivered title / preview / robots / sitemap. See [contracts/site-routes.md](contracts/site-routes.md).

## Unchanged

- **Sign-in**: `localStorage["bk.user.v1"]`, per origin.
- **Time Machine progress**: `historical_*` tables keyed by Sleeper user id. No migration.
- **Backend configuration**: `CORS_ORIGINS` gains one entry at deploy time.
