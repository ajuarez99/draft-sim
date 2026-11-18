# Live verification: 076 Usage analytics we own

**Date**: 2026-11-17 (14:45 to 15:10 US Central; the ET day was 2026-11-17 throughout) ·
**Run by**: Claude (built-in browser pane, plus `curl` / a node sender for the abuse cases) ·
**API**: `.\gradlew.bat bootRun --args="--server.port=8086"` started from this worktree's `backend/` in a
background shell (not `preview_start`: the known worktree trap, and the task allowed the fallback). Confirmed
to be this tree: the java process command line's classpath is
`...\worktrees\076-usage-analytics\backend\build\classes\java\main`, `POST /api/usage` answers 204, and
`GET /api/admin/usage` exists. Two runs: **A** with `APP_OWNER_SLEEPER_USER_ID=verify076-owner`, **B** with it
blank. Both with a generated `ADMIN_TOKEN` and `CORS_ORIGINS=http://localhost:5186,http://localhost:5187`.
`/api/health`: `weightsLoaded: true` on both runs ·
**Web**: `npm run dev` from this worktree's `web/` (node command line shows the worktree's `vite.js`), all with
`VITE_USAGE_COLLECT=1`: Redraft build (`VITE_SITE=timemachine`) on 5186; combined build (unset) on 5187, later
restarted as the league build (`VITE_SITE=leagues`) on 5187 ·
**Capture**: after the first signed-out view, both vite servers proxied `/api` through a scratch logging proxy
(8096 → 8086) that wrote the full request headers and body of every `/api/usage` request to a file. That file
is the header evidence for SC-004; the pane's network panel shows status only ·
**Database**: shared local Postgres `localhost:5433` (`draftsim`), Flyway at V51 before the run. `usage_daily`
was **empty** at the start, `historical_game` held 3 rows (max id 316, all NBA, all `COMPLETE`, all
`started_as_guest = false`); `started_as_guest is distinct from (sleeper_user_id like 'guest:%')` → 0 rows ·
**Leagues used**: "(Foot) Ball Knowers" (NFL 2026, in season) and its 2025 season (NFL, complete). **No NBA
league is loaded locally and no NFL Redraft season is loaded** (47 NBA seasons, 1970 to 2016), so league views
were NFL only and Redraft games were NBA only.

Automated suites at verification time: web `npx tsc -b` clean · `npx vitest run` 218 files, 3,607 passed ·
`npm run build` (combined) built · backend `./gradlew test`: **3,070 tests, 48 skipped, 12 failed** (see
"Backend suite" below; the feature's own classes all pass: UsageCollectMvcIT 10, AdminUsageMvcIT 7,
StartedAsGuestIT 3, LeagueLockMappingsIT 4, plus UsageFunnelIT, UsageLimiterTest, UsageServiceTest,
UsageVocabularyTest with 0 failures).

## Results

| # | Scenario | Site / sport | Result | Evidence |
|---|---|---|---|---|
| 1a | SC-001 Redraft views by in-app navigation | Redraft build | PASS | Loaded `/`, clicked to `/daily`, `/nba`, back to `/daily`, then the `/nba/2001-02` link (the dev `/nba` index lists no seasons, so the season page was reached from `/daily`: five views, not four). Rows after: `timemachine view` `/` 1, `/daily` 2, `/nba` 1, `/nba/:season` 1. Five sends captured, five counted. |
| 1b | SC-001 league-side views | Combined build, NFL | PASS | Signed in (local id set in `localStorage`), loaded `/`, clicked `/managers`, then in-app to `/leagues/…/rivalries`, `/leagues/…/history` (2026), `/leagues/…/history` (2025), `/admin/digest`, `/admin/usage`. Rows: `combined view` `/` 1, `/managers` 1, `/leagues/:league/rivalries` 1, `/leagues/:league/history` 2, `/admin/digest` 1, `/admin/usage` 1 (the first `/admin/usage` view was dropped by the ceiling, see 5c). `league-view` rows: rivalries 1, history 2, league `1346366555759341568`, sport `nfl` filled by the server. The league pages were entered with `history.pushState` + `popstate` (React Router's own in-app path), not a rail click. |
| 1c | Signed-out landing stored, not rejected | Combined and league builds | PASS | Combined, signed out, `/` → row `combined view /(landing)` 1, no `rejected` row. League build after unlock, signed out → `leagues view /(landing)` 1. By curl, `leagues` + `/(landing)/leagues/:league/power` → counted (1). |
| 1d | A game room left open counts once | Redraft, NBA | PASS | Game 605 open from 14:48:26 to 14:50:42 (2 min 16 s), no reload: `/game/:game` stayed at 1, captured sends stayed at 7. |
| 2 | SC-002 / SC-003 as amended: guest funnel | Redraft, NBA | PASS, with one stubbed step | As a guest: played today's game of the day (game 605) and finished it with **auto-finish**; started a chosen-season game (606, 2001-02) and pressed Abandon on the home page. `historical_game` difference: +2 rows, 605 `COMPLETE` `daily_date 2026-11-17` and 606 `ABANDONED` `chosen_season`, **both `started_as_guest = true`**. Share: the pane denies clipboard writes (`permissions.query('clipboard-write')` = `denied`), so the first press fell to the manual-copy box and **sent nothing** (rows and capture unchanged: the "not on the manual fallback" rule holds). With `navigator.clipboard.writeText` stubbed to resolve, one press showed "Copied. Paste it anywhere." and stored `event result-shared daily=yes;sport=nba;who=guest` = 1. Reloaded the results page 5 times: `/game/:game` views +5, the game is still one finished game, shares still 1. Report for Nov 17 with the owner left out (run A): started 2, finished 1, gave up 1, left open 0, guest 2 (1 finished), game of the day 1 started / 1 finished. The page was read after the owner games below existed, not at the guest-only moment; the guest numbers are the same rows. |
| 3 | SC-004 nothing identifying is sent or stored | all three builds; guest, signed-in, unlocked | PASS | 42 captured `/api/usage` requests (27 from 5186, 15 from 5187). Header names, every request: `host, connection, content-length, sec-ch-ua-platform, user-agent, sec-ch-ua, content-type, sec-ch-ua-mobile, accept, origin, sec-fetch-site, sec-fetch-mode, sec-fetch-dest, accept-encoding, accept-language`. **No `X-Sleeper-User`, `X-Guest-Id`, `X-League-Session`, `X-Admin-Token`, `Authorization`, `Cookie` or `Referer`** on any, including sends made while a guest id, a signed-in user, a league session and the admin token were all in the browser's storage. Body keys across all 42: `kind`, `site`, `route` (39), `name`/`props` (3), `league` (3). 19 distinct bodies, all vocabulary; a regex scan for the guest id shape, the two user ids used, game ids and the token found 0 matches. `usage_daily` columns: day, site, kind, route, name, props, league_id, sport, count; nothing else stored. |
| 4 | SC-005 collection address failing | Redraft, NBA, signed in | PASS, with a note | The proxy destroyed every `/api/usage` socket (vite then answers the browser 500). Opened `/nba/1995-96`, started game 608, drafted one player by hand, auto-finished, opened results, pressed Share (stubbed clipboard). Page behaved the same: results rendered, "Copied." shown, no error banner. Three sends failed and three were lost (season view, game view, the share). Console: three browser-written `Failed to load resource: 500` lines, **no exception from analytics code**. Not run with a refused connection or a hung host. |
| 5a | SC-006 1,000 junk sends | curl-equivalent (node), each from its own `X-Real-IP` | PASS | 200 unknown routes, 200 unknown events, 150 bad prop values, 100 league-views of unknown leagues, 150 unknown sites, 100 oversized (1.5 KB), 100 non-JSON: 1,000 × `204`, empty body, 170 ms. `usage_daily` before and after: 15 rows, total 34, **0 new rows**, no `rejected` row yet (held in memory). 65 s later one valid send: `rejected` rows appeared, `timemachine` 350 and `combined` 300 = 650, exactly the known-site junk; the 350 unknown-site / oversized / non-JSON sends are counted nowhere. |
| 5b | Rejected flush on report read | | PASS | 10 more junk sends (`leagues`), no row; `GET /api/admin/usage` → `leagues rejected` written at once (14 = 10 + 4 from the odd-shape sends below), report `rejected: 665`. |
| 5c | SC-006 burst from one address | | PASS | 200 valid `timemachine /nfl` views in 53 ms from one address: **60 counted**. Log (run A): `usage sends not counted in the last minute: limited=140, overCeiling=0, crawler=1`. |
| 5d | Site-wide ceiling | | PASS | 700 valid sends from 700 addresses in 125 ms: **600 counted** (`/nfl/:season`). Repeated on run B: 600 again, log `limited=0, overCeiling=101, crawler=0` (100 + one CORS check sent in the same minute). A real page view sent 4 s after the first 700 was also dropped, silently, as the contract says. |
| 5e | Odd shapes | | PASS | Not counted: a valid view with extra `userId`/`gameId` fields; `kind` disagreeing with the fields; a Googlebot user agent; a combined-only route on `leagues`; `/(landing)` on `timemachine`; an unknown prop key on `sign-in`. Counted: a `text/plain` body; a send carrying `X-Sleeper-User` and `X-Guest-Id` (counted once, nothing about them stored). |
| 5f | SC-007 rows bounded | | PASS | After everything (2,622 scripted sends that reached the handler, 43 browser sends): **28 rows**, total count 1,969. Rows equal distinct combinations used. |
| 6a | SC-008 owner gate | | PASS | `GET /api/admin/usage` with no token → `403 {"code":"admin_token_required",...}`; with a wrong token → the same body. Bad date, `from > to`, 366+ days → `400 bad_range` with three distinct messages. |
| 6b | CORS | | PASS | `OPTIONS /api/usage` with `Origin: http://localhost:5186`, `Access-Control-Request-Method: POST`, `-Headers: content-type` → 200, `Access-Control-Allow-Origin: http://localhost:5186`, `Allow-Headers: content-type`. Actual POST with that Origin → 204 with the allow-origin header. A stranger origin → 403 on both. (The browser sends themselves were same-origin through the vite proxy; a real cross-origin browser send is owed after deploy.) |
| 7 | Opt-out | Redraft and combined | PASS | `localStorage['umami.disabled']='1'`, two in-app navigations: captured sends stayed at 26. Removed, next navigation: 27 (`/nba`). On the combined build with the switch set, a full load of `/admin/usage` made no `/api/usage` request. |
| 8a | Owner page, empty store | Combined | PASS (text read; no screenshot, the pane was not drawing then) | "Page counting has not started: nothing has been recorded yet." and, under Pages and actions, "Nothing has been counted yet. Page views, actions and league views start to appear after the site that sends them is deployed and someone opens a page on a real address." No table of zeros. The funnel was already populated from saved games (3 / 3 / 0). |
| 8b | Owner page populated, numbers against psql | Combined, run A | PASS | Every number matched the tables: per-day views 7 / 2 / 683 (combined / league / Redraft), each per-page row, key actions (3 rows), league views "(Foot) Ball Knowers · NFL · 3", "Sends turned away in this range: 665". Days before Nov 17 read "not counted", never 0. Three "counting began on Nov 17, 2026" lines, one per site. Caveats present: US Eastern days, floor, open to scripts, views are not people, opt-out is per browser and never applies to the funnel. Nothing negative on the page. Screenshots taken at desktop and phone width. |
| 8c | Owner exclusion, configured | Run A, NBA | PASS | Signed in as the configured id on the Redraft build. The home page **claimed the two guest games** (605, 606 now owned by that id, `started_as_guest` still `true`). Then three signed-in games: 607 finished, 608 finished, 609 left open. API, Nov 17: started 5, finished 3, abandoned 1, drafting 1, guest 2/1, owner 3 / 2 / 0 / 1. Page (30 days, includes 3 older games): **5 started, 4 finished (80% of started), 1 NOT FINISHED**, gave up 1, left open 0, guest 2 (1 finished), signed in 3, game of the day 1 / 1; "Your signed-in games are left out of every number in this section, the per-day table included: 3 games started, 2 finished, 0 gave up, 1 left open."; Tue Nov 17 row 2 / 1 / 1. The claimed guest games stayed guest games and were not subtracted twice (CR-01). |
| 8d | Owner exclusion, not configured | Run B | PASS | Same data: **8 started, 6 finished (75%), 2 NOT FINISHED**, gave up 1, left open 1, signed in 6; "The owner id is not set on the server, so your own games are counted with everyone's."; Nov 17 row 5 / 3 / 2. |
| 8e | Ranges | | PASS | 7 days (Nov 11 to Nov 17), 30 days (Oct 19 to Nov 17), 90 days (Aug 20 to Nov 17): label, pressed state and numbers follow. See V-03 for the 90-day length. |
| 8f | Phone width | | **FAIL** | V-02: the page scrolls sideways at 375 px. |
| 8g | Dark UI, same as `/admin/digest` | | PASS | Same shell, panels and token form; body `oklch(0.15 0.03 280)`, panel `oklch(0.2 0.036 280)`. |
| 9 | INFO line after the burst | | PASS | Two lines, counts only, quoted in 5c and 5d. Neither log file contains an address or a user agent. |
| 10 | `/admin/digest` with the shared token helper | Combined | PASS | Token entered once on `/admin/usage`; `/admin/digest` in the same tab loaded without a prompt (`GET /api/admin/digest` 200), and `/admin/usage` on the league build did too. |
| 11 | Health | | PASS | `weightsLoaded: true`. |
| 12 | Locked league site sends nothing | League build | PASS | Lock screen up ("This site is private."): no `/api/usage` request (capture stayed at 38). After unlocking with the token: the landing view was sent, with no `X-League-Session` on it. |

Not run:

- **A real clipboard or share-sheet share.** The pane denies clipboard writes; the counted shares used a
  stubbed `writeText`. The touch `navigator.share` path and a dismissed sheet were not exercised.
- **US3 with two leagues, and any NBA league view.** One league lineage is loaded locally, NFL.
- **An NFL Redraft game.** No NFL season is loaded, so `sport=nfl` on a share and the Football funnel panel
  with data were not seen (it showed "No football games were started in this range.").
- **"No page views ... were counted from X to Y"** (counted before, nothing in range): the page offers only
  7 / 30 / 90 days and counting began today, so the UI cannot reach it. By curl a 2020 range returns empty
  lists with `countingSince` still filled.
- **Null / unknown labels**: a league row deleted since (falls back to the id), a blank sport ("unknown"), a
  stored route with no label left. None could be produced without deleting shared data.
- **The 10,000-address cap**, a database failure during a send (the WARN line), and the vocabulary guard
  (quickstart step 9; it needs a source edit).
- **SC-009** was not timed. The per-day table answers "started / finished yesterday" in one row.
- **Long manager names / a 12-team league**: the page shows neither.
- Screenshot of the empty-store page (text only).

## Defects found during verification

- **V-01 (LOW) - the shares row includes the owner's own shares, under a sentence that says every number
  leaves his games out.** Run A, owner configured: headline 5 started (owner's 3 left out), "Results shared
  (browser-reported) 2", of which one is `who=member` sent from the owner's own finished game 607. A share
  carries no identity, so it cannot be subtracted; the sentence "Your signed-in games are left out of every
  number in this section" is the thing that is wrong. `web/src/pages/AdminUsage.tsx`, the paragraph after the
  group table. Wording fix: say shares are the exception (they stop only with the browser opt-out). Not applied.
- **V-02 (MEDIUM) - `/admin/usage` scrolls sideways on a phone when a page table holds a long address
  template.** At 375 px the panels are 343 px wide. "League site: page views per page" was 396 px
  (`/(landing)/leagues/:league/power` does not wrap), "Single combined site: page views per page" 352 px; the
  document became 412 px wide and the Views column sat outside its card. Landing-form templates are what the
  locked league site records for a signed-out visitor, so this is the normal case, not an edge. Likely one
  line: let the template cell break (`overflow-wrap: anywhere` on `.us-table .code`). Not applied.
- **V-03 (LOW) - the per-day lists print every day that was never counted.** 90 days: 270 day rows, 267 of
  them "not counted". 30 days: 90 rows, 87 "not counted". Correct and honest, but the page-per-route tables
  and key actions end up far down the page for months after launch. One line such as "Aug 20 to Nov 16: not
  counted" would say the same. Not applied.

Things a reader could misread, not defects:

- **League views are per league lineage.** A view of the 2025 season's history page was counted under the
  2026 league id (the shell sends the lineage's current league, as spec 052 built it). The table's "League"
  column does not say that.
- **Auto-finished drafts count as finished**; the page says so.
- The pane's network panel labels every send `204 No Content [FAILED: net::ERR_ABORTED]`. All of them reached
  the proxy and were stored, so this reads as the panel's treatment of a keepalive fetch with an empty reply.
  That explanation is assumed.

## Backend suite

`./gradlew test` after the servers were stopped: 3,070 tests, 48 skipped, **12 failed**, in four classes this
branch does not touch: `TargetControllerIT` (8, "The draft board for this sport hasn't been built yet"),
`LeagueRepositorySuccessorIT` (2, expected seasons `[2026, 2025, 2024]`, got `[]`), `AdminDigestControllerIT`
(1, season `null`), `AccessControlMvcIT` (1, 409 for 200). They read as depending on what is loaded in the
shared 5433 database (two NFL league rows, no board built). **Not confirmed**: the same suite was not run on
main, and code-review.md ran 58 targeted tests, not the full suite.

## Side effects on the shared database

- `historical_game`: **5 rows left**, ids 605 to 609, all NBA, all owned by the made-up id `verify076-owner`
  (605 complete, game of the day, started as guest; 606 abandoned, started as guest; 607 and 608 complete;
  609 still drafting), with their pick rows.
- `usage_daily`: the 28 rows this run created (all dated 2026-11-17, 1,260 of the views and all 665 rejected
  being synthetic) were **deleted** at the end; the table is empty again, as found.
- Opening league pages made the app refresh league 331 from Sleeper (`league_refresh.last_success_at` moved
  to 14:55:19) and fetch digest stats and trending adds (14:55:17). No ingest was called by hand.
  `draft_pick.adp_at_time` was already null on all 360 rows before the run.
- Browser pane: `localStorage` and `sessionStorage` for `localhost:5186` and `:5187` were cleared.
- Servers: all four processes (API, two vite, the logging proxy) stopped; 8086, 5186, 5187, 8096 are free.

## Assumed, not verified (owed after deploy)

- **`X-Real-IP` behind Railway.** Locally the header was set by hand. If production does not deliver it, every
  visitor shares one 60-a-minute bucket. The `limited=` count in the INFO line is the thing to read.
- **The cross-origin keepalive send** from `www.ballknowers.co` and `leagues.ballknowers.co` to the API host:
  every browser send here was same-origin through the vite proxy. Preflight and the allow-origin header were
  checked by curl only.
- **CSP (report-only)** allowing the send, and `Authorization` when the build has an API token (none set here).
- **`APP_OWNER_SLEEPER_USER_ID` in production**: whether it is set, and to the right id.
- Production hosts sending without `VITE_USAGE_COLLECT` (the `OWN_HOSTS` branch), and a production build at all:
  only `vite dev` was driven.
- V51 on the production database, and the backfill of `started_as_guest` there.

## Fixed after verification 2026-11-17, not re-verified in a browser

The parent session fixed the three page defects above after this run. Web `tsc -b` clean, vitest 3,607 passed
and the build passed after the edits. **None of the three was looked at in a browser again**, so V-02 (phone
width) in particular is unconfirmed. One earlier full vitest run showed 1 failure that was not identified and
did not recur in two re-runs.

- **V-01**: `web/src/pages/AdminUsage.tsx`, the owner line now says results shared include the owner's own,
  since a share carries no identity.
- **V-02**: `web/src/styles.css`, `.us-table` cells now wrap long route templates (expected: no sideways scroll
  at 375 px; not seen).
- **V-03**: `web/src/pages/AdminUsage.tsx`, days before counting began collapse to one line instead of one
  "not counted" row per day.
- `web/src/pages/AdminUsage.test.tsx` updated for the new wording and the collapsed days.
