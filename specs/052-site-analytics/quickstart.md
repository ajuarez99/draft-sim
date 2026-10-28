# Quickstart / verification guide: Site analytics (spec 052)

## Locally, without an id (FR-014)

1. `cd web && npm run dev`, open the app, click through five pages.
2. DevTools → Network: zero requests to `cloud.umami.is`; Elements: no tracker `<script>`.

## Locally, with a test id (payload shape, SC-005)

1. `VITE_UMAMI_WEBSITE_ID=<any uuid> npm run dev`. `data-domains` stops sends on
   `localhost`, so for this check temporarily read payloads from the queue/`umami.track`
   call log in `analytics.test.ts`, or stub `window.umami.track` in the console and record
   its arguments while clicking through every main page.
2. Expected: every `url` is a template (`/leagues/:league/power`), no 6+-digit number, no
   username, `referrer` absent or an outside origin.

## Blocked tracker (SC-003)

1. With a test id set, block `cloud.umami.is` (DevTools request blocking).
2. Click through sign-in, leagues, board, mock, live, /managers, power rankings, weekly
   report, history: no console errors from the app, nothing renders differently.

## Production (needs Allan's Umami account and the build variable)

1. Two browsers, three in-app pages each, no reloads → Umami shows 2 visitors, 6 views on
   the right templates within 5 minutes (SC-002).
2. An hour on a live draft room → 1 page view (SC-004).
3. Start a mock → one `mock-started`; follow a live draft → one `live-draft-followed`.
4. Read the real collection host in Network; if it isn't `cloud.umami.is`, add it to
   `connect-src` in `web/serve.json` (R7).
5. Set `localStorage['umami.disabled']='1'` in your own browser, confirm no requests (R6).
