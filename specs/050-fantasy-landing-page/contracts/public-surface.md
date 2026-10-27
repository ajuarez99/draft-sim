# Contract: the public surface of www.ballknowers.co

What each requester gets, after this feature. "Bot" = any fetch that does not run JS.

| Request | Status | Content-Type | Body / key content |
|---|---|---|---|
| `GET /` (bot) | 200 | text/html | title, description, canonical `https://www.ballknowers.co/`, og:* incl. absolute og:image 1200×630, `twitter:card=summary_large_image`, JSON-LD WebApplication, and `#root` containing the `h1`, subheadline and the four group `h2`s with their feature names |
| `GET /leagues/<id>/power` (bot) | 200 | text/html | byte-identical to `/` — site-level preview, no league data |
| `GET /og-image.png` | 200 | image/png | 1200×630 PNG, < 1 MB |
| `GET /robots.txt` | 200 | text/plain | rules in plan D5, `Sitemap:` line |
| `GET /sitemap.xml` | 200 | application/xml or text/xml | valid urlset with one `<loc>https://www.ballknowers.co/</loc>` |
| `GET /boot.js` | 200 | application/javascript | sets `html[data-bk-user]` when a stored user parses |
| `/` in a browser, signed out | — | — | landing page: hero + form, four feature groups; same text as the bot version |
| `/` in a browser, signed in | — | — | league home exactly as today; prerender never visible |

Forbidden anywhere in title/description/og/twitter/h1/subheadline: "Monte Carlo",
"simulat*", accuracy/usage claims (R6 list).
