# Bug-hunting code review: 073 league API lock + idle session

**Date**: 2026-11-16 · **Reviewer**: bug-hunting and bypass review pass (not a style review)
**Scope**: everything uncommitted on top of `2a7ebe8` on branch `073-split-sites`.
**Read**: plan.md (both 2026-11-16 amendments), spec.md FR-021..FR-025, contracts/site-routes.md "API access", build-notes.md (both 2026-11-16 sections), DEPLOY.md "Two sites", the four new backend classes, `WebConfig`, `AdminGateInterceptor`, `AdminAccess`, `SecretCompare`, `ApiTokenFilter`, `SleeperUserController`, `RefreshController`, `LeagueController.liveStream`, `ErrorHandler`, `api.ts` diff, `leaguesLock.ts`, `useIdleLock.ts`, `LeaguesLock.tsx`, `useLiveDraft.ts`, `App.tsx`, `AppShell.tsx`, the new tests.

**VERIFIED** = reproduced by running something. **READ** = from reading the code only.

## What I ran (nothing in the project was changed except this file)

- **Real Tomcat, real jar** (`bootJar` of the working tree, Java 21, shared DB on 5433), four processes, each stopped by its own PID with `taskkill`; ports 8091 to 8095 have no listener afterwards.
  - 8092: `LEAGUE_LOCK=true ADMIN_TOKEN=local-admin-test` (the attack target)
  - 8093: `LEAGUE_LOCK=true`, `ADMIN_TOKEN` blank
  - 8094: `LEAGUE_LOCK` unset, working tree
  - 8095: `LEAGUE_LOCK` unset, jar built from a clean `2a7ebe8` (the byte-compare baseline)
- **About 190 curl requests** with `--path-as-is`. Scripts and output are in the scratchpad: `atk1.sh`, `atk2.sh` / `atk2.out`, `cmp.sh` / `cmp.out`. I forged tokens with `openssl` from the known test admin token, so expired-but-well-signed and mangled tokens are real ones, not guesses.
- **Backend tests**: the four disputed classes on a temporary worktree at `2a7ebe8` and in the working tree (see "The 12 failing tests"). In the working tree I also ran `LeagueLockMvcIT` (11), `LeagueLockOffMvcIT` (2), `LeagueSessionTest` (8), `NoIngestHintsInMessagesTest` (2): all pass, 0 skipped. **I did not re-run the full backend suite**; the builder's 2952 / 12 failed / 48 skipped is theirs.
- **Web**: `npx vitest run` = **200 files, 3263 tests passed, 0 failed, 0 skipped** (matches the builder). `npx tsc -b` exit 0. Three `vite build` runs (`leagues`, `timemachine`, unset) to scratch folders, exit 0 each.
- **Three scratch Vitest reproductions** (R1 to R3) run against the real `leaguesLock.ts`, `useIdleLock.ts` and `api.ts` with fake timers and `fetch` mocked. File: scratchpad `vt/idle.test.ts`, config beside it. All three assert the faulty behaviour and pass.
- Temporary worktree `wt-2a7ebe8` removed; `git worktree list` no longer shows it. `git status` shows the same 46 entries as at the start, plus this file.

## Bypass hunt result

**No bypass found.** With the lock on and no valid session or admin token, nothing I tried returned league data.

| Attack family | Result on real Tomcat |
|---|---|
| Trailing slash, `//`, `/./`, `%2e`, `\` | 401 (or 404 for `//api/...`, which matches no handler) |
| `/api/history/../leagues...`, `%2e%2e`, `..;`, `..;x=y`, `.%2e`, `%252e%252e` | 404. The path is excluded from the lock but only reaches Spring's resource handler, which refuses it (`ResourceHandlerUtils` WARN). Same under `/api/sleeper/user/..` |
| `..%2f`, `%2e%2e%2f`, `..%5c`, `%00` | 400 from Tomcat before Spring |
| `/api/health/../leagues`, `/api/refresh/daily/../../leagues` | 401 |
| `/api/admin/../leagues`, `/api/ingest/../leagues/...` | 403 `admin_token_required` |
| Matrix params (`;x=y`, `;jsessionid=1`, on any segment), `%3b` | 401 |
| Case (`/API/...`, `/Api/Leagues`, `/api/LEAGUES`), encoded letters (`/%61pi/`, `/api/%6ceagues`) | 404 or 401 |
| `?session=<valid>` on `/api/drafts`, `/board`, `/seats`, `/leagues/..`, `/drafts/live-stream/board`, `/board;live-stream`, `/board/live-stream`, `/live-stream/../board`, `/live-stream/` (trailing slash), `POST` form body | 401 everywhere. Accepted only on the real `GET /api/drafts/{id}/live-stream` (also with `;x=y`, same handler) |
| Header and param together | Header wins: a bad header with a good param is 401. Two params: the first counts |
| Tokens: expired but well signed, `+`, negative, 13 and 30 digit expiry, far-future expiry with a real signature, non-numeric, hex, extra dots, no dot, no signature, truncated, padded, standard-base64 alphabet, non-base64, 5000 characters, lowercased, empty and blank header, the admin token as a session, a session as the admin token | all 401, identical body |
| A session token on `/api/admin/digest`, an ingest route, `POST /api/admin/session` | 403: a session cannot mint a session or reach an operator route |
| Methods: `HEAD` | 401 |
| `POST /api/sims`, `POST /api/sims/stream` (also with `?session=`), `POST /api/refresh/players`, `POST /api/setup/league/{id}` | 401 |
| `X-Sleeper-User` alone, `Origin` alone, `X-Original-URL` / `X-Rewrite-URL`, `X-HTTP-Method-Override`, `_method=GET` | no effect |
| Non-`/api` paths: `/`, `/error`, `/actuator*`, `/index.html`, `/v3/api-docs`, `/swagger-ui.html`, `/h2-console`, `/WEB-INF/web.xml`, `/application.yml`, `/db/migration/...` | 404 (`/error` answers 500 `{"status":999,"error":"None"}`, no data). No actuator, no static resources, every controller is under `/api` |
| **Every** mapping in `IngestController`, `AdminDigestController`, `HistoricalImportController`, `AdminRecapController` and `POST /api/admin/session`, with no token (25 routes, each with its own method) | 25 of 25 answer 403 `admin_token_required` |
| `OPTIONS` (the pass-through in both interceptors) | 200 with an empty body and an `Allow` header. Spring answers it itself; no handler runs |
| `ADMIN_TOKEN` blank, lock on | Everything refused: `POST /api/admin/session` 403 with no header, an empty header, a blank header and the old token; locked routes 401, including a token signed with an empty key. Time Machine and the username lookup still answer. The startup WARN "League API lock ENABLED but no ADMIN_TOKEN is set" is logged once |
| Token or admin token in the server log | 0 matches for the admin token, a session token, `session=` or the header name in the 8092 log after the whole attack run. No access log is configured and nothing in `src/main` logs a query string |

Public routes with the lock on, as intended: `/api/health` 200, `/api/history/profile` and `/api/history/daily` 200 with an `X-Sleeper-User`, `/api/history/seasons` 401 with none (the handler's own rule, Spring's body, not the lock's), `/api/sleeper/user/<unknown>` 200 `{"found":false}`, `POST /api/refresh/daily` 404 (no secret configured, the route's own answer).

Lock off against the clean commit: 50 requests compared with headers, 46 identical. The four differences are in CR-08.

## The 12 failing tests

**They fail identically on the clean commit. The change did not cause them.**

| | Tests run | Failed | Failing names |
|---|---|---|---|
| Clean `2a7ebe8` (temporary worktree) | 39 | 12 | the list below |
| Working tree (same four classes plus the lock tests) | 62 | 12 | the same 12, `diff` of the two name lists is empty |

`AccessControlMvcIT.draftRoutesAre404WithNoIdentity`; `AdminDigestControllerIT.anExplicitDateIsAccepted`; `LeagueRepositorySuccessorIT.seasons_from2024ListsNewestFirstWithGamesFlags` and `.successorOf_walksOneStepForward`; all eight of `TargetControllerIT`. Per class on both sides: 1 of 23, 1 of 4, 8 of 8, 2 of 4, 0 skipped. They are still 12 red tests on this machine's database and somebody should look at them, but not as part of this change.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| CR-01 | MEDIUM | web idle watcher | An active user is locked at the tick: activity in the last minute before expiry does not renew unless it happens to cause an API call |
| CR-02 | MEDIUM | token design, docs | The `?session=` token is the full bearer token, valid as a header on every route and renewable without end; docs say "short-lived" and "that one route only" |
| CR-03 | MEDIUM | crypto, docs | Session key is derived from `ADMIN_TOKEN` with no entropy rule; one captured session token is an offline guessing oracle for the admin token |
| CR-04 | LOW | web session store | A slow or out-of-order response replaces a newer token with an older one, and the client believes it lasts longer than the server will honour |
| CR-05 | LOW | web lock timing | League data stays on screen up to 60 s after expiry (longer after sleep), and a 401 in that gap does not lock. True worst case from last input to lock screen is about 7 minutes, not "window plus 60 s" |
| CR-06 | LOW (decision) | live draft | Watching a live draft without touching anything for 5 minutes replaces the draft room with the lock screen |
| CR-07 | LOW | SSE | A stream opened with a valid session keeps delivering for up to 4 hours after that session expires (not reproduced live) |
| CR-08 | LOW | lock off | Not byte-for-byte: one new CORS response header and two new routes answer with the lock off |
| CR-09 | LOW | backend | A wrong method on a locked path answers 405 before the lock; token accepts leading zeros |
| CR-10 | LOW | tests | No test of deny-by-default itself; the path-shape test cannot see Tomcat |
| CR-11 | LOW | web | Module caches survive `lock()`; a narrow two-tab race can undo a lock; admin token sits in `sessionStorage` while unlocked |
| CR-12 | LOW | docs | Stale notice comments, bundle contents, operator scripts, no `ADMIN_TOKEN` generation guidance |

No CRITICAL, no HIGH.

## Findings

### CR-01 (MEDIUM, VERIFIED) - an active user is locked out at the tick

**Where**: `web/src/useIdleLock.ts:31-42`, with `web/src/leaguesLock.ts:29` (`ACTIVE_GRACE_MS`) and `:171-176` (`renewSession`).

**What happens.** The watcher renews only on its 60 s tick, and on each tick it checks `sessionExpired()` **before** it looks at `activeSinceTick`. Input by itself (scroll, keys, clicks that fetch nothing) never renews; it only sets a flag for the next tick. So any activity that begins in the last tick interval before expiry is too late: the tick that would have renewed is the tick that finds the session expired. Because the interval starts when the session starts and the window is a multiple of the tick, that tick lands exactly on the expiry.

**Reproduced (scratch R1).** Unlock at 0:00. Read without input until 4:10. Scroll every 5 s from 4:10 to 4:55, press a key at 4:55. At the 5:00 tick the site locks, 5 seconds after the last input, and no renewal request was ever sent. The lock screen then says "Locked after 5 minutes without activity."

Realistic: open a long page (a draft board, league history, the superlatives list), read for four minutes, start scrolling or typing in a filter. Navigation that fetches does renew (through `apiFetch`), which is why ordinary clicking around hides this.

**Second mechanism, same cause (READ).** The tick interval and `ACTIVE_GRACE_MS` are both exactly 60 000 ms. An input just after a tick is renewed at the next tick, about 60 s later; the response arrives a few hundred milliseconds after that, `Date.now() - lastActivityAt` is then just over 60 000, and `renewSession` drops the token. Chance per tick is roughly latency / 60 s, so it is rare, but it silently skips a renewal.

**Also**: `LEAGUE_LOCK_IDLE_SECONDS` of 60 or less can never be kept alive by the watcher at all.

**Fix.** Renew from the activity handler, not only from the tick: in `onActivity`, if the session has less than half its window left (or the last renewal was more than about 30 s ago) and no renewal is in flight, call `renewLeagueSession()`. Keep the tick for the expiry check. Make the grace comfortably larger than the tick (for example grace 90 s with a 60 s tick), or mark the renewal the watcher itself sends as "user-driven" so the grace check does not apply to it.

**Regression test.** R1 with the assertion inverted: after the 5:00 tick the site is still unlocked and one renewal was sent. A second one for the grace boundary: input 59.9 s before a tick, response 300 ms late, token adopted.

### CR-02 (MEDIUM, VERIFIED for the mechanics; exposure is READ) - the URL token is the full credential and never has to end

**Where**: `backend/.../api/LeagueLockInterceptor.java:70-78`, `backend/.../config/LeagueSession.java:54-58`, `web/src/useLiveDraft.ts:109-113`; claims in plan.md ("short-lived and accepted on that one route only"), `useLiveDraft.ts` comment, contracts/site-routes.md.

**What happens.** The `session` query parameter is accepted on the live-stream route only, that part is true and tested. But the value in that URL is the ordinary session token. Anyone who reads it out of a request log can put it in `X-League-Session` and use every locked route (verified: a token works as a header on `/api/drafts` regardless of how it was obtained). Every accepted request then returns a freshly issued token, so the holder can keep it alive for as long as they call any route once per window. There is no absolute lifetime and no revocation short of rotating `ADMIN_TOKEN`. "Five minutes" bounds only an abandoned token.

**Exposure, honestly.** Someone has to see the URL: Railway's HTTP logs, a proxy in between, or a screen share with devtools open. `EventSource` URLs are not written to browser history. For this app that mostly means Allan's own Railway project, so this is not a stranger-off-the-internet hole. It is rated MEDIUM because the written description of what the lock protects is wrong in a way that matters when deciding whether a URL token is acceptable.

**Fix (pick one).**
1. A separate stream ticket: `GET /api/drafts/{id}/live-ticket` (locked) returns `<expiry>.<HMAC(key, "league-stream-v1:" + draftId + ":" + expiry)>`, 60 s life, accepted only as `?session=` on that draft's stream, never renewed and never valid as a header. The web asks for one before each `new EventSource`.
2. Or keep the design and correct the three documents: the URL token is the full session, usable on every route for its window and renewable by whoever holds it.

Either way add an absolute cap: carry the original issue time through renewals (`<expiry>.<firstIssued>.<sig>`) and refuse past, say, 12 hours.

**Regression test.** A stream ticket in `X-League-Session` on `/api/drafts` is 401; a session token whose first-issued time is older than the cap is 401 even when its expiry is in the future.

### CR-03 (MEDIUM, READ) - a captured session token lets someone guess the admin token offline

**Where**: `LeagueSession.java:81-85` (key = `HMAC(adminToken, "league-session-key-v1")`), `LeagueSessionController.java:31`, DEPLOY.md variables table.

**What happens.** The signing key is a deterministic function of `ADMIN_TOKEN`. One session token (see CR-02 for where one can leak) is enough to test admin-token guesses offline at two HMAC-SHA256 per guess, which is billions per second on a GPU. The admin token is the permanent secret: it gates ingest, overrides every membership check and mints sessions. Online, `POST /api/admin/session` and now every locked route (`admin.matches` in the interceptor) answer right-or-wrong with no rate limit, same as the existing admin routes.

With a 128-bit random token none of this matters. But nothing asks for one. DEPLOY.md tells you how to generate `BK_CREDENTIAL_KEY` (`openssl rand -base64 32`) and says nothing about `ADMIN_TOKEN`, and the lock screen is now a password box Allan types the token into from memory or a phone, which pushes toward something short. The compare itself is constant time (`MessageDigest.isEqual` in both `SecretCompare` and `LeagueSession.valid`), expiry parsing cannot overflow (digits only, at most 12), a blank or whitespace-only token fails closed (verified on 8093).

**Fix.** DEPLOY.md: "generate with `openssl rand -base64 32`, keep it in a password manager". At startup with the lock enabled, WARN (or refuse to start) when the token is shorter than about 24 characters. Optional and better: sign with a separate random `LEAGUE_SESSION_SECRET` so a leaked session token says nothing about the admin token.

### CR-04 (LOW, VERIFIED) - an older response overwrites a newer token, and the client's expiry is later than the server's

**Where**: `web/src/leaguesLock.ts:171-176`, `web/src/api.ts:258-260`.

**What happens.** `renewSession` adopts whatever token the latest response to arrive carries and sets `expiresAt = now + window` at arrival. The server stamped that token when the request was accepted, not when the response landed.

**Reproduced (scratch R2).** Request A is slow (40 s, think a simulation). Request B, sent at 20 s, returns at once with a token the server issued at 20 s. A then returns at 40 s carrying a token issued at 0 s. The client now holds the older token and believes it expires at 340 s; the server refuses it from 300 s. In that gap a request gets a 401 and the site locks with "after 5 minutes without activity".

Only bites after a long request followed by several quiet minutes. The client-relative expiry is otherwise a good choice (no clock-skew problem).

**Fix.** The token starts with its expiry in epoch seconds. Adopt a renewed token only if that number is greater than the held token's, and measure `expiresAt` from when the request was sent, not when it returned.

### CR-05 (LOW, VERIFIED) - league data outlives the session on screen; the stated worst case is low

**Where**: `web/src/useIdleLock.ts:31-47`, `web/src/leaguesLock.ts:211-223` (`useUnlocked`), `web/src/api.ts:241-246` and `:258-262`.

**What happens.** Nothing re-renders at the expiry instant. `useUnlocked` changes only on `notify()`, and the only things that call it after expiry are the 60 s tick and `visibilitychange`. Once expired, `getSessionToken()` returns null, so requests go out with no header, and the 401 handler runs only when a header was sent (`sentSession`), so those 401s do not lock either.

**Reproduced (scratch R3).** Session started, system clock jumped an hour with no timers run and the tab still "visible" (a laptop lid). The league page is still rendered; a request goes out without the header and its `league_session_required` 401 changes nothing; the lock screen appears only at the next tick, about 50 s later. `visibilitychange` does not fire for a lid close on a foreground tab in every browser, so the "also checked when the tab becomes visible" path does not cover it.

**True worst case from last input to lock screen**: 60 s (grace, during which a background poll's response is still adopted) + 300 s (window) + up to 60 s (tick) = about **7 minutes**. plan.md and build-notes.md say "about the window plus 60 s". Background tabs throttle timers, which can add more for a tab that is not visible.

**Fix.** Schedule a `setTimeout` for `expiresAt` whenever a session starts or is renewed and lock when it fires; also check on `focus` and `pageshow`. In `noteSessionOutcome`, lock on a `league_session_required` 401 whenever a session record is held, expired or not. Correct the stated worst case.

### CR-06 (LOW, decision for Allan, READ) - a quiet watcher loses the live draft room after five minutes

**Where**: `useIdleLock.ts:7` (activity events), `useLiveDraft.ts`.

Stream events are not activity (correctly: otherwise a draft would hold the session open all night). The consequence is that on draft night, watching the board fill without touching the mouse for five minutes swaps the draft room for the lock screen, and the token has to be typed again. Mouse movement without a click does not count either. This is what FR-024 says, so it is not a defect, but it is the moment the tool matters most. Options: a longer window (`LEAGUE_LOCK_IDLE_SECONDS`) on draft night, or count `pointermove` as activity, or exempt the live room.

### CR-07 (LOW, READ, not reproduced) - an open stream outlives its session

**Where**: `LeagueController.java:476-590`, `LIVE_SSE_TIMEOUT_MS` = 4 hours at `:43`.

The lock is checked once, when the stream opens. After that the emitter keeps sending state and heartbeat frames until the draft completes, the client disconnects or four hours pass, whatever happened to the session. Expected for a stateless token and already implied by plan.md, stated here plainly. I could not reproduce it live: the local database holds only completed drafts, whose stream sends one frame and closes.

Reconnect behaviour, from reading `useLiveDraft.ts:163-181`: the browser's own reconnect reuses the original URL and its old token. If that token has expired the server answers 401, `EventSource` goes to `CLOSED`, and the hook's own retry (5 s) or watchdog (60 s) calls `connect()`, which rebuilds the URL with the current token. So a long draft recovers after a 5 s gap with "disconnected" showing; it does not die silently. If the client-side session has expired too, the retry loops every 5 s with no token until the tick puts up the lock screen. Needs a real browser to confirm.

Optional hardening: complete the emitter when the opening token's expiry passes, so the client is forced to reconnect with a current one.

### CR-08 (LOW, VERIFIED) - lock off is not quite byte-for-byte

Compared 8094 (working tree, lock off) with 8095 (clean `2a7ebe8`), 50 requests, full response headers and bodies, timestamps normalised. 46 identical, including a junk `X-League-Session`, `?session=junk`, member, stranger and anonymous reads of 16 league routes, mocks, managers, targets, sims, Time Machine, admin and ingest refusals, 404s and a disallowed origin.

Caused by this change:
- Every cross-origin response and preflight now carries `Access-Control-Expose-Headers: X-League-Session`, and preflights accept that request header (`WebConfig.java:71-74`). Harmless to the combined site.
- `GET /api/league-session` answers 204 to anyone and `POST /api/admin/session` exists (both 404 on the baseline). Intended.

Not caused by this change, found while comparing:
- `/api/health` prints its keys in a different order per process (a `Map.of`).
- `/api/leagues/{id}/roster-management` printed `efficiency` as `0.9334101626662428` in one process and `0.933410162666243` in the other: a floating-point sum in hash order. Stable within a process. Worth a line somewhere; it makes byte-diffs of that route noisy.

FR-025 and plan.md say "exactly as before". Add "apart from one CORS response header and the two new routes".

### CR-09 (LOW, VERIFIED) - two small backend edges

- **405 before the lock.** A request whose path matches a locked route but whose method does not (`POST /api/drafts`, `GET /api/sims`, `GET /api/refresh/players`) answers 405 with Spring's error body and no session, because no handler was chosen so no interceptor ran. `OPTIONS` answers 200 with an `Allow` list. No data, and unknown paths answer 401, so this does not reveal which leagues exist. It does mean "identical refusal for everything" is not literally true.
- **Token malleability.** `00<expiry>.<sig>` is accepted (the signature is checked over the parsed number), trailing whitespace is trimmed by Tomcat, and with two `X-League-Session` headers the first wins. None of it extends a token. Fix if wanted: require `String.valueOf(expiry).equals(expiryPart)`.
- The refusal's `Content-Type` is `application/json;charset=ISO-8859-1` (written through `getWriter()` without a charset). The body is ASCII, and the admin gate has always done the same.

### CR-10 (LOW, READ) - what the tests would and would not catch

Would catch: dropping `/api/health`, `/api/history/**`, `/api/sleeper/user/**` or `/api/refresh/daily` from `PUBLIC_PATHS` (`theAllowlistedRoutesAnswerWithoutASession`); widening the lookup to `/api/sleeper/**` (the `assertFalse` plus the `users/123/leagues` case); accepting `?session=` elsewhere; handing out a session on a refusal.

Would not catch:
- **Deny-by-default itself.** `everyLeagueRouteFamilyRefusesWithoutASession` names one or two routes per family by hand. A new controller mapped outside `/api`, or a new entry added to `PUBLIC_PATHS` that shadows a league route, passes every test. Missing test: autowire `RequestMappingHandlerMapping`, walk `getHandlerMethods()`, and for every pattern assert that it starts with `/api/` and that a request with no credentials is 401 `league_session_required` unless the pattern is in an explicit expected-public list written in the test (so adding a public route means editing the test). For every pattern under the admin and operator prefixes assert 403.
- **Interceptor order.** The comment says the lock is registered after the admin gate "so an ingest route still refuses first". Because those prefixes are excluded from the lock, order changes nothing; registering it first would behave the same. No test is needed, but the comment claims more than is true.
- **Tomcat.** `noPathShapeSlipsAMissingSessionPastTheLock` runs under MockMvc, which does none of Tomcat's normalisation, and accepts 400, 401 or 404. It cannot fail for the shapes that matter most (`..`, `%2F`, `;`). Those were run by hand here and are clean; nothing keeps them clean. A `@SpringBootTest(webEnvironment = RANDOM_PORT)` test with a raw `HttpClient` for a dozen shapes would.
- **Web**: no test for an active user near expiry (CR-01), response ordering (CR-04), the `storage` event between tabs, or a 401 arriving after client-side expiry (CR-05).

### CR-11 (LOW, READ) - what survives a lock in the browser

- **Caches.** `railLeague.ts:81` (`cached`) and `searchIndex.ts:190` (`cachedIndex`) are module-level and are not cleared by `lock()`. After an idle lock the user is still signed in, the league list and jump index are still in memory, and after unlocking they are reused without a refetch. Only the admin-token holder can unlock, so nobody else sees them; the plan already says rendered data is not wiped. Cheap to tidy: in `App.tsx`, when `unlocked` goes false, call `invalidateRailLeagues()` and `invalidateSearchIndex()`.
- **Two-tab race.** `lock()` in tab A removes the stored session. If tab B has a request in flight and its response lands before B has processed the `storage` event, and B saw input in the last 60 s, B's `renewSession` writes the session back, A receives that as a `storage` event and unlocks again. Narrow. Fix: have `renewSession` re-read storage and do nothing if the key is gone.
- **Sign-out** clears the user, the session and the stored admin token, then navigates to `/`. Fine. In another tab the lock screen appears through the `storage` event, without the idle line.
- **The admin token is in `sessionStorage`** (`bk.adminToken`) for as long as the tab is unlocked, so that `/admin/digest` does not ask again. Any script running on the league origin can read the permanent operator secret, not just the five-minute session. Existing pattern for the digest page, now extended to every unlocked tab. Worth a decision: drop it and let the digest page ask.
- **`apiFetch` with no session held** (combined and Time Machine builds): no header is added, `renewSession` returns at its first line, and the 401 branch is skipped before any body is read. No clone and no extra await beyond one microtask. With a session held, the clone happens only for a 401, so large and streamed bodies are never duplicated. No existing caller reads a 401 body in a way this changes (the test that the caller's `ApiError` still carries the server's message passes).

### CR-12 (LOW, mixed) - documents and delivered files

- `VITE_SITE=leagues` build (VERIFIED, read from `dist-leagues/index.html`): `<title>Ball Knowers</title>`, `noindex, nofollow`, theme colour, `boot.js`, an empty `<div id="root">`, no description, canonical, og, twitter or JSON-LD. `robots.txt` is `User-agent: *` / `Disallow: /`. No `sitemap.xml`. Left over: the HTML comment about "Title, description, canonical, og:* and twitter:*", and `og-image.png` / `og-image-tm.png` (the builder noted the images).
- The league site's JavaScript bundle is public and contains the landing copy and all league UI code. No data, but "delivers no landing text" is true of the HTML only.
- Combined build: `landingHtml.test.tsx` and its parity snapshot are unmodified in `git status` and pass; the built `robots.txt` and `sitemap.xml` name `https://www.ballknowers.co/` as before. The `leagues` branches in `landingHtml.ts`, `siteFiles.ts` and `vite.config.ts` are all behind `cfg.site === 'leagues'`. I did not byte-compare a combined `dist` against a build of the clean commit.
- Time Machine: no `LeaguesMovedNotice`, `tmSignedInHere` or `.tm-moved` in source or in the built bundle. Two stale comments still name the notice: `web/src/styles.css:7461` and `web/src/leagueWords.ts:2`. The lock screen's code ships inside the Time Machine and combined bundles (one module graph); it is never rendered there.
- Time Machine API use (READ): `TimeMachineShell` imports nothing from `api`; `HistoricalDraft`, `HistoricalRoom` and `HistoricalAwardPanel` import only `/api/history/**` functions; `SignInForm` only `getSleeperUser`. All allowlisted. Matches the builder's list.
- DEPLOY.md: variable names and defaults match the code (`LEAGUE_LOCK` default false, `LEAGUE_LOCK_IDLE_SECONDS` default 300). The rollout order is right and the warning about enabling before the flip is accurate. Missing: how to generate `ADMIN_TOKEN` (CR-03); that the backend must be deployed with this change before the league site's lock screen can work (an older backend answers `POST /api/admin/session` with 404, which the screen reports as "Couldn't reach the server"); that after step 7 any operator script reading league routes needs `X-Admin-Token` (`scripts/check-weeks-vs-sleeper.py` and the capture scripts read `/api/leagues/...`); that anyone with a combined-site tab still open gets errors, not a lock screen, once the lock is on.
- The daily refresh workflow calls only `/api/refresh/daily` (allowlisted).
- `.claude/launch.json` parses; `draft-sim-api-locked` uses port 8080 like `draft-sim-api`, so only one of them can run.
- A 401 lock always shows "Locked after N minutes without activity", including when the real cause was a rotated `ADMIN_TOKEN` or CR-04 (the builder listed this as departure 6).

## Still needs a real browser

- The whole flow on `leagues` against a locked backend on another origin: unlock, browse, the renewed header actually readable cross-origin (CORS expose is verified with curl only), five real idle minutes, the idle line.
- CR-01 by hand: read for four minutes, then scroll.
- Two tabs: unlock in one, the other follows; sign out in one, the other locks.
- Live draft with a real `EventSource` and `?session=`, across a token expiry and a forced reconnect. Needs a draft that is not complete, which the local database does not have.
- Laptop sleep and wake with the league site in front (CR-05).
- The Time Machine site end to end with `LEAGUE_LOCK=true` on its backend.
- Whether Railway's HTTP logs record query strings (decides how much CR-02 matters).
- Password manager behaviour on the "Admin token" field, and the lock card at phone width.

## Not checked

- The full backend suite (only the classes named above).
- The stream surviving its session (CR-07), for lack of a live draft locally.
- A byte comparison of a combined `dist` against one built from the clean commit.
- NBA-specific paths: the lock is sport-agnostic and I tested with the NFL league only.
