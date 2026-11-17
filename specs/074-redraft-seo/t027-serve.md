# T027: measured serve behaviour (2026-11-17)

Command, from `web/`: `VITE_SITE=timemachine REDRAFT_SEASON_DATA=src/__fixtures__/redraft-seasons.json npm run build`, then `npx serve@14.2.6 dist --config ../serve.site.json -l 4391` (the Dockerfile's command, no `-s`). Windows, `curl` without scripts. The fixture is hand-shaped (2 NBA + 2 NFL seasons), so the page set is `/`, `/daily`, `/nba`, `/nfl`, `/nba/1976-77`, `/nba/2016-17`, `/nfl/2006`, `/nfl/2007`. Servers started on 4391 and 4392 only, each killed by its own PID after checking its command line. Nothing on 8080, 5173, 5432 or 5433 was touched.

**Measured, not assumed.** Every status, `Location` and meta-tag value below was read from a real response. Not measured: behaviour with the real backend's data (T047), Railway's edge in front of serve, and any browser.

## Timemachine build

`Noindex` = the delivered body contains `<meta name="robots" content="noindex">`. `Sec` = the response carries `X-Content-Type-Options: nosniff` (one of the four `serve.json` headers; the other three travelled with it in the sample I printed in full).

| Path | Status | Location | Noindex | Title | Sec |
|---|---|---|---|---|---|
| `/` | 200 | | no | Ball Knowers Time Machine: Redraft a Real NBA or NFL Season (legacy head, see note 1) | yes |
| `/daily` | 200 | | no | Daily Redraft: A Past NBA or NFL Season Every Day \| Ball Knowers | yes |
| `/nba` | 200 | | no | NBA Redraft: Every Past Season, 1976-77 to 2016-17 \| Ball Knowers | yes |
| `/nfl` | 200 | | no | NFL Redraft: Every Past Season, 2006 to 2007 \| Ball Knowers | yes |
| `/nba/2016-17` | 200 | | no | 2016-17 NBA Redraft: Fantasy Basketball Draft Do-Over \| Ball Knowers | yes |
| `/nba/1976-77` | 200 | | no | 1976-77 NBA Redraft: ... | yes |
| `/nfl/2006`, `/nfl/2007` | 200 | | no | 2006 / 2007 NFL Redraft: Fantasy Football Draft Do-Over \| Ball Knowers | yes |
| `/nba/2016-17?x=1` (query string) | 200 | | no | same as `/nba/2016-17` | yes |
| `/nba/` | 301 | `/nba` | | | no |
| `/nba/2016-17/` | 301 | `/nba/2016-17` | | | no |
| `/nba/2016-17.html` | 301 | `/nba/2016-17` | | | no |
| `/daily.html`, `/daily/` | 301 | `/daily` | | | no |
| `/index.html`, `/index` | 301 | `/index`, then `/` | | | no |
| `/nba/index.html`, `/nba/index` | 301 | `/nba/index`, then `/nba` | | | no |
| `/nba/1900`, `/nba/2016` | **404** | | yes (404.html) | Page not found \| Ball Knowers Redraft | yes |
| `/xyz`, `/xyz/abc`, `/nope.png` | **404** | | yes | Page not found \| Ball Knowers Redraft | yes |
| `/assets` | **404** | | yes | Page not found (directoryListing false works) | yes |
| `/assets/` | 301 | `/assets` | | | no |
| `/game/1`, `/game/abc/def`, `/game` | 200 | | **yes** (app-shell.html) | Ball Knowers Redraft | yes |
| `/game/1/` | 301 | `/game/1` | | | no |
| `/history`, `/history/5` | 200 | | **yes** | Ball Knowers Redraft | yes |
| `/leagues`, `/leagues/1/x`, `/drafts/9`, `/mock/3`, `/managers/2`, `/admin/x` | 200 | | **yes** | Ball Knowers Redraft | yes |
| `/robots.txt`, `/sitemap.xml`, `/boot.js` | 200 | | no | plain text / XML / JS | yes |
| `/404`, `/app-shell` | 200 | | yes | the two private files answer 200 at their own address (accepted, R4); both noindex, neither linked or in the sitemap | yes |
| `/404.html`, `/app-shell.html` | 301 | `/404`, `/app-shell` | | | no |
| `/%2e%2e/serve.site.json` | 400 | | | serve's own bad-request page | no |
| `/../serve.site.json` | 404 | | yes | the config file is not reachable (it sits beside dist, not in it) | yes |

Other measured facts:

- The embedded `<script type="application/json" id="redraft-data">` is present on `/nba/2016-17` and absent from `/game/1`.
- The 404 response carries all four `serve.json` headers (CSP-report-only, nosniff, referrer policy, HSTS), printed in full for `/nba/1900`. The 301 responses carry none of them: serve sends its headers only with a file.
- `Content-Disposition: inline; filename="404.html"` on the 404.
- `sitemap.xml` lists exactly the eight pages above, home as `https://www.ballknowers.co/`, every other slashless, and none of the 404/app-shell/game paths.
- `grep -il "time machine" dist/*.html dist/*/*.html` finds only `dist/index.html` (the legacy home head and body, note 1). No other generated page says it.
- `/leagues` bare, and `/game` bare, are rewritten (200 shell) because each is listed bare and with `/**`. The glob alone was not tested for the bare form.

Note 1: `/` still serves the existing front door (spec 073 head and static body, "Time Machine" wording) because `landingHtml.test.tsx` compares that body with the React `TimeMachineSignIn`. The new home body and head exist (`homeBody`, `redraftPages` home entry) and are unused until Phase 5 swaps them in together with the React home.

## Leagues build (`VITE_SITE=leagues`)

`serve.site.json` is `{ rewrites: [{ source: "**", destination: "/index.html" }], headers }`. Compared against `npx serve@14.2.6 -s dist --config ../serve.json -l 4392` on the same `dist`, for status, content type, body hash and every header except Date/ETag/Last-Modified:

| Path | New config | `serve -s` | Same |
|---|---|---|---|
| `/`, `/nba`, `/nba/2016-17`, `/game/1`, `/leagues/5/home`, `/daily`, `/xyz`, `/assets`, `/sitemap.xml` | 200 text/html, same body (md5 2946cec8) | identical | yes |
| `/robots.txt` | 200 text/plain, `Disallow: /` | identical | yes |
| `/boot.js`, `/favicon.svg`, `/og-image.png` | 200, real files | identical | yes |
| `/index.html`, `/nba/2016-17.html` | 301 | 301 | yes |

15 of 15 paths identical. `/sitemap.xml` answers 200 with the HTML shell on both (the locked league site emits no sitemap; it was like that before).

Also checked: the leagues and the combined builds from this tree against the same builds from `HEAD`: every file is byte-identical except the JS bundle (its hash changed because `apiError.ts` gained a `code` field) and `index.html`, which is identical once the bundle hash in its script tag is masked.
