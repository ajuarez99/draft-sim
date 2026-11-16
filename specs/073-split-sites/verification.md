# Live verification: 073 Split the website into a Time Machine site and a league site

**Date**: 2026-11-15 (run 1) and 2026-11-16 (run 2, after the session was restarted) · **Run by**: Claude (built-in browser pane) ·
**API**: `bootRun` from this worktree's `backend/` on 8080 with `CORS_ORIGINS=http://localhost:5173,http://localhost:5184,http://localhost:5185`. Confirmed to be this tree: the java process's classpath starts `...\.claude\worktrees\073-split-sites\backend\build\classes\java\main`. `/api/health` answered `weightsLoaded: true`. ·
**Web**: three Vite dev servers from this worktree's `web/`: 5173 (no variable, combined), 5184 (`VITE_SITE=timemachine`, `VITE_LEAGUES_ORIGIN=http://localhost:5185`), 5185 (`VITE_SITE=leagues`, `VITE_TM_ORIGIN=http://localhost:5184`). Confirmed: each node process's command line is `...\worktrees\073-split-sites\web\node_modules\...\vite.js --port <port>`, each serves `/src/site.ts` (a file only this branch has), and `/src/siteEnv.ts` on 5184 / 5185 carries `VITE_SITE: "timemachine"` / `"leagues"`. ·
**Database**: Postgres 14 on `localhost:5433` (db/user `draftsim`), Flyway V49. 5432 not touched. ·
**Leagues used**: "(Foot) Ball Knowers" (NFL 2026, in season, week 10; and NFL 2025, complete). No NBA league exists locally.

**How the servers were started, and why not `preview_start`.** `preview_start draft-sim-api-split` answered "No server named draft-sim-api-split" and listed the MAIN checkout's launch entries. So the known trap is real here: from this worktree, `preview_start` reads `C:\Users\allan\source\draft-sim\.claude\launch.json`. All servers were therefore started as background shells from the worktree's `backend/` and `web/` directories, with the same environment variables the four new launch entries set. **The four new launch entries themselves were not exercised** (they cannot be reached from a worktree session).

**Signed in as**: `popsharky` (commissioner of the local NFL league, real Sleeper lookup through the backend) for league pages and a fresh Time Machine game; the synthetic `it-ui-072` for the pre-existing game 80, by writing `bk.user.v1` by hand.

**Tooling limits that shape the evidence.** The browser pane's window was hidden for most of the run: screenshots and real mouse/keyboard input timed out more often than they worked. Most clicks were therefore made by calling `.click()` on the real DOM element, form submits by setting the input and calling `requestSubmit()`, and layout was measured with `getBoundingClientRect` / scroll sizes rather than judged by eye. Four screenshots were captured and are in `verification-shots/`. Where a check used a stub or an injected value it says so in the row.

Automated suites at verification time: backend not run (no backend file changed) · web vitest: see H · `tsc -b` exit 0 · production build exit 0 in all three modes, exit 1 for the typo.

## Side effects of this run on shared state (read this first)

- **The app refreshed the local NFL league from Sleeper by itself.** Opening the league home on 5173 fired the app's own `POST /api/leagues/1346366555759341568/refresh` (spec 009 auto refresh; seen in the network panel and in the backend log 20:30:57 to 20:31:31 on 11-15: "league history: 2 seasons, 24 roster-seasons, 312 roster-weeks ... 751 transactions ingested", "player-games: nfl 2026 -- 10 weeks fetched", "nfl 2025 -- 18 weeks fetched", "refresh: chain ... 2 season(s) refreshed"). I did not call any ingest endpoint and the admin token was disabled, but the effect is a history / transactions / player-games re-ingest of both NFL seasons in the shared 5433 database. The league home showed a pre-season layout on the first visit and the in-season layout ("You're 2nd of 12 at 7-2") afterwards. The draft ingest is not part of that chain (read in `LeagueRefreshService`); `draft_pick.adp_at_time` was 0 of 180 on both drafts when read after the refresh. Its value before the refresh was not read, so "unchanged" is ASSUMED.
- **One Time Machine game was created and finished**: `historical_game` id 81, NBA 2002-03, owner popsharky (sleeper id 1122386008709910528), plus that user's `historical_profile` row and the badges it earned.
- **The one-time "badge unlocked" pop-ups for game 80 (`it-ui-072`) were consumed**: the first view of game 80 on 5173 showed them and marked them seen on the backend.
- No mock draft was created (`mock_draft_session` count stayed 0). No SQL was written by hand; every `psql` call was a `select`.
- Browser storage on 5173, 5184, 5185, 5186 and 5187 was cleared at the end of run 1.

## Results

"Run 1" = verified on 2026-11-15 before the restart, on servers that are now gone. Rows re-confirmed in run 2 say so.

| # | Scenario | Sport / league | Result | Evidence |
|---|---|---|---|---|
| A1 | PR-03: is the 403 for an origin not in the list real? | n/a | PASS (prediction confirmed) | Run 1. `curl -i -X POST -H "Origin: http://localhost:5199" -H "X-Sleeper-User: it-ui-072" localhost:8080/api/history/games` → `HTTP/1.1 403`, body `Invalid CORS request`. A plain `GET /api/health` with the same origin: 403 too. Same GET with `Origin: http://localhost:5184` → 200 with `Access-Control-Allow-Origin: http://localhost:5184` and game 80 in the body. Preflight for 5185 with `x-sleeper-user` → 200 with allow headers. Through the Vite proxy (`curl -H "Origin: http://localhost:5186" localhost:5185/api/sleeper/user/popsharky`) → 403: the proxy does keep the Origin header. Not run against a backend with NO `CORS_ORIGINS` (the plain config); the unlisted-origin result stands in for it. |
| A2 | PR-10: what the league sign-in screen says when its origin is not allowed | league site | PASS (visible error, not a blank page) | Run 1. A fourth dev server on 5186 (`VITE_SITE=leagues`, `VITE_API_BASE=http://localhost:8080`, so the browser calls the backend cross-origin as production does). Typed popsharky, pressed Enter (real keyboard input). Screen: "Couldn’t reach Sleeper just now -- that’s not on you. Try again." Console: "Access to fetch at 'http://localhost:8080/api/sleeper/user/popsharky' from origin 'http://localhost:5186' has been blocked by CORS policy". Screenshot `verification-shots/leagues-unlisted-origin-signin-error.jpg`. With `bk.user.v1` planted by hand on 5186, the league home rendered its shell with "Couldn't load the latest matchup / standings / power rankings / awards / player spotlight." Note: without `VITE_API_BASE` a dev server only fails on POSTs, because a browser sends no Origin on a same-origin GET; the 5186 set-up was needed to see the production-shaped failure. |
| B1 | Combined: signed-out landing | 5173 | PASS | Run 1. Title "Ball Knowers: Fantasy Football & Basketball for Sleeper Leagues", canonical `https://www.ballknowers.co/`, h1 "Your fantasy league, all season long.", sections This week / The season / Draft / History. The combined build's `robots.txt` and `sitemap.xml` are byte-identical to `origin/main:web/public/` (diff run). The builder's "index.html is byte-identical to before" was not re-run. |
| B2 | Combined: sign in, league picker | 5173 | PASS | Run 1. Real typing + Enter. "Welcome back, popsharky", (Foot) Ball Knowers card, four "From Sleeper / Set up" cards. |
| B3 | Combined: league home + other league pages | NFL 2026 | PASS | Run 1. By clicking the real links, top bar DOM node identical across every hop (no shell remount): league home, Matchups (`/weekly-report`, week 9 scores), Power rankings, Playoff odds ("No forecast for this league"), Team strength (table), Luck (table), Bench points (table), Awards, History (standings), Managers ("Scouting report"). No console errors other than the expected 409 / 404 below. |
| B4 | Combined: a draft board | NFL 2026, 2025 | NOT RUN (data) | `/drafts/<id>/board` answers "The draft board for this sport hasn't been built yet." on 5173 and 5185; `GET /api/targets?sleeperDraftId=...` is 409. The local database has no NFL board, so the board grid itself was never rendered on any site. `/drafts/<id>` (simulation room) did render: "The simulation hasn’t started yet. 12 teams · 15 rounds", seats listed. |
| B5 | Combined: top bar "Mock drafts" opens the new-mock modal | 5173 | PASS | Run 1. Clicked from `/leagues/<id>/superlatives`: address became `/`, dialog "PRACTICE ROUND / START A MOCK DRAFT / 1 · SPORT / 2 · USE SETTINGS FROM (Foot) Ball Knowers" was open, top bar node unchanged. "Start NFL mock" led to `/mock/new` ("Mock draft setup", seats listed). The mock itself was not started (no board data). |
| B6 | Combined: Time Machine link in top bar and phone menu | 5173 | PASS | Run 1. Top bar at 1366: Leagues, Mock drafts, Managers, Time Machine. Phone (375) menu sheet: Leagues, Mock drafts, Managers, Time Machine → `/history`, Sign out. |
| B7 | Combined: `/history` home and existing game | NBA 2008-09, game 80 | PASS | Run 1. As `it-ui-072` (storage planted by hand): home lists "2008-09 · Missed the playoffs (9th) · Score 76", level 2, 2 of 21 badges; links go to `/history/80`; the game page shows results with "Draft another year" and "Back to Time Machine" → `/history`. As popsharky the home showed the empty state ("No games yet"). |
| B8 | Combined: switching seasons / drafts remounts cleanly | NFL 2026 ↔ 2025 | PASS | Run 1. Season switcher on Awards: `/leagues/1346366555759341568/superlatives` → `/leagues/1254190892974084096/superlatives` ("through week 14") → back ("through week 9"); top bar node unchanged, content changed. Season switcher on the draft page: `/drafts/1346366555776126976` → `/drafts/1254190894563729408/board`, the `.room` node was replaced. |
| B9 | Combined: scroll and focus across navigations | 5173 | PASS | Run 1. `main` scrolled to 700 on Awards, clicked Luck: `main.scrollTop` 0, focus on body; Back: Awards at 0. Same behaviour as the unchanged `AppShell`; `origin/main` was not run side by side. |
| B10 | Combined: `/game/<id>?x=1#y` → `/history/<id>?x=1#y` in-app | 5173 | PASS | Run 1. Address bar ended at `http://localhost:5173/history/80?x=1#y`. |
| B11 | Combined: unknown path | 5173 | PASS | Run 1. `/nope/xyz?x=1#y`: URL kept, top bar with all four links present, "Nothing here / No draft, mock or page at this address. Back to your leagues." Someone else's or a missing game (`/history/80` as popsharky, `/history/999999`) shows the same not-found. |
| C1 | TM front door, desktop and phone | 5184 | PASS | Run 1. Title "Ball Knowers Time Machine: Redraft a Real NBA or NFL Season". Text: "Draft a real season. See how it played out." / username form / "How to play" three steps / "Free. No password." Zero hits for league, leagues, mock, manager, managers in the page text; no links; no top bar. 375 wide: the document scrolls to its end (scrollY 404 of 404, last element's bottom at 796 of 812), no sideways scroll. 1366x768: `.tm-door` is the scroller (962 / 768). Screenshot `verification-shots/tm-front-door-375.jpg`. |
| C2 | TM: no jump when React takes over | built `timemachine` site on 5187 (`vite preview`) | PASS | Run 1. The delivered HTML was rendered with scripts off (sandboxed iframe, same width) and compared with the live page: h1, input, button and the "How to play" heading sit at identical rectangles at 1366x768 (h1 296,152,760x84; input 416,416; button 416,488; h2 at 690) and at 375x812 (h1 20,152,335x59; input 20,391; button 20,463; h2 at 695; document height 1216 both). Same font family. |
| C3 | TM: fresh sign-in → home, no moved notice | 5184 | PASS | Run 1 (input set programmatically, form's own submit handler). Storage after: `bk.user.v1`, `bk.tm.signedInHere.v1` = "1". URL `/`, title "Time Machine", no `.tm-moved` element. Repeated later through a forwarded link (F7): same. |
| C4 | TM: no league navigation or wording, signed in | 5184, home / room / season replay / results, 375 and 1366 | PASS, one known exception | Run 1. Interactive tree on the home: wordmark link "Ball Knowers Time Machine, home" → `/`, "Account: popsharky", "Play today's basketball game", "Draft a random year". Nothing else. Account menu: name, Sign out. Text search for league / leagues / mock / manager / managers in visible text and in title / aria-label / alt / placeholder attributes: **zero hits** on the home (all disclosures opened), the drafting room, the season replay and the results. Not a hit for the regex but worth Allan's eye: "App standard 12-team NBA roster · **Legacy Ball Knowers scoring**" in "How this works" and in the room's "What counts" (already flagged in the code review as his call). |
| C5 | TM: start a game, pick, auto-finish, results | NBA 2002-03, game 81 | PASS | Run 1. "Draft a random year" → `/game/81`. "Draft" on Tim Duncan → `POST /api/history/games/81/pick` 200 from origin 5184, banner moved to "Team 10 is on the clock". "Auto-finish the draft" → season replay → "See your results": "You lost in the semifinal · 10-9 · Draft score 79". No console errors. |
| C6 | TM: share text link is the bare origin | game 81 | PASS (clipboard stubbed) | Run 1. `navigator.share` removed and `navigator.clipboard.writeText` replaced with a recorder, then "Share your result" clicked. Recorded text ends with the line `http://localhost:5184/`. Page said "Copied. Paste it anywhere." |
| C7 | TM: room height and scroll in the new shell, 1366x768 | game 81 while drafting | PASS (identical to combined) | Run 1. `main.app-main` 52..768, banner 115..220 sticky, pick-order list, player pane and roster each scroll inside themselves. `main` itself overflows by 9px (725 / 716). The **same numbers, to the pixel,** were measured for the same game at `/history/81` on 5173, so the new shell changes nothing here; the 9px is older than this feature. Screenshot `verification-shots/tm-room-1366x768.jpg`. |
| C8 | TM: room at phone width | game 81 while drafting, 375 | PASS (identical to combined), no screenshot | Run 1. One scroller (the document, 10,575 tall), no sideways scroll, banner sticks at 48px. Same measurements on 5173. See observation O-1. The screenshot attempt timed out, so this row is numbers only. |
| C9 | TM: "Back to Time Machine", past game at `/game/<id>` | game 81 | PASS | Run 1. Results page links: "Draft another year" → `/`, "Back to Time Machine" → `/`. Clicking it landed on `http://localhost:5184/`; the home's "View" and "See the game" links are `/game/81`; clicking one opened `/game/81`. |
| C10 | TM: sign out | 5184 | PASS | Run 1. From `/game/81`: account menu → Sign out → `http://localhost:5184/`, front door, title back to the landing title. `bk.user.v1` removed (the signed-in-here marker stays, which is right). |
| C11 | TM: not-found wording | 5184 | PASS | Run 1. `/nope/xyz?x=1#y`: shell present, "Nothing here / No page at this address. Back to Time Machine." link → `/`. Someone else's game (`/game/80` as popsharky): same. |
| C12 | TM: tab title | 5184 | PASS | Run 1. Signed out "Ball Knowers Time Machine: Redraft a Real NBA or NFL Season"; signed in "Time Machine". |
| C13 | TM: not-counted reason reads "by the NBA" | game 81 | PASS with an injected payload; real old season NOT RUN | Run 1. The game is random-year only and landed on 2002-03, whose reasons do not contain the phrase. The stored reasons for 1970 to 1978 still say "not tracked by the league that season" (read from `historical_season.excluded_stats`). To see the display rewrite, the page's `fetch` was wrapped so game 81's `season.excludedStats` carried that exact stored string for steals and blocks; the room then printed "steals not tracked by the NBA that season", "blocks not tracked by the NBA that season", and the league-word search stayed at zero. A real game in a 1970s season was not played. |
| D1 | Moved notice shows for a browser signed in before the switch | 5184 | PASS | Run 1. Storage cleared, only `bk.user.v1` planted by hand. Home shows "Looking for your leagues? They moved to localhost:5185." with a link to `http://localhost:5185/` and a "Dismiss" button, above the hero (760 wide at desktop; 343 wide at 375, no sideways scroll). Screenshot `verification-shots/tm-home-moved-notice-375.jpg`. |
| D2 | Dismiss → gone, reload → still gone | 5184 | PASS | Run 1. After Dismiss: element gone, `bk.tm.movedNotice.dismissed.v1` set. After a full reload: still absent. |
| D3 | Follow the link instead | 5184 → 5185 | PASS | Run 1. Reset, planted user + three formulas. Link href was `http://localhost:5185/#bk-carry=eyJ2...` (959 characters). Clicked: landed on `http://localhost:5185/` (carry stripped). Back: `http://localhost:5184/`, notice absent, dismissed key set, formulas still in 5184's storage. |
| E1 | League site: signed-out landing | 5185 | PASS | Run 1. League landing ("Your fantasy league, all season long."), league title. |
| E2 | League site: sign in, league pages load | NFL 2026 | PASS | Run 1. Signed in through the form at a forwarded URL (F7). Then by link clicks, no shell remount: league home ("You're 2nd of 12 at 7-2"), Matchups, Power rankings, Playoff odds, Team strength, Luck, Awards, History, Managers, a manager's history. Draft board: same "hasn't been built yet" as B4. No console errors. |
| E3 | League site: no Time Machine link | 5185 | PASS | Run 1. Top bar: Leagues, Mock drafts, Managers, search, account. Phone menu sheet: Leagues, Mock drafts, Managers, Sign out. "time machine" in page text on all ten pages walked: zero. No `/history` or `/game/` href on the league home. Jump-to (opened by a synthetic Ctrl+K keydown): "time" → no results, "machine" → no results, "history" → only "Standings / (Foot) Ball Knowers", "power" → "Power rankings" (so the box was working). |
| E4 | League site: tab title, unknown path | 5185 | PASS | Run 1. Signed-in title "Ball Knowers". `/nope/xyz?x=1#y` and `/game/80`: shell present, "Nothing here / No draft, mock or page at this address. Back to your leagues." |
| F1 | 5184 `/history?x=1#y` → `/?x=1#y` | signed in and out | PASS | Run 1. Address bar `http://localhost:5184/?x=1#y` both ways (home signed in, front door signed out). |
| F2 | 5184 `/history/<id>?x=1#y` → `/game/<id>?x=1#y` | signed in | PASS | Run 1. `http://localhost:5184/game/80?x=1#y`, game rendered (as `it-ui-072`). Signed out: covered by F6's second hop. |
| F3 | 5184 league paths → same path, query, hash on 5185 | signed in and out | PASS | Run 1. Each of `/leagues/1346366555759341568/power`, `/drafts/1346366555776126976/board`, `/mock/new`, `/managers`, `/admin/digest`, and `/managers/718/history`, with `?x=1#y`, ended at `http://localhost:5185<same path>?x=1#y`. Signed in on both: the page rendered (power rankings, mock setup, scouting report, "ADMIN / Morning digest"). Signed out on both: 5185 showed its landing at that URL. |
| F4 | 5185 `/history?x=1#y` → 5184 | signed in and out | PASS | Run 1. Ended at `http://localhost:5184/?x=1#y` (two hops: cross-site to `/history`, then in-app). |
| F5 | 5185 `/history/<id>?x=1#y` → 5184 game | signed in and out | PASS | Run 1. `http://localhost:5184/game/80?x=1#y` (signed in, game shown); `http://localhost:5184/game/81?x=1#y` (signed out, front door). |
| F6 | 5185 `/leagues/<id>/history` and `/managers/<id>/history` stay league pages | signed in and out | PASS | Run 1. Both stayed on 5185 at the same URL: league standings / manager page signed in, league landing signed out. |
| F7 | FR-013: sign in on the destination → the page the link named | both directions | PASS | Run 1. 5184 → `5185/leagues/.../power?x=1#y` signed out, signed in there: "Week 10 power rankings" at the same URL. 5185 → `5184/game/81?x=1#y` signed out, signed in there: game 81's results at the same URL, no moved notice. |
| F8 | Back button after a forward | 5184 → 5185 | PASS | Run 1. From `5184/`, opened `5184/managers/718/history?x=1#y` (forwarded to 5185), Back → `http://localhost:5184/`. No loop. |
| G1 | Formula carry, first time | 5184 → 5185, storage level | PASS | Run 1. The stat calculator cannot be opened locally ("Player pages are for basketball leagues." on the NFL league), so this is **storage level, not the calculator's own list**. Three formulas were made with the app's real `saveFormula` (module imported in the 5173 page), one with a non-ASCII name and non-default settings, and the resulting `bk.statCalc.saved.v1` value planted on 5184. With the key absent on 5185, `5184/mock/new?x=1#y` ended at `http://localhost:5185/mock/new?x=1#y`: no `bk-carry` in the address bar, `#y` intact, and 5185's store held the same three (names, expressions, `per36`, `LAST_10` all intact). Same result through the notice link (D3). |
| G2 | Carry does not overwrite | 5185 already has one formula | PASS | Run 1. 5185 store set to one formula "LG own"; forward from 5184 → store still only "LG own", address bar clean. |
| G3 | Deleted formulas do not come back | 5185 after delete-all | PASS | Run 1. "LG own" removed with the app's real `removeSaved` (store became `{"v":1,"formulas":[]}`); forward from 5184 → store still `{"v":1,"formulas":[]}`. |
| G4 | Formulas appear in 5185's stat calculator UI | n/a | NOT RUN | Needs a basketball league with stats. |
| H1 | Three production builds | n/a | PASS | Run 1, one at a time, each exit 0. Delivered files read as text: see the table under "Delivered files". |
| H2 | `VITE_SITE=leauges npm run build` fails | n/a | PASS | Run 1. Exit 1: `Error: VITE_SITE: unknown value "leauges". Use "timemachine", "leagues", or leave it unset (combined).` |
| H3 | `og-image-tm.png` | n/a | PASS | Run 1. Present in `dist/` of the timemachine build (44,436 bytes, identical to `public/`). Viewed: 1200x630, "BALL KNOWERS / Time Machine / Draft a real season. See how it played out. / Free with a Sleeper username / NBA NFL". It is also present in the other two builds (both images live in `public/`); only the tags differ. |
| H4 | `npx tsc -b` | n/a | PASS | Run 1. Exit 0, no output. |
| H5 | `npx vitest run` | n/a | PASS on a quiet machine (197 files / 3179 tests); one flaky failure on a loaded one (V-01) | See "Test suite" below. |
| I | SC-004: same game content on 5184 as at `/history` on 5173 | NBA 2008-09, game 80, `it-ui-072` | PASS | Run 1. Home text (the moved notice and the countdown removed): 1,087 characters, SHA-256 prefix `3f20ab509fc43aad` on both. Game page text: 2,396 characters, `6b2fc34efef41b49` on both. (The first 5173 view of the game was 2,569 characters because it showed the one-time badge pop-ups; the second view matched.) |
| J | SC-005: one NBA league on the league site | n/a | NOT RUN | No NBA league in the local database. Needs an NBA league ingested (admin token, writes to the shared database), then the same page walk as E2 plus the stat calculator for G4. |

Not run: see J, B4, G4, C13's real-season half, and "Not run" at the end.

## Delivered files (H1), read from `dist/` after each build

| | unset (combined) | `timemachine` | `leagues` |
|---|---|---|---|
| `<title>` | Ball Knowers: Fantasy Football &amp; Basketball for Sleeper Leagues | Ball Knowers Time Machine: Redraft a Real NBA or NFL Season | same as combined |
| canonical, `og:url` | `https://www.ballknowers.co/` | `https://www.ballknowers.co/` | `https://leagues.ballknowers.co/` |
| `og:image`, `twitter:image` | `https://www.ballknowers.co/og-image.png` | `https://www.ballknowers.co/og-image-tm.png` | `https://leagues.ballknowers.co/og-image.png` |
| description | "NFL and NBA fantasy tools for Sleeper leagues: power rankings, ..." | "Draft a team from a real NBA or NFL season, then watch that season play out. Free, with just a Sleeper username." | same as combined |
| pre-rendered body | league landing | "Draft a real season. See how it played out. ..." | league landing |
| whole-word league / leagues / mock / manager(s) in `index.html` | 34 | **0** | 39 |
| "time machine" in `index.html` | 0 | 6 | 0 |
| hosts named in `index.html` | only `www.ballknowers.co` | only `www.ballknowers.co` | only `leagues.ballknowers.co` |
| `robots.txt` Sitemap line | `https://www.ballknowers.co/sitemap.xml` | `https://www.ballknowers.co/sitemap.xml` | `https://leagues.ballknowers.co/sitemap.xml` |
| `sitemap.xml` `<loc>` | `https://www.ballknowers.co/` | `https://www.ballknowers.co/` | `https://leagues.ballknowers.co/` |

All rows match `contracts/site-routes.md`.

## Test suite (H5)

| Run | When | Machine state | Files | Tests | Result |
|---|---|---|---|---|---|
| 1 | 2026-11-15 20:46 | `bootRun`, four Vite servers and the browser pane running | 196 passed, 1 failed (197) | 3178 passed, 1 failed (3179) | exit 1, 267.8s. The failure is V-01 |
| the failing file alone, 3 times | 2026-11-15 | same | 1 passed | 6 passed | 3 of 3 green |
| 2 | 2026-11-15, second full run | same | not finished | not finished | cut off by the session restart; no summary line was written |
| 3 | 2026-11-16 | nothing else running | **197 passed (197)** | **3179 passed (3179)** | exit 0, 116.3s |

The counts match the last reported 197 files / 3179 tests. `npx tsc -b`: exit 0 (run 1; also runs inside each `npm run build`, all three exit 0).

## Run 2 (2026-11-16, after the restart)

Every server from run 1 was gone. Restarted the same way (background shells from the worktree; ports 8080, 5173, 5184, 5185) and re-proved the tree: the three Vite processes' command lines name `...\worktrees\073-split-sites\web\...`, `/src/site.ts` is served on each, 5184 and 5185 report `VITE_SITE` `timemachine` / `leagues`; the backend's java classpath starts in `...\worktrees\073-split-sites\backend\build\classes\java\main`; `/api/health` `weightsLoaded: true`. The 5186 and 5187 helpers were not restarted.

Re-confirmed, cheaply:

- Browser storage on 5173, 5184 and 5185 was empty (the clean-up at the end of run 1 held).
- Signed out, address bar: `5184/leagues/1346366555759341568/power?x=1#y` → `http://localhost:5185/leagues/1346366555759341568/power?x=1#y`; `5185/history/81?x=1#y` → `http://localhost:5184/game/81?x=1#y` (front door title); `5173/game/81?x=1#y` → `http://localhost:5173/history/81?x=1#y`.
- CORS: unlisted origin POST → 403; `Origin: http://localhost:5184` GET → 200 with `Access-Control-Allow-Origin: http://localhost:5184`.
- Access on the Time Machine endpoints (no new route in this feature, checked anyway): `GET /api/history/games` with no `X-Sleeper-User` → 401, blank header → 401; `GET /api/history/games/80` as a stranger → 404.
- With `bk.user.v1` and the signed-in-here marker planted by hand for `it-ui-072`: `5184/history/80?x=1#y` → `http://localhost:5184/game/80?x=1#y`, page text 2,396 characters, hash `6b2fc34efef41b49` (the same as run 1 on both sites), top bar = wordmark + account only, console clean.

Deliberately not repeated in run 2: any league page. Opening one fires the app's automatic refresh, which re-ingests the league into the shared database (see "Side effects"); once was enough. So every league-page row above rests on run 1 only.

Servers stopped at the end of run 2: 8080 (pid 31496), 5173 (pid 29364), 5184 (pid 29784), 5185 (pid 25020), each with its child process. Run 1's servers (8080 pid 15232, 5173 pid 19112, 5184 pid 4044, 5185 pid 31656, 5186 pid 12168, 5187) ended with the session restart, not by me. Postgres on 5433 was left running. Nothing listens on 8080, 5173, 5184, 5185, 5186 or 5187 now. Browser storage on the three origins was cleared again.

Files this verification added to the worktree: `specs/073-split-sites/verification.md` and four images under `specs/073-split-sites/verification-shots/`. `web/dist/` (git-ignored) holds the last build made, the `timemachine` one. Nothing was committed, staged or edited.

## Defects found during verification

None of CRITICAL, HIGH or MEDIUM severity in the feature. Entries below are what was seen.

- **V-01 (LOW, test flake, not in a file this feature touches) - `PowerRankings.week.test.tsx` failed once in a full run.** Run 1's full `npx vitest run` gave 196 of 197 files and 3178 of 3179 tests passing; the failure was `src/pages/PowerRankings.week.test.tsx > Your ballot reads the ladder week, not the open week > shows week-1 ranks while week 2 is open and empty` ("Unable to find an element with the text: /You voted yourself 1st\./"). The file is unmodified on this branch. Run alone it passed 3 times out of 3 (6 of 6 tests). The full run happened while `bootRun`, four Vite servers and a browser were busy on the same machine. Reproduction: not reliable; run the full suite on a loaded machine. Treat as a timing-sensitive test, not a 073 regression.

## Observations (not defects of this feature)

- **O-1 (older than this feature, seen on both 5173 and 5184) - phone-width room: the top bar scrolls away and the sticky banner keeps a 48px gap above it.** At 375 wide the room's document is ~10,575px tall but `.app-shell` is 812px, so the sticky top bar leaves with its parent while `.hist-banner` sticks at `top: 48px`. Measured at scrollY 1500: top bar bottom at -688, banner top at 48. Identical on the combined site, so the split did not cause it. Reproduce: phone width, a game in the drafting state, scroll down the player list.
- **O-2 - the moved notice's link is a 20px-tall tap target at phone width** (99x20; the Dismiss button is 87x37). Small for a thumb. The link is inline text in a sentence.
- **O-3 - "NFL" appears on the Time Machine front door and share image**, and the local database has no NFL Time Machine seasons, so a local player can only draw basketball. Production has both sports; nothing to fix, but it explains why no NFL Time Machine game was played here.
- **O-4 - `preview_start` from this worktree cannot see the four new launch entries** (it reads the main checkout's `launch.json`). They will work once the branch is merged, which is ASSUMED, not seen.
- **O-5 - in dev, a wrong origin only breaks POSTs.** A dev server whose origin the backend does not allow still loads every GET through the Vite proxy (no Origin header on a same-origin GET) and fails on the first POST with 403. Production is cross-origin for everything, so A2's failure is the one a real misconfiguration gives.

## Assumed, not verified

- That the four new `.claude/launch.json` entries start the right thing (O-4). The environment variables they set were used by hand and behaved.
- That `adp_at_time` and other draft data were not changed by the automatic refresh (not read beforehand).
- That the combined `index.html` is byte-identical to `origin/main`'s (builder's check, not repeated; only robots and sitemap were diffed).
- That scroll, focus and remount behaviour on the combined site equals `origin/main`'s: the combined build was measured, `origin/main` was not run beside it. The code paths (`AppShell`, the scroll reset) are unchanged in the diff.
- That the league site works for an NBA league (SC-005) and that carried formulas show in the stat calculator's own list (G4).
- That a real 1970s Time Machine season prints "by the NBA" (C13 used an injected payload with the stored string).
- That real mouse and touch input behaves like the DOM `.click()` calls used for most steps. Real typing and Enter were used for two sign-ins (5173, 5186).
- Analytics: no page-view request was inspected on any site (no website id is set locally).

## Not run (needs production or data that is not here)

- Real DNS for `leagues.ballknowers.co`; the Squarespace record.
- Railway: building each mode there, the second web service, and whether the plan allows a second custom domain (still unconfirmed, as `quickstart.md` says).
- Production CORS (`CORS_ORIGINS` with the league address on the real backend).
- Umami receiving the league host; the opt-out being per address.
- How link-preview bots render the Time Machine tags and image.
- SC-005's NBA league, the live draft room, a started mock draft, a rendered draft board, the stat calculator UI (all: no local data).
- The backend suite (no backend file changed).
- A backend started with no `CORS_ORIGINS` at all.

## Counts

52 rows: 49 PASS (two of them with a stated qualifier: C6 used a stubbed clipboard, C13 an injected payload), 0 FAIL, 3 NOT RUN (B4 draft board, G4 calculator UI, J NBA league), plus the real-season half of C13. H5 is PASS on the clean run and carries V-01 from the loaded run.

## Verdict

The split behaves as the contract table says on all three builds, on real origins, against the real backend. Combined mode, which is what merging ships, showed no regression in anything that could be exercised: shell never remounted across nested routes, the mock modal still opens from the top bar, scroll resets, `/game/<id>` rewrites in place, and its robots and sitemap are byte-identical to `origin/main`. The Time Machine site shows no league navigation or league wording anywhere that was reached, its room measures pixel-for-pixel the same as on the combined site, the redirects keep path, query and hash in both directions signed in and out, the moved notice and the formula carry do what FR-019 and FR-020 say, and the predicted CORS 403 is real with a visible error on sign-in. No defect was found in the feature; the one red result is a timing-sensitive test in an untouched file that passes alone and in a quiet full run. What this run could not show is anything that needs an NBA league, a built draft board, the stat calculator's own screen, or production (DNS, Railway, production CORS, analytics, link previews), and it changed shared local data by triggering the app's own league refresh. Those gaps are listed above and should be read before calling SC-005 met.
