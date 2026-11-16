# Contract: what each site serves at each path

`TM` = `tmOrigin()`, `LG` = `leaguesOrigin()`. "gate" = the signed-out visitor sees that site's front door at the same URL and the page after signing in. "no gate" = happens signed in or out.

## Route table

| Path | `combined` | `timemachine` | `leagues` |
|---|---|---|---|
| `/` | gate → league picker | gate → Time Machine home | gate → league picker |
| `/game/:gameId` | no gate → `/history/:gameId` (in-app) | gate → Time Machine game | not found |
| `/history` | gate → Time Machine home | no gate → `/` (in-app) | no gate → `TM/history` |
| `/history/:gameId` | gate → Time Machine game | no gate → `/game/:gameId` (in-app) | no gate → `TM/history/:gameId` |
| `/leagues/*` | gate → league pages | no gate → `LG` + same path, query, hash | gate → league pages |
| `/drafts/*` | gate → draft pages | no gate → `LG` + same path, query, hash | gate → draft pages |
| `/mock/*` | gate → mock pages | no gate → `LG` + same path, query, hash | gate → mock pages |
| `/managers`, `/managers/*` | gate → manager pages | no gate → `LG` + same path, query, hash | gate → manager pages |
| `/admin/*` | as today | no gate → `LG` + same path, query, hash | as today |
| anything else | gate → not found | gate → not found | gate → not found |

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
| Signed-out screen | league landing | Time Machine landing | league landing |

## Delivered files (read as text, no scripts)

| | `combined` | `timemachine` | `leagues` |
|---|---|---|---|
| `<title>`, description, og/twitter | league wording | Time Machine wording | league wording |
| canonical, `og:url` | `TM/` | `TM/` | `LG/` |
| `og:image` | `TM/og-image.png` | `TM/og-image-tm.png` | `LG/og-image.png` |
| pre-rendered body | league landing | Time Machine landing | league landing |
| `robots.txt` sitemap line | `TM/sitemap.xml` | `TM/sitemap.xml` | `LG/sitemap.xml` |
| `sitemap.xml` single URL | `TM/` | `TM/` | `LG/` |

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
