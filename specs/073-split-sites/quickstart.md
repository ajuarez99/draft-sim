# Quickstart: checking the two sites, and switching production over

## Run both sites locally

Prerequisites: a local backend on 8080 with at least one NFL and one NBA league ingested, and a Sleeper user that has Time Machine games.

Start the backend, then one dev server per site (launch entries added by this feature):

- `draft-sim-web-tm` → http://localhost:5184 (`VITE_SITE=timemachine`, `VITE_LEAGUES_ORIGIN=http://localhost:5185`)
- `draft-sim-web-leagues` → http://localhost:5185 (`VITE_SITE=leagues`, `VITE_TM_ORIGIN=http://localhost:5184`)
- the existing `draft-sim-web` → http://localhost:5173 is the `combined` site, unchanged

Both proxy `/api` to the backend, but the proxy keeps the browser's `Origin` header, and the backend allows only 5173 by default, so a call from 5184 or 5185 is probably refused (the plan review reasoned this, nobody ran it). Use the `draft-sim-api-split` launch entry, which sets `CORS_ORIGINS` to 5173, 5184 and 5185. Before relying on it, run the reviewer's curl against the plain backend (`curl -i -H "Origin: http://localhost:5184" http://localhost:8080/api/health`) and record whether the 403 is real. Each port is its own origin, so each asks for a sign-in, as the real sites will.

## What to check

Follow the plan's "Acceptance criteria for the verification pass", using [contracts/site-routes.md](contracts/site-routes.md) as the expected-answer table. The short version:

1. 5173: nothing has changed.
2. 5184: only the Time Machine. `/history` and `/history/<id>` rewrite in place; `/leagues/<id>/power?x=1` lands on 5185 with the path and query intact.
3. 5185: every league page works; no Time Machine link; `/history/<id>` lands on `5184/history/<id>`, which then rewrites in place to `5184/game/<id>`.
4. Build each mode and read `dist/index.html`, `dist/robots.txt`, `dist/sitemap.xml`:

```bash
cd web && npm run build
```

```bash
cd web && VITE_SITE=timemachine npm run build
```

```bash
cd web && VITE_SITE=leagues npm run build
```

## Switching production over (Allan's steps, in this order)

The full version, with the variables and the rollback, is DEPLOY.md "Two sites". Merging this branch changes nothing visible: the existing web service has no `VITE_SITE`, so it keeps building the combined site.

1. **First check, unconfirmed:** that your Railway plan allows a custom domain on a second web service. Nobody has looked this up.
2. **Backend service**: append `,https://leagues.ballknowers.co` to `CORS_ORIGINS`. Harmless on its own.
3. **New Railway web service** from the same repo, Root Directory `web`, build variables `VITE_SITE=leagues`, `VITE_API_BASE` (same as the existing web service) and, if wanted, `VITE_UMAMI_WEBSITE_ID`.
4. **Custom domain** `leagues.ballknowers.co` on that service, and the CNAME Railway shows, in Squarespace DNS.
5. **Check the league site** at its real address: sign in, open a league, a draft board and a mock. If sign-in says "Couldn't reach Sleeper just now", step 2 was missed or mistyped.
6. **Flip the main site**: set `VITE_SITE=timemachine` on the existing web service and redeploy. Do 5 and 6 in one sitting. The league site forwards `/history/<id>` to `www.ballknowers.co/history/<id>`, which both the combined and the Time Machine build understand, so a forwarded link works before and after the flip (after it, with one more in-app hop to `/game/<id>`).
7. **Re-set your own analytics opt-out** on `leagues.ballknowers.co`: it is stored per address.

Formulas saved in the stat calculator travel only on a forward from the main address to the league site, and only when the league site has never saved formulas (a person who deleted theirs there is not given them back). Someone who types the league address directly starts without them there. When the store is very large, the newest formulas that fit in the 24,000-character fragment are carried (50 ordinary ones fit). A visitor forwarded between the sites is recorded with no outside referrer (CR-08, accepted).

**Rolling back**: remove `VITE_SITE` from the existing web service and redeploy; the main address is the combined site again. The league service can stay up or be deleted.

## Not checkable before the switch

Real DNS, Railway's build of each mode, production CORS, Umami receiving the league host, and how link-preview bots render the Time Machine tags. Each needs a look on the day.
