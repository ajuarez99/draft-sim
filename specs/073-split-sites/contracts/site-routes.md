# Contract: what each site serves at each path

`TM` = `tmOrigin()`, `LG` = `leaguesOrigin()`. "gate" = the signed-out visitor sees that site's front door at the same URL and the page after signing in. "no gate" = happens signed in or out.

## Route table

*Amended 2026-11-16: in the `leagues` column, "lock →" means the visitor first sees only the admin-token lock screen at that same URL (spec FR-021) and, once unlocked, the gate behaviour that follows. The two `/history` rows are outside the lock: they show nothing and still redirect. `combined` and `timemachine` have no lock.*

| Path | `combined` | `timemachine` | `leagues` |
|---|---|---|---|
| `/` | gate → league picker | gate → Time Machine home | lock → gate → league picker |
| `/game/:gameId` | no gate → `/history/:gameId` (in-app) | gate → Time Machine game | lock → not found |
| `/history` | gate → Time Machine home | no gate → `/` (in-app) | no gate → `TM/history` |
| `/history/:gameId` | gate → Time Machine game | no gate → `/game/:gameId` (in-app) | no gate → `TM/history/:gameId` |
| `/leagues/*` | gate → league pages | no gate → `LG` + same path, query, hash | lock → gate → league pages |
| `/drafts/*` | gate → draft pages | no gate → `LG` + same path, query, hash | lock → gate → draft pages |
| `/mock/*` | gate → mock pages | no gate → `LG` + same path, query, hash | lock → gate → mock pages |
| `/managers`, `/managers/*` | gate → manager pages | no gate → `LG` + same path, query, hash | lock → gate → manager pages |
| `/admin/*` | as today | no gate → `LG` + same path, query, hash | lock → as today |
| anything else | gate → not found | gate → not found | lock → gate → not found |

Notes:

- `/leagues/:id/history` and `/managers/:id/history` are league pages. They match the `/leagues/*` and `/managers/*` rows, never the `/history` rows.
- **Amended after review, 2026-11-15 (PR-06, PR-08).** `leagues` forwards to `TM/history...`, not `TM/game/...`: `/history` is the one address both `combined` and `timemachine` understand, so a forwarded link works during the rollout window and after a rollback. After the flip that costs a second, in-app hop. `combined` also accepts `/game/:gameId` for the same reason. `/admin` joined the forwarded prefixes.
- Query string and hash are preserved on every redirect, cross-site and in-app.
- A cross-site redirect whose target origin equals the current origin renders not-found instead of navigating.
- Every league-site route in `App.tsx` is `/` or falls under one of the five forwarded prefixes.

## Shell

| | `combined` | `timemachine` | `leagues` |
|---|---|---|---|
| Shell | `AppShell` | `TimeMachineShell` | `AppShell` |
| Top-bar / menu links | Leagues, Mock drafts, Managers, Time Machine | none (wordmark + account) | Leagues, Mock drafts, Managers |
| League header, jump-to search, Ctrl+K | yes | no | yes |
| Signed-out screen | league landing | Time Machine landing | lock screen, then league landing (2026-11-16) |

## Delivered files (read as text, no scripts)

*Amended 2026-11-16: the `leagues` column is the locked site (spec FR-021). Its earlier entries are superseded as shown; `combined` and `timemachine` are unchanged.*

| | `combined` | `timemachine` | `leagues` |
|---|---|---|---|
| `<title>`, description, og/twitter | league wording | Time Machine wording | title "Ball Knowers" only; `noindex, nofollow`; no description, og or twitter |
| canonical, `og:url` | `TM/` | `TM/` | none |
| `og:image` | `TM/og-image.png` | `TM/og-image-tm.png` | none |
| pre-rendered body | league landing | Time Machine landing | none (empty root); no JSON-LD |
| `robots.txt` | `Allow: /`, sitemap line `TM/sitemap.xml` | `Allow: /`, sitemap line `TM/sitemap.xml` | `Disallow: /`, no sitemap line |
| `sitemap.xml` single URL | `TM/` | `TM/` | not emitted (dev server: 404) |

## Time Machine path helpers

| Helper | `combined` | `timemachine` | `leagues` |
|---|---|---|---|
| `tmHome(site)` | `/history` | `/` | throws |
| `tmGame(site, 42)` | `/history/42` | `/game/42` | throws |
| share link | `<origin>/history` | `<origin>/` | n/a |

## Build variable

| `VITE_SITE` | Result |
|---|---|
| unset, empty | `combined` |
| `timemachine` | `timemachine` |
| `leagues` | `leagues` |
| anything else | build fails with a message naming the value and the three accepted ones |

## API access (added 2026-11-16, second pass; backend `LEAGUE_LOCK=true`)

With the lock off (the default) every row is open exactly as before. With it on:

| Route family | Access | Notes |
|---|---|---|
| `/api/health` | public | |
| `/api/history/**` (Time Machine) | public | its own `X-Sleeper-User` identity rule still applies |
| `/api/sleeper/user/{name}` | public | sign-in lookup, both sites |
| `/api/refresh/daily` | public | own `X-Refresh-Secret` |
| `/api/admin/**`, `/api/ingest/**` | admin token | `X-Admin-Token`, 403 `admin_token_required`; unchanged |
| `POST /api/admin/session` | admin token | mints a session, `{token, expiresInSeconds}` |
| `/api/league-session` | locked | 204; renews |
| `POST /api/league-session/stream-ticket?draftId=` | locked | `{ticket, expiresInSeconds: 60}`; a single-purpose ticket for that draft's live stream (CR-02) |
| `/api/drafts/**`, `/api/leagues/**`, `/api/mocks/**`, `/api/managers/**`, `/api/sims/**`, `/api/setup/**`, `/api/targets/**`, `/api/board`, `/api/sleeper/users/{id}/leagues`, `/api/refresh/players`, `/api/leagues/{id}/refresh` and every other `/api/**` route | **locked** | session, admin token, or (live stream only) a `?ticket=` bound to that draft; else 401 `league_session_required` |

Headers: request `X-League-Session`; response `X-League-Session` (renewed token, CORS-exposed). Query `ticket=` only on `GET /api/drafts/{id}/live-stream`, and only a ticket issued for that same draft id (60 s, never renewable, never valid as `X-League-Session`). A session token in any query parameter, on any route, is refused. *(Amended 2026-11-16, lock review CR-02: this line used to say `session=`.)* A session expires `idle-seconds` after its last renewal and at most `LEAGUE_LOCK_MAX_SESSION_SECONDS` (12 h) after the unlock.
