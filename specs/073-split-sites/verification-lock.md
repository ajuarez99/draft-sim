# Live verification: 073 league-site lock (locked league API + idle-expiring session)

**Date**: 2026-11-16 · **Run by**: Claude (built-in browser pane) · written as the run went, section by section.

Status words: **VERIFIED** = I saw it in this run. **ASSUMED** = not seen. **NOT RUN** = not attempted, with the reason.

## Setup (filled in as servers came up)

- **Backend A (locked)**: `gradlew.bat bootRun` as a background shell from this worktree's `backend/`, port 8080, pid 29252. Env: `LEAGUE_LOCK=true`, `ADMIN_TOKEN=<37-character local test value>`, `LEAGUE_LOCK_IDLE_SECONDS=90`, `CORS_ORIGINS=http://localhost:5173,http://localhost:5184,http://localhost:5185,http://localhost:5188`. VERIFIED this tree: the java command line's classpath starts `...\.claude\worktrees\073-split-sites\backend\build\classes\java\main`; startup log line `League API lock ENABLED (idle 90s); the public allowlist is [/api/health, /api/refresh/daily, /api/history/**, /api/sleeper/user/**, /api/admin/**, /api/ingest/**]`; `/api/health` → `weightsLoaded: true`. **The short-token WARN did not fire** (the log's only two WARN lines are the pre-existing `BK_CREDENTIAL_KEY` and `API token auth DISABLED` ones).
- **THE IDLE WINDOW IN THIS RUN IS 90 s, NOT THE DEFAULT 300 s.** It was shortened so the expiry could be waited out several times. Whether a real 300 s wait was done is stated in row 14.
- **Web** (all `npx vite --port N --strictPort` as background shells from this worktree's `web/`): 5173 combined (pid 33392); 5184 `VITE_SITE=timemachine`, `VITE_LEAGUES_ORIGIN=http://localhost:5185` (pid 27108); 5185 `VITE_SITE=leagues`, `VITE_TM_ORIGIN=http://localhost:5184` (pid 17940); 5188 `VITE_SITE=leagues`, `VITE_TM_ORIGIN=http://localhost:5184`, `VITE_API_BASE=http://localhost:8080` (pid 40936, the cross-origin one). VERIFIED: each node command line is `...\worktrees\073-split-sites\web\node_modules\...\vite.js --port N`; each serves `/src/site.ts`; `/src/siteEnv.ts` reports the `VITE_SITE` / origin values above and, on 5188, `VITE_API_BASE: "http://localhost:8080"`.
- `preview_start` was not used (known: from this worktree it reads the main checkout's `launch.json`). The `draft-sim-api-locked` launch entry was therefore NOT exercised; note it sets a 16-character token, which would fire the short-token WARN.
- **Database**: Postgres 14 on `localhost:5433`, left running. 5432 not touched.
- **League**: "(Foot) Ball Knowers" (NFL 2026, in season; NFL 2025 complete). No NBA league locally.
- **Browser pane**: displayed this time. Real keyboard input (typing, Enter) and screenshots worked; the pane's viewport is small (454x371 CSS px at its "desktop" size).
- Instrumentation used in the page (does not survive a reload, re-installed each time): `window.fetch` wrapped to record time, URL, whether `X-League-Session` was sent, status and whether the response exposed `X-League-Session`; a `MutationObserver` on `#root` recording the moment `.lock-card` appears or disappears; `EventSource` wrapped to record URLs.

## Side effects on shared state (kept current during the run)

- **One automatic league refresh fired** when the power page rendered after the first unlock on 5185 (10:48:18): the app's own `POST /api/leagues/1346366555759341568/refresh` (sent twice by the page, both 200). Backend log: "transactions: league 331 season 2026 -- 49 stored through week 10", "league history: 2 seasons, 12 roster-seasons, 12 roster-weeks, 0 future fixtures, 49 transactions ingested", "player-games: nfl 2026 -- 1 weeks fetched (of 10), 1273 games stored", "refresh: chain ... 1 season(s) refreshed, 1 skipped as complete". So the refresh is not limited to the league HOME page: any league page inside the shell fires it when the data is stale.
- **One Time Machine game was created and finished** (row 10): `historical_game` id 316, owner popsharky. Full list under "Rows created or changed" at the end.

## Results (appended as each was run)

### 4. Direct API with curl against 8080 (backend A, locked): PASS, VERIFIED

| Request | Answer |
|---|---|
| `GET /api/leagues/1346366555759341568`, `GET /api/drafts`, `GET /api/league-session`, no credentials | 401 `{"code":"league_session_required","message":"this league site is locked"}` |
| `GET /api/sleeper/users/1122386008709910528/leagues?sport=nfl&season=2026`, no credentials | 401, same body |
| `GET /api/sleeper/user/popsharky`, no credentials | 200 `{"found":true,...}` |
| `GET /api/history/seasons?sport=NBA`, `/api/history/games`, `/api/history/profile` with `X-Sleeper-User`, no session | 200 each |
| `POST /api/admin/session` with no token / a wrong token | 403 `admin_token_required` |
| `POST /api/admin/session` with the token | 200 `{"token":"<expiry>.<origin>.<sig>","expiresInSeconds":90}` |
| `GET /api/drafts` with `X-League-Session` | 200, response carries a renewed `X-League-Session` (expiry one second later, same origin part) |
| `GET /api/league-session` with the session | 204 |
| same token with one character appended | 401 |
| `GET /api/drafts` with `X-Admin-Token` only | 200 (both drafts listed) |
| Preflight `OPTIONS /api/drafts`, `Origin: http://localhost:5188`, request headers `x-league-session,x-sleeper-user` | 200, `Access-Control-Allow-Origin: http://localhost:5188`, `Access-Control-Allow-Headers: x-league-session, x-sleeper-user`, `Access-Control-Expose-Headers: X-League-Session` |
| 401 with `Origin: http://localhost:5188` | carries `Access-Control-Allow-Origin` (so the browser can read the refusal), `Content-Type: application/json;charset=ISO-8859-1` |

Stream ticket over HTTP (drafts A = 1346366555776126976, B = 1254190894563729408):

| Request | Answer |
|---|---|
| `POST /api/league-session/stream-ticket?draftId=A`, no session | 401 |
| same with the session | 200 `{"ticket":"t.<expiry>.<sig>","expiresInSeconds":60}` |
| `GET /api/drafts/A/live-stream?user=..&ticket=<A's>` | **409** `{"error":"The draft board for this sport hasn't been built yet."}`: passed the lock, refused by the handler because the local DB has no NFL board |
| `GET /api/drafts/B/live-stream?user=..&ticket=<A's>` | 401 |
| `GET /api/drafts/A/live-stream?user=..&session=<session>` | 401 |
| `GET /api/drafts/A/live-stream?user=..` (nothing) | 401 |
| ticket as `X-League-Session` on `/api/drafts`; `?ticket=` on `/api/drafts` and on `/api/drafts/A/board` | 401 each |
| `GET /api/drafts/A/live-stream` with the session as a header | 409 (passed the lock) |

The admin token, `ticket=` and the header name appear 0 times in the backend log after these calls.

### 1 + 2 (5185, pane's desktop size 454x371): PASS, VERIFIED

- `/` signed out, storage empty: body text is exactly "Ball Knowers / This site is private. / Admin token / Unlock". Title "Ball Knowers". 0 links, no top bar, no match for league/sleeper/mock/manager/draft in the DOM. Focus is on the password input. No `/api/` request in the network log. Screenshot looked at (not saved to disk).
- `/leagues/1346366555759341568/power?x=1#y` with `bk.user.v1` planted and the old flag `bk.leagues.unlocked.v1 = "1"` planted: same lock card only, URL untouched, no match for league/foot/popsharky/power in `#root`, no `/api/` request (network log and `performance` resource entries both empty), **the old flag was deleted on load and did not unlock**.
- Wrong token, typed with the real keyboard and submitted with Enter: `POST /api/admin/session` → 403; "That token wasn't accepted." in the `role="alert"` region; still locked; no storage written.
- Right token, typed and Enter: lock card gone 73 ms after the POST was sent; the power page rendered at `http://localhost:5185/leagues/1346366555759341568/power?x=1#y` (query and hash intact). Storage: `localStorage` = `bk.user.v1`, `bk.leagueSession.v1` (`{token, expiresAt, windowSeconds: 90}`, token shape `<expiry>.<origin>.<sig>`); `sessionStorage` empty; the admin token string is in no storage and not in the URL.

### 3, same-origin half (5185): PASS, VERIFIED

Every league request after unlock carried `X-League-Session` and every response carried a renewed one (`/power`, `/ballot`, `/history`, `/drafts`, `POST` and `GET .../refresh`: 13 of 13). `POST /api/admin/session` itself sent `X-Admin-Token` and no session.

### 5a. Idle lock, first measurement (5185, power page): PASS, VERIFIED

Unlock POST sent at t = 0 (the Enter key was the last input). No input afterwards; the page's own requests ran until t = 3.4 s (refresh poll and refetches). **Lock screen appeared at t = 90.40 s.** Text: "Ball Knowers / This site is private. / **Locked after 2 minutes without activity.** / Admin token / Unlock". `bk.leagueSession.v1` removed, `bk.user.v1` kept, URL unchanged, no league text left in `#root`.

- **V-01 (LOW, wording)**: with a 90 s window the line says "2 minutes" (`minutesOf` rounds 1.5 up). With the default 300 s it reads "5 minutes" (see row 14 for whether that was seen). Only matters if the window is set to something that is not a whole number of minutes.

### 1 on 5188 at 375x812 (cross-origin leagues site): PASS, VERIFIED

- Deep URL `/leagues/1346366555759341568/power?x=1#y`, signed out, storage empty: lock card only, title "Ball Knowers", 0 links, focus in the input, no request to 8080 at all. Card 335 wide at x=20, input 301x59 with a 20px font (no iOS zoom-on-focus), button 301x51, no sideways scroll. Screenshot `verification-shots/lock-screen-375.jpg` (the pane scales it down; `lock-screen-desktop-pane.jpg` is the 454px-wide pane view on 5185).
- Right token (typed, Enter), signed out: the league **sign-in landing** at the same URL ("Your fantasy league, all season long.", title "Ball Knowers: Fantasy Football & Basketball for Sleeper Leagues"). No sideways scroll.
- **7, sign-in gate after unlock**: typed `popsharky`, Enter → `GET /api/sleeper/user/popsharky` 200 → the power page at `...power?x=1#y`, query and hash intact. (The lookup request carried `X-League-Session`; the public route ignores it and returns no renewal header.)
- "Backend unreachable" message: see row 11c (shown when the backend was stopped between configurations).
- NOT RUN: a true 1366-wide desktop render. The pane's "desktop" is 454 px wide, so the two sizes seen are 454 and 375.

### 3, cross-origin half (5188 → 8080): PASS, VERIFIED

- Network log: `OPTIONS http://localhost:8080/api/admin/session` 200 then `POST` 200; `OPTIONS /api/drafts` 200 then `GET` 200. No console error.
- The page's own JavaScript can read the renewed header: inside the page, `response.headers.has('X-League-Session')` was true on every locked-route response (it is false for a header that is not CORS-exposed), and the stored `expiresAt` advanced (7.8 s after the sign-in requests; later by a full window on each renewal, see 5c).

### 5b. Idle lock with a background poll running (5188): PASS, VERIFIED

Unlocked, then one real key press (Shift) as the last input, then a 10 s poll calling the app's own `getDrafts()` (through the real `apiFetch`, imported from the running page) with no input at all. Every poll got 200 and a renewed header from the server. The stored expiry was 71.8 s away at the key press; the first poll moved it to exactly last-input + 90 s; polls 2 to 8 moved it by 0. **Lock screen 90.14 s after the last input.** The poll at 90.13 s went out with no session header and got 401. Session key removed, no league text left in `#root`, "Locked after 2 minutes without activity."

So a background poll does not keep the session alive, and it does stretch an un-renewed session from "original expiry" to "last input + window", as plan.md says.

### 5 after-lock unlock: PASS, VERIFIED

Unlocking again from the idle lock screen (typed, Enter): power page back at the same URL, and everything was fetched again: `/power`, `/ballot`, `/history` and the rail's `GET /api/drafts` (not served from the pre-lock cache). `sessionStorage` empty.

### 5c. Active use never locks (5188): PASS with one tooling caveat, VERIFIED

- **Tooling finding that shapes this row: the browser pane reports `document.visibilityState === "hidden"`** even while the tool says the pane is displayed; it flips to "visible" for a few milliseconds around a scroll action. `useIdleLock` deliberately never renews from a hidden tab. So in the raw pane, real key presses arrived (trusted `keydown`, seen by a capture listener) but did not renew, while scroll actions did. This is the pane, not the app; a normal foreground browser tab reports "visible". For the key-press parts below `document.visibilityState` was overridden in the page to return "visible" (an injected stub, said so in each line).
- **221 s of activity (2.46 windows), raw pane, no stub**: real wheel/scroll and key input at t = 11, 25, 53, 67, 99, 120, 152, 166, 198, 212 s. Never locked (`.lock-card` never appeared). Four renewals, each `GET /api/league-session` 204 cross-origin, at 53.8, 99.8, 152.8, 198.8 s, each on a scroll made past half the window. Stored time-to-expiry sampled every 5 s: 72 → 27, then 88 → 48, 89 → 39, 87 → 47, 88 ... (it kept moving). The key presses in that stretch all happened before half the window, where no renewal is due, so they neither prove nor disprove key renewal; see next line.
- **Key press renews at once (visibility stub on)**: Shift pressed with 40.9 s left (past half): `GET /api/league-session` sent in the same millisecond as the `keydown` (both logged at t = 420.792 s), expiry back to 88 s.
- **The reviewer's CR-01 scenario scaled to 90 s (visibility stub on)**: last input and renewal at t0; no input for 76.0 s; Shift at +76.0, +80.0, +84.0 s. A renewal was sent at +76.0 s (same millisecond as the first key), the stored expiry went from 14 s to 89 s, and the page was **still unlocked at +92 s** (old expiry was +90.0). One renewal only (the 15 s spacing held).
- **Tighter, by accident, with no stub**: a scroll that the tool delivered late landed 75.0 s after the last input and **2 s before expiry**; the renewal went out on that scroll and the page stayed unlocked.
- NOT RUN: `pointerdown` / `touchstart` as the renewing input (wheel, scroll and keydown were seen); real laptop sleep and wake.

### 6. Two tabs on 5185 (`/managers`): PASS, VERIFIED

- Tab B already showing the lock, token entered in tab A: **tab B unlocked by itself, no reload**, 11 ms after its `storage` event for `bk.leagueSession.v1`, and loaded `/api/managers` (13 managers listed).
- A third tab opened inside the window: unlocked on load without the token (stored session picked up, 88 s left).
- Sign out in tab A: tab A went to `http://localhost:5185/` showing the lock; **tabs B and C showed the lock screen within 8 ms of the `storage` events** (`bk.user.v1` and `bk.leagueSession.v1` removed), without waiting for a request and without the idle line. They did not make a further request.
- NOT RUN: the narrow race the review left open (tab B's in-flight response writing a session back after tab A locks). Not attempted; it needs a response held in flight.
- `/managers` does not fire the league refresh (no `/refresh` request on any of the three tabs).

### 7. Sign out on the league site: PASS, VERIFIED

Account menu → Sign out (real clicks): URL `http://localhost:5185/`, lock card, no idle line, title "Ball Knowers". `localStorage` afterwards held only `bk-sport-filter` (a display preference); `bk.leagueSession.v1` and `bk.user.v1` gone; `sessionStorage` empty. Sign-in gate after unlock: see row 1 on 5188 and row 9.

### 8. Live draft stream with a ticket: browser part PASS for the ticket handshake, stream itself NOT RUN (no data)

`/drafts/1346366555776126976/live` on 5185, unlocked in place (the app does open the live room for a completed draft):

- `POST /api/league-session/stream-ticket?draftId=1346366555776126976` → 200 at +99 ms, then `new EventSource("/api/drafts/1346366555776126976/live-stream?user=1122386008709910528&ticket=t.<expiry>.<sig>")` at +121 ms. **`ticket=` present, no `session=`.**
- The stream did not connect: the server answered **500** and the `EventSource` went to CLOSED. The hook retried at +6.1 s, and **fetched a new ticket first** (`POST .../stream-ticket` at +6092 ms, new expiry in the URL at +6100 ms). So "a ticket before every connection, including the hook's own reconnect" was seen.
- Why 500: the local database has no NFL draft board, so the handler throws "The draft board for this sport hasn't been built yet."; with `Accept: text/event-stream` the 409 JSON error cannot be written (`HttpMessageNotWritableException: No converter for LinkedHashMap with preset Content-Type 'text/event-stream'`) and it surfaces as 500. With curl and no Accept header the same call is 409 (row 4). Reproduced with the admin token and `Accept: text/event-stream`: 500. This is older than the lock and unrelated to it (observation O-1). The page says "NOT CONNECTED / The live stream isn't answering — the backend may not have this endpoint yet. Retrying every few seconds."
- HTTP-level ticket rules (A's ticket on A passes the lock, on B 401, `?session=` 401): row 4.
- NOT RUN: a stream that actually opens and delivers frames with a ticket; a ticket across a reconnect of a live draft; a stream outliving its session (CR-07). All need a draft with a board, which is not local.

### 9. Redirect routes stay outside the lock; carry arrives: PASS, VERIFIED

- 5185, storage empty (locked, signed out): `/history` → `http://localhost:5184/` (Time Machine front door); `/history/80?x=1#y` → `http://localhost:5184/game/80?x=1#y` (front door at that URL). No lock screen shown on the way.
- 5184 with two formulas saved by the app's own `saveFormula` ("Lock carry A", "Lock carry B é"): `5184/leagues/1346366555759341568/power?x=1#y` → `http://localhost:5185/leagues/1346366555759341568/power?x=1#y` showing **the lock card only**, no `bk-carry` left in the address bar, no `/api/` request, and **5185's `localStorage` already held both formulas** (names and expressions intact) while locked.
- Unlock (typed, Enter) → league sign-in landing at the same URL → `popsharky`, Enter → "WEEK 10 POWER RANKINGS" at the same URL, query and hash intact. Formulas still in 5185's storage.
- Note, not new with the lock: the carry is written to the league origin's storage before the lock is passed (`acceptCarry` runs first, as plan.md says). A crafted link can therefore plant saved formulas in a locked browser that has none. No data is exposed.
- NOT RUN: the formulas shown in the stat calculator's own list (needs a basketball league).

### 10. Time Machine site (5184) against the LOCKED backend: PASS, VERIFIED

Storage cleared on 5184, no session anywhere. Every request below was logged by the fetch wrapper with its header names; **none sent `X-League-Session`, none got one back, every status was 200 (201 for the create)**.

- Front door: Time Machine landing, no request.
- Sign in as `popsharky` (typed, Enter): `GET /api/sleeper/user/popsharky` 200; home lists the game of the day (2001-02), "Your games: Finished 1 ... 2002-03 ... Score 79", level 4, "6 of 21 earned". Requests: `/api/history/games`, `/profile`, `/daily`, `/seasons?sport=NBA`, `/seasons?sport=NFL`, all 200.
- Open game 81 ("View"): `/api/history/games/81` and `/award` 200; results page ("You lost in the semifinal · 10-9 · Draft score 79").
- "Draft a random year": `POST /api/history/games` **201** → `/game/316` (NBA 2001-02, pick 1.01).
- A pick ("Draft" on the first row): `POST /api/history/games/316/pick` 200; banner moved on ("Team 3 is on the clock").
- "Auto-finish the draft": `POST .../auto` 200 → season replay → "Skip to results": "You lost in round one · 11-8 · Draft score 79". `/award` and `/profile` 200.
- Whole-word league / leagues / mock / manager(s) in the results page text: 0.
- **No "leagues moved" notice**: after a fresh sign-in (storage = `bk.user.v1` only) and again with storage wiped and only `bk.user.v1` planted by hand: no `.tm-moved` element, no text matching moved / "looking for your leagues" / 5185, no link leaving the origin. Home then showed "Finished 2".
- NOT RUN: an NFL Time Machine game (no NFL seasons locally), phone width for this pass (covered in the earlier verification.md, and nothing in the Time Machine UI changed for the lock).

### 11a. Combined site (5173) against the LOCKED backend: what the user sees (documented consequence, VERIFIED)

Signed in as popsharky (storage planted), no lock screen (the combined build has none), no session.

- `/` (league picker): shell renders, "Welcome back, popsharky", "Start a mock draft", then under **Your leagues** a red error box reading "**this league site is locked**", and under From Sleeper "Couldn’t load your Sleeper leagues (this league site is locked). Try again". Sport pills show 0. `/api/drafts`, `/api/mocks`, `/api/sleeper/users/<id>/leagues` → 401. Screenshot looked at. Not blank, no endless spinner.
- `/leagues/1346366555759341568/power`: top bar, the heading "Power rankings" and the one line "this league site is locked". No league header or rail. Four 401s; no `POST .../refresh` was sent.
- `/managers`: page chrome and intro text, then "this league site is locked".
- `/history`: **Time Machine works**: home with game of the day and "Finished 2"; all `/api/history/*` 200.
- **O-2 (LOW, wording)**: on the combined site the visitor reads the backend's raw refusal message, "this league site is locked". It is a visible error, which is what the spec asks, but it reads oddly on a site that has no lock screen and it tells a league-mate that a lock exists. Only matters if the lock is enabled before the flip, which DEPLOY.md warns against.

### 1 (unreachable): PASS, VERIFIED

Backend A stopped (nothing listening on 8080), right token entered on 5185 `/managers`: `POST /api/admin/session` → 500 from the Vite proxy; "Couldn't reach the server. Try again."; still locked; nothing stored. (Seen through the dev proxy's 500, not through a dropped connection or a cross-origin network error.)

- **Tool note**: 3 (4 by the end of the run, see O-5) of the roughly 15 Enter key presses the tool sent in this run did not submit the form (value typed, no request); a second Enter always did. It happened on the Time Machine sign-in form as well as the lock form, so it is ASSUMED to be the browser tool dropping the key, not the app.

### 11b. Backend restarted WITHOUT `LEAGUE_LOCK` (backend B, pid 43692): combined site PASS; league site has a defect (V-02)

Backend B: same worktree, env `ADMIN_TOKEN` and `CORS_ORIGINS` only. Startup log has no "League API lock" line. curl: `/api/drafts` 200 with no credentials; a junk `X-League-Session` ignored; `GET /api/league-session` 204 to anyone; cross-origin responses carry `Access-Control-Expose-Headers: X-League-Session` (CR-08, accepted); `POST /api/admin/session` 200 with the token (`expiresInSeconds: 300`), 403 without.

- **5173 combined, lock off: PASS, VERIFIED, same as the earlier verification run.** `/`: "Welcome back, popsharky", (Foot) Ball Knowers card, four "From Sleeper / Set up" cards; `/api/drafts`, `/api/mocks`, `player-spotlight` 200. `/leagues/<id>/power`: league header, rail, "WEEK 10 POWER RANKINGS"; `/power`, `/ballot`, `/history`, `/drafts`, `POST /refresh` all 200 (the refresh POST did not re-ingest: no ingest line in backend B's log). Menu: Leagues, Mock drafts, Managers, Time Machine → `/history`, Sign out. "Mock drafts" → address `/`, dialog "PRACTICE ROUND / START A MOCK DRAFT / 1 · SPORT / 2 · USE SETTINGS FROM (Foot) Ball Knowers / Start NFL mock". Managers: 13 managers. Time Machine: home at `/history`. No lock screen, no 4xx/5xx on any `/api/` request, no `bk.leagueSession.v1` written, no session header needed.
- **5185 leagues, lock off: the web lock screen still shows and still unlocks**: lock card at `/managers`; token typed + Enter → `POST /api/admin/session` 200 → sign-in landing → popsharky → Managers (13). Stored session window 300 s.
- **V-02 (MEDIUM) — with the backend lock OFF, the league site locks an ACTIVE user exactly one window after unlock, and says it was for inactivity.** A lock-off backend never sends the renewed `X-League-Session` header (by design: "hands out none"), on ordinary routes or on `GET /api/league-session`. The web's idle timer still runs, so nothing can ever move the expiry. Measured on 5185 against backend B (`visibilityState` stubbed to "visible", real key presses): unlock at t = 0; keys at 171.6, 194, 214, 234, 254, 270, 280, 290 and **300.3 s**; the watcher sent `GET /api/league-session` at 171.6, 194.0, 211.0, 234.1, 254.1, 270.3, 290.3 s, each 204 with **no** renewal header; stored expiry never moved; **lock screen at t = 301.0 s**, 0.7 s after the last key press, reading "Locked after 5 minutes without activity."
  - When it bites: any time the league site is live while `LEAGUE_LOCK` is unset, i.e. between deploying the league site and DEPLOY.md step 7, and after the documented rollback ("unset `LEAGUE_LOCK`"). It contradicts FR-024 ("an active user MUST NOT be locked") for that configuration. Only Allan is affected (he re-types the token every 5 minutes).
  - Reproduce: backend with `ADMIN_TOKEN` set and no `LEAGUE_LOCK`; leagues build; unlock; keep clicking for 5 minutes.
  - Possible fixes (not applied): have `GET /api/league-session` (and/or the interceptor) issue a renewed token for a valid presented session even when the lock is off; or have the web treat "renewal answered 2xx with no header" as "backend lock is off" and extend the local expiry.
- This run doubles as **one real 300 s wall-clock measurement of the web timer** (301.0 s from unlock to lock screen), though against a lock-off backend and not idle. The idle, locked-backend 300 s check is row 14.

### 12. Delivered files: PASS, VERIFIED

Three builds into scratch folders (`web/dist` not touched), each exit 0 (`VITE_SITE=leagues npm run build`, which runs `tsc -b` first; `vite build` for `timemachine` and unset).

| | `leagues` | `timemachine` | unset (combined) |
|---|---|---|---|
| `<title>` | `Ball Knowers` | Ball Knowers Time Machine: Redraft a Real NBA or NFL Season | Ball Knowers: Fantasy Football &amp; Basketball for Sleeper Leagues |
| robots meta | `noindex, nofollow` | none | none |
| description / canonical / og / twitter / JSON-LD | 0 / none / 0 / 0 / 0 | 1 / `https://www.ballknowers.co/` / 10 / 5 / 1 | 1 / `https://www.ballknowers.co/` / 10 / 5 / 1 |
| `<div id="root">` | empty | pre-rendered landing | pre-rendered landing |
| `robots.txt` | `User-agent: *` / `Disallow: /` | `Allow: /` + `Sitemap: https://www.ballknowers.co/sitemap.xml` | same as timemachine |
| `sitemap.xml` | **not emitted** | `<loc>https://www.ballknowers.co/</loc>` | same |

`leagues` `index.html` is 1,458 bytes with 0 whole-word hits for league / leagues / sleeper / mock / manager / fantasy. Left over, as the review said: the HTML comment about "Title, description, canonical, og:* and twitter:*", and `og-image.png` / `og-image-tm.png` in the folder (unreferenced). NOT RUN: a byte comparison of the combined `dist` against one built from the clean commit.

### 13. Suites

- Backend, focused (`./gradlew test --tests '*LeagueLock*' --tests '*LeagueSession*'`, run with no backend server up, Postgres on 5433 up): **40 tests, 0 failed, 0 skipped, 0 errors**: `LeagueLockMappingsIT` 3, `LeagueLockMvcIT` 18, `LeagueLockOffMvcIT` 2, `LeagueSessionTest` 17 (read from the XML reports written at 17:14 UTC). BUILD SUCCESSFUL. The full backend suite was NOT RUN (as instructed).
- `npx tsc -b`: exit 0 (ran inside the `leagues` `npm run build`).
- `npx vitest run` (run while backend C, four Vite servers and the browser were up): **200 files passed (200), 3290 tests passed (3290)**, 0 failed, exit 0, 92.9 s. Matches the reported counts. The `PowerRankings.week` flake from the earlier verification did not recur.

### 14. One real default-window check (backend C, pid 28808, `LEAGUE_LOCK=true`, NO `LEAGUE_LOCK_IDLE_SECONDS`): PASS, VERIFIED

Startup log: `League API lock ENABLED (idle 300s)`. 5185 `/managers`, unlocked (Enter was the last input, 1 ms before the POST), stored window 300 s, then **nothing at all for five minutes** (no input events recorded by the capture listener, no request after the page's first four). **Lock screen at t = 300.46 s** from the unlock request: "Ball Knowers / This site is private. / **Locked after 5 minutes without activity.** / Admin token / Unlock". Session key removed, `bk.user.v1` kept, no manager text left in `#root`. (The vitest run above was going on the same machine during this wait.)

Then, from the locked page's console (item 4, browser half), same-origin on 5185 and cross-origin from 5188 to 8080: `/api/drafts`, `/api/leagues/<id>`, `/api/sleeper/users/<id>/leagues` → 401 `league_session_required` (also with a junk `X-League-Session`); `/api/sleeper/user/popsharky`, `/api/history/games` (with `X-Sleeper-User`), `/api/health` → 200.

## Summary table

| # | Item | Result | Key evidence |
|---|---|---|---|
| 1 | Lock screen at `/` and a deep URL, signed out and with `bk.user.v1`, 454 px and 375 px, 5185 and 5188 | PASS | card only, title "Ball Knowers", 0 links, focus in the field, no API call, Enter submits; wrong token and unreachable messages seen; right token → the page the URL named, query and hash intact |
| 1 | True desktop width (1366) | NOT RUN | the pane is 454 px wide at its desktop size |
| 2 | Storage after unlock | PASS | only `bk.leagueSession.v1` (+ `bk.user.v1`); admin token in no storage and not in the URL; old flag deleted and ignored |
| 3 | Header sent, renewed, readable cross-origin; preflight | PASS | 5188 → 8080: `OPTIONS` 200, header readable by page script, stored expiry advances |
| 4 | Direct API, curl and browser console | PASS | table in row 4, console list in row 14 |
| 5 | Idle lock (90 s window) | PASS | **90.40 s** (5185) and **90.14 s** with a 10 s background poll running (5188); line shown; DOM clean |
| 5 | Active use, 2x window | PASS (scroll input, raw pane); key input PASS only with `visibilityState` stubbed | 221 s, never locked, 4 renewals; pane reports "hidden", see 5c |
| 5 | Reviewer's scenario (idle ~75 s, input to ~88 s) | PASS | renewal in the same ms as the first input at +76.0 s; unlocked at +92 s; also a scroll 2 s before expiry |
| 5 | Unlock after idle lock, fresh data | PASS | `/power`, `/ballot`, `/history`, rail `GET /api/drafts` all refetched |
| 6 | Two tabs | PASS | other tab unlocks by itself (no reload) and locks by itself on sign-out, both within ~10 ms; new tab unlocked without token |
| 6 | Two-tab in-flight race (CR-11) | NOT RUN | needs a held response |
| 7 | Sign out locks; sign-in gate after unlock | PASS | session and user keys removed; popsharky → page |
| 8 | Stream ticket: handshake in the browser | PASS | ticket POST before each `EventSource`, `ticket=` and no `session=`, new ticket on the hook's reconnect |
| 8 | Stream actually connecting / delivering | NOT RUN | no NFL board locally: stream answers 409 (500 to an `EventSource`), see O-1 |
| 8 | Ticket rules at HTTP level | PASS | A on A passes the lock, A on B 401, `?session=` 401, ticket as header 401 |
| 9 | Redirects outside the lock; old league link → lock → sign-in → page; formula carry | PASS | storage-level for the carry |
| 10 | Time Machine site vs locked backend, end to end | PASS | every request 200/201, no session header sent, no notice |
| 11a | Combined site vs locked backend | documented, visible error | "this league site is locked" boxes; `/history` works (O-2 wording) |
| 11b | Lock off: combined site as before | PASS | no lock, no 4xx, no session |
| 11b | Lock off: league site unlocks via `POST /api/admin/session` | PASS for unlocking; **FAIL for staying unlocked (V-02)** | active user locked at 301.0 s |
| 12 | Delivered files | PASS | table in row 12 |
| 13 | Suites | PASS | vitest 200 / 3290; `tsc -b` 0; backend focused 40 / 0 failed / 0 skipped |
| 14 | Default 300 s idle window | PASS | **300.46 s** |

Not runnable here (NOT RUN): production CORS and the Railway variables; whether Railway's HTTP logs record query strings (matters for the 60 s stream ticket); a live draft in progress (ticket across a real reconnect, a stream outliving its session); an NBA league; real DNS; the `draft-sim-api-locked` launch entry through `preview_start`; laptop sleep/wake; password-manager behaviour on the token field; `pointerdown` / `touchstart` as the renewing input; the full backend suite (by instruction).

## Defects and surprises

- **V-02 (MEDIUM) - lock-off backend + league site: an active user is locked one window after unlock.** Details and reproduction in row 11b. Not fixed (no source edits in this run).
- **V-01 (LOW) - the idle line rounds the window to minutes**: a 90 s window reads "Locked after 2 minutes without activity." Row 5a.
- **O-1 (LOW, older than the lock) - the live stream answers 500 to an `EventSource` when the handler throws before streaming** (here: no draft board built). The 409 JSON body cannot be written under `Accept: text/event-stream`. The live room then retries every ~6 s forever, fetching a new ticket each time, and logs a stack trace per attempt in the backend log. Row 8.
- **O-2 (LOW, wording) - combined site vs a locked backend prints the raw server message** "this league site is locked". Row 11a.
- **O-3 - any league page fires the app's league refresh, not only the league home.** One real re-ingest happened in this run (see "Side effects"). Later `POST .../refresh` calls (several, on 5185, 5188 and 5173) answered 200 without re-ingesting (no further ingest lines in any backend log).
- **O-4 (tooling) - the browser pane reports `document.visibilityState === "hidden"`**, so `useIdleLock`'s "a hidden tab never renews" rule swallowed key presses in the raw pane. Scroll actions flip the pane visible for a few ms and did renew. Key-press renewal was shown with the property stubbed. A person in a normal foreground tab is ASSUMED to get "visible".
- **O-5 (tooling) - the tool dropped 4 Enter key presses** (no `keydown` reached the page, confirmed once with a capture listener); a second Enter always worked.
- **O-6 - the sign-in lookup sends `X-League-Session`** to the public `/api/sleeper/user/...` route when a session is held. Harmless (the route ignores it), noted because the header leaves on a request that does not need it.
- **O-7 - the carry writes into the league origin's storage before the lock is passed** (by design, row 9).
- The short-token WARN did not fire with the 37-character token. The `draft-sim-api-locked` launch entry's 16-character token would fire it.

## Assumed, not verified

- That a real foreground browser tab reports "visible" and so renews on key and pointer input without the stub (O-4). Wheel/scroll renewal was seen without the stub.
- That `pointerdown` and `touchstart` renew like `keydown`, `wheel` and `scroll` (same handler; not exercised).
- That the lock card looks right at a true desktop width (only 454 px and 375 px were rendered).
- That the dropped Enter presses are the tool's (O-5), not the form's.
- That the first refresh's re-ingest changed nothing that cannot be re-fetched (`adp_at_time` was not read before or after; the draft ingest is not part of the refresh chain per the earlier verification).
- Everything in the NOT RUN list above.

## Rows created or changed in the shared database (5433)

- `historical_game` id **316** (NBA 2001-02, owner sleeper id 1122386008709910528 = popsharky), created, drafted (one manual pick, then auto-finish) and finished; popsharky's `historical_profile` and badge rows updated by it (the home went from "Finished 1" to "Finished 2").
- The automatic league refresh at 10:48:18 re-ingested NFL 2026 for league 331: 49 transactions, 12 roster-seasons, 12 roster-weeks, 1,273 player-games for one week (log lines under "Side effects").
- Nothing else: no ingest endpoint was called, no mock created (`mock_draft_session` count 0), every `psql` call was a `select`. Flyway still at V49.

## Clean-up

- Stopped by PID with `taskkill`, no `gradlew --stop`: backend A 29252, backend B 43692, backend C 28808 (each the bootRun java process; its Gradle client then exited); Vite 33392 (5173), 27108 (5184), 17940 (5185), 40936 (5188). `netstat` shows no listener on 8080, 5173, 5184, 5185, 5188; no node or java process with this worktree in its command line remains. A Gradle daemon may still be idle; it was not stopped.
- Postgres on 5433 (pid 14476) left running; 5432 untouched.
- Browser storage cleared on 5173, 5184, 5185 and 5188; the two extra tabs closed.
- Files added to the worktree: this file and `verification-shots/lock-screen-375.jpg`, `verification-shots/lock-screen-desktop-pane.jpg`. No source edit, commit, stash or branch switch. Builds went to the scratchpad, not `web/dist`. The backend test run rewrote `backend/build/` (git-ignored).

## Verdict

The lock does what FR-021 to FR-027 say on a locked backend, in a real browser, same-origin and cross-origin: nothing but the card until the token is entered, no league request while locked, the admin token never stored, the session header sent, renewed and readable across origins, an idle lock at the window (90.40 s and 90.14 s on a 90 s window, 300.46 s on the default), no lock while active, the reviewer's late-input case fixed, tabs in step, sign-out locking, redirects and the carry outside the lock, and the Time Machine untouched by it. One real defect: with the backend lock **off**, the league site still runs its idle timer but can never renew, so it throws an active user out every 5 minutes (V-02); that is the state the site is in between deploying it and enabling `LEAGUE_LOCK`, and after a rollback. The stream ticket was seen up to the handshake only, because no local draft can open a stream.

