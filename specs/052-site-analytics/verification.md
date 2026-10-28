# Verification: Site analytics (spec 052)

Run 2026-10-28. The setup was this worktree's own backend on :8152 against the shared
local Postgres on :5433, and its vite dev server on :5179 with `VITE_ANALYTICS_DEBUG=1`.
In that mode every payload is logged instead of sent. The in-app browser was signed in as
`popsharky`. Payloads were captured by wrapping `console.debug` in the page and recording
each `[analytics]` payload.

**Verified** means run here, with the result as shown. **Not verified** means it was not
run, and the reason is given.

## Verified locally

| Check | Result |
|---|---|
| Final `npx tsc -b`, `vitest run`, `npm run build` | clean; 156 files / **2724 tests passed**; built |
| Mutation: remove the `lastLeagueKey = null` fix | 2 of 55 targeted tests fail; restored → 55/55 |
| Signed-out landing load | one view, `/(landing)`, title `Landing`. StrictMode's double effect was de-duped |
| Signed-out shared link `/leagues/<id>/power`, full load | one view, `/(landing)/leagues/:league/power`, `Landing (from League power rankings)` |
| Sign in through the real form | `sign-in` (url `/(landing)`), then **one** signed-in view `/` |
| Home → league home → /managers → back | views `/leagues/:league`, `/managers`, `/leagues/:league`. A `league-view` `{route, sport:"nba", league:"1339351318115946496"}` was sent **on both visits** (code-review #1 fixed, live) |
| Mock setup → Start the draft | `mock-started {from:"setup"}`, view `/mock/:session`, and a `league-view` for the mock's source league (`nfl`, through the hint path) |
| 25 picks in the mock, with the board re-simulating each time | event count stayed at 3. No views from re-renders (FR-001, SC-004's mechanism) |
| A failed mock start (HTTP 403, see note) | **no** `mock-started` (US2 scenario 2) |
| Payload contents, every captured event | url/title are templates only. No 6+-digit run, no username, no referrer on in-app views. `document.cookie` was `""` throughout (FR-005/006, SC-005 locally) |
| Real `cloud.umami.is/script.js` loaded with our exact attributes on localhost | the script loaded; `window.umami.track` is a function. No cookies and no umami storage keys. `track(object)` made **no** fetch or beacon, because `data-domains` blocks localhost |

Note: the first mock attempt got HTTP 403 because the local backend's `CORS_ORIGINS`
didn't include :5179. GETs passed and the POST was refused. That is local setup, not this
feature. It was fixed by restarting the backend with the origin allowed, then retested.

## Not verified (needs production + Allan's Umami website id)

- A real send reaching Umami, and Umami accepting a **relative** `url`. The tracker sends
  absolute URLs itself; our relative one is accepted only on memory, not proof
  (code-review note).
- The dashboard showing 2 visitors / 6 views for SC-002. That needs two browsers on
  different networks.
- The CSP in a real browser. It is report-only, so it can't break anything, but the
  `gateway.umami.is` origin is only confirmed from the script source.
- The Docker build arg on Railway.
- SC-003 (blocked tracker) in production. Locally the debug path never loads the script.
  The unit tests cover the `error` path.
- SC-006 (time to first real numbers), which waits on account creation.
- The live draft room over an hour (SC-004). Only the mechanism is shown, through the mock
  room's re-simulation. No live Sleeper draft was running.
- `league-added` and the ESPN path were not clicked live. Adding a league writes to the
  shared local DB. These are covered by unit tests only.
