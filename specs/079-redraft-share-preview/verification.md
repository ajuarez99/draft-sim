# Live verification: 079 Redraft share preview

**Date**: 2026-11-21 · **Run by**: Claude (built-in browser pane, curl, node scripts)

**API**:
- `bootRun` from this worktree's `backend/` on port 8093, against Postgres `localhost:5433`, with `REFRESH_ONVISIT_ENABLED=false`.
  It was started with `preview_start` from a temporary launch entry; that entry was removed afterwards.
- How I know it ran this tree's code:
  - The startup log names `...\redraft-share-preview-aa0f1c\backend\build\classes\java\main` as the classpath.
  - The class files (including `SharePreviewRenderException.class`, the CR-04 fix) are newer than their sources, and Gradle reported `compileJava UP-TO-DATE`.
  - `/api/history/public/games/608/preview` answered 200. Only this branch has that route.
  - `/api/health` returned `weightsLoaded: true`. JVM 21.0.12, PID 17072.

**Web**:
- Build: `VITE_SITE=timemachine VITE_API_BASE=http://localhost:8093 REDRAFT_SEASON_DATA=src/__fixtures__/redraft-seasons.json npx vite build`.
  It wrote 9 static pages. `server.site.json` = `{site: timemachine, apiBase: http://localhost:8093, origin: https://www.ballknowers.co}`.
- Served by `node server/server.mjs` on port 3093.
- For comparison, `npx serve@14.2.6 dist --config ../serve.site.json` ran on port 3094 against the same build.

**Database**: the shared 5433 cluster.
- I read it only, except for test games I created through the real API:
  - game 1174: today's NBA daily, played as a new guest;
  - games 1175-1217: 43 free-play games under `X-Sleeper-User: verify079-bulk`, each auto-finished.
- No existing rows were modified.
- Local data is NBA only: `historical_season` has 47 NBA seasons and no NFL.

**Games used**:
- 80, 81, 316: free play.
- 605: the 2026-11-17 daily, now in full view.
- 607, 608: chosen-season games.
- 1174: today's daily (2002-03 NBA), so **reduced**.
- 1175-1217: free play. They include four champions (1178, 1181, 1182, 1212) and records with ties (1188 at 14-4-1, 1199 at 7-3-1).
- 404 cases: 606 (abandoned), 609 (drafting), 999999 (unknown).

**Automated suites**: not re-run by me. The parent's post-fix figures (code-review.md, "Fixes applied") are:
- web targeted 51/51, `tsc -b` clean;
- backend `*SharePreview*` 5+13, `RedraftPublicMvcIT` 10, `LeagueLockMappingsIT` 4, with 0 skipped.

## Results

| # | Scenario | Sport / league | Result | Evidence |
|---|---|---|---|---|
| 1 | `/preview` JSON for COMPLETE games | NBA: 50 games | PASS | 200 with `Cache-Control: public, max-age=300` (see note 1) |
| 2 | `/preview` 404 rule | NBA | PASS | 606 (abandoned), 609 (drafting), 999999 and 1174 while drafting all gave 404; `abc` gave 400 |
| 3 | `/preview.png` is a real 1200×630 PNG | NBA: 50 games | PASS | See note 2 |
| 4 | PNG looked at (SC-002) | 1174, 608, 1178, 80, 1188 | PASS | See note 3 |
| 5 | PNG 404s | NBA | PASS | 606, 609 and 999999 answer 404 with 0 bytes |
| 6 | PNG latency, cold vs warm | 50 distinct games | MEASURED | See note 4 |
| 7 | Heap and RSS around 50 cold renders | backend PID 17072 | MEASURED, heap delta inconclusive | See note 5 |
| 8 | Unfurl with 5 bot UAs, parsed by `open-graph-scraper@6.10.0` | 1174 (reduced daily), 1178 (champion), 608 (chosen) | PASS | See note 6 |
| 9 | Same HTML for every client (no UA sniffing, FR-001) | 3 ids × 5 UAs | PASS | Exactly 1 distinct body per id across the 5 UAs |
| 10 | Each tag appears exactly once | 1174 | PASS | Raw head: one `<title>` and one of each of the 19 meta names/properties |
| 11 | Reduced daily leaks no player (US2, SC-003) | 1174 | PASS | See note 7 |
| 12 | Reduced daily: the preview matches what the shared page shows | 1174 | PASS | See note 8 |
| 13 | CR-01 fix: a reload mid-draft does not hide the preview after the finish | 1174 | PASS | See note 9 |
| 14 | Fallback when the backend is down, hung, malformed or oversized (US3, SC-004) | stub backends, real `createServer` | PASS | See note 10 |
| 15 | Fallback with the real backend stopped | 1190 (never loaded before) | PASS | See note 11 |
| 16 | Unknown, abandoned, drafting or zero-padded id | real servers | PASS | See note 12 |
| 17 | `/game/1178/` and `/game/1178?x=1` | | PASS | `/game/1178/` redirects 301 to `/game/1178`, the same as the CLI; `?x=1` still gets the per-game tags |
| 18 | Regression against the `serve@14.2.6` CLI on the same build | 24 paths | PASS | See note 13 |
| 19 | Security headers and compression | | PASS | See note 14 |
| 20 | Browser: the shared-result page renders | 1178, 1174 | PASS | See note 15 |
| 21 | PR-05: after a client-side move the head's image is the site image again | 1178 → `/nba/1983-84` | PASS | See note 16 |
| 22 | Browser network and console | | PASS | See note 17 |

### Notes on the results

1. **Words checked by row.**
   - Free play: "Ball Knowers Redraft · 2008-09 NBA · This team missed the playoffs (9th of 12)".
   - Past daily (605): "Redraft daily · Nov 17 · This team lost in the semifinal".
   - Chosen season (607, 608): the description carries "Chosen season, not the game of the day".
   - Champion (1178): "Ball Knowers Redraft · 1983-84 NBA · 🏆 Champion".
   - Ties: "14-4-1 · Draft score 84% · …".
   - Call to action: daily "Play today's Redraft.", free play "Draft a past season." (PR-10).
2. **PNG checks.** All 50 answered 200 `image/png`. Each had the PNG signature and an IHDR of 1200×630, checked for 80, 1174 and 608. Sizes ran 35-39 KB. Headers: `Cache-Control: public, max-age=86400`.
3. **What the PNGs show.** Each shows the brand line, the day or season line, the headline, the record, "Draft score N%" and 14 squares in rows of 10 and 4.
   - The champion card has a gold trophy and a gold "Champion".
   - The chosen-season card says "1995-96 NBA · chosen season".
   - The square colours match the alt-text counts. For 1174 that is 6 green and 8 red; for 608 it is 3 green, 1 grey and 10 red.
   - Nothing runs off the canvas, including "This team missed the playoffs (9th of 12)" and "14-4-1".
4. **PNG latency** (curl `time_total`, localhost).
   - Cold: median 40.6 ms, p90 46.0 ms, min 32.9 ms, max 175.4 ms. The max is the first render after startup.
   - Warm (cache hit): median 1.6 ms, max 2.3 ms.
5. **Heap and RSS** (`jcmd GC.heap_info` after `GC.run`; RSS is the Windows working set).
   - Heap used: 31,126 K before and 31,453 K after.
   - The 50 cached PNGs alone hold 1.85 MB, so the +0.3 MB delta means something else was freed. Read it as noise, not as the cache's cost.
   - RSS: 284.4 MB before and 275.5 MB after (private 327 → 317 MB). No growth.
   - Allocation per render is not measured here. The unit test's figure is about 3.2 MB per render (code-review.md).
6. **Unfurl check (15 fetches: 3 ids × 5 UAs).**
   - UAs: Slackbot-LinkExpanding 1.0, Twitterbot/1.0, facebookexternalhit/1.1, Discordbot/2.0, and the iMessage string "…facebookexternalhit/1.1 Facebot Twitterbot/1.0".
   - All 15 answered 200 in ≤ 46 ms.
   - The parser read `og:title`, `og:description` and `og:url` (`https://www.ballknowers.co/game/<id>`).
   - It also read `og:image`: absolute, `http://localhost:8093/api/history/public/games/<id>/preview.png?v=1`, with width 1200, height 630 and type image/png.
   - The image fetch with the same UA answered 200 `image/png` with a valid signature.
   - Also present: `og:image:alt`, `twitter:card=summary_large_image`, `twitter:title`, `twitter:image` and its alt, and `robots=noindex`.
   - Raw output: `unfurl.jsonl` in the scratchpad.
7. **Reduced daily (1174) carries no player.**
   - I collected 174 player names from the owner's full state (the `auto FINISH` response).
   - None of them appears in the preview JSON or in the served `/game/1174` HTML.
   - The preview JSON has no position tokens. Its only numbers are 0, 03, 13, 2002, 21, 6, 8 and 81, which are the date, record, counts, season and score. It has no ranks or pick numbers.
   - The card fields are: brandLine, headline, champion, record, draftScore, seasonLine, dayLine, chosenSeason, marks.
8. **The page agrees with the preview.** The public page shows "Game of the day, Nov 21", "This team lost in the semifinal", "13-6", draft score 81 and the squares ▲▲▲▼▼▼▲▼▼▲▼▼▲▼. The preview's `marks` and the PNG show the same 13-6, 81 and squares.
9. **CR-01 replay, live.**
   - `/game/1174` while the game was DRAFTING gave the plain shell: no `og:url`, title "Ball Knowers Redraft".
   - I then auto-finished the game; that took 1.47 s.
   - Fetching `/game/1174` straight after gave the per-game tags at once ("Redraft daily · Nov 21 · This team lost in the semifinal").
10. **Stub backends behind the real `createServer`.** The plain shell came back with a 200 every time. The security headers were present each time. Exactly one log line was written per case.

    | Backend | First load | Second load |
    |---|---|---|
    | dead port | 26 ms | 4 ms, cached 30 s |
    | hung, never answers | 1,019 ms (timeout) | 2 ms, the failure is cached |
    | wrong shape | 6 ms | |
    | 40 KB body | 6 ms | |

11. **The real backend stopped.**
    - Port 8093 was closed and PID 17072 was gone.
    - `/game/1190` answered 200 with the 2,243 B plain shell in 3.9 ms; refused connections fail fast. The log line was `preview 1190 unavailable: fetch failed`.
    - `/game/1178` still carried its per-game tags from the 10-minute word cache, as designed. Its `og:image` then points at a dead backend. In production the whole backend would be down too, so the image fails either way.
12. **Odd ids.**
    - `/game/999999`, `/game/606` and `/game/609`: plain shell, intercepted (they carry `Cache-Control: no-cache`).
    - `/game/0608` and `/game/abc`: not intercepted. They are served by serve-handler (they carry the ETag and `Content-Disposition: app-shell.html`), so the CR-03 fix holds.
13. **Same as the CLI on 24 paths.** Each path gave the same status, size and redirect on both servers:
    - pages: `/`, `/index.html` (301), `/daily`, `/daily.html` (301), `/nba`, `/nba/` (301), `/nba/1976-77`, `/nfl`, `/app-shell.html` (301), `/app-shell`, `/404.html` (301), `/history/5`;
    - 404 with the custom page: `/nowhere`, `/serve.site.json`, `/server.site.json`, `/package.json`, `/server/server.mjs`;
    - static files: `/og-image-tm.png`, `/robots.txt`, `/sitemap.xml`, the JS bundle;
    - `/game/`: `/game/abc`, `/game/` (301), `/game/1178/` (301).
14. **Headers and compression.**
    - The JS bundle: br when br is accepted, gzip when only gzip is. ETag, `Vary` and the four security headers are byte-identical to the CLI's.
    - `/game/1178`: gzip and chunked, with the same headers: CSP-Report-Only, nosniff, Referrer-Policy and HSTS.
    - The CSP is the Report-Only header, the same as today.
15. **Pages rendered in the browser pane.**
    - `/game/1178` showed "Shared result · 1983-84 NBA Redraft", "Champion", "14-5", draft score 83, and ▲ on rounds 4, 6, 8, 9, 10 and 14. That matches the PNG.
    - `/game/1174` showed the reduced view: "The picks and lineups show tomorrow".
16. **PR-05 in the browser.** I clicked "How 1983-84 played out", a client-side move: the navigation entry was still `/game/1178`. On `/nba/1983-84`, `document.head` then had:
    - `og:image` and `twitter:image` = `https://www.ballknowers.co/og-image-tm.png`;
    - both alt tags back to the site alt;
    - one `og:image`, and canonical `/nba/1983-84`.
17. **Browser network and console.**
    - CORS preflights (`OPTIONS`) answered 200.
    - `GET /api/history/public/games/{1178,1174}` answered 200.
    - The only console errors were two 404s from `GET /api/history/games/{id}`: the owner read that the page tries before falling back to the public read. That is the existing flow, not 079.

### Not run

- **Prod fonts.** Font presence in `eclipse-temurin:21-jre-jammy` was not checked: Docker is not running. The PNGs above were rendered by Windows JDK 21 with its fonts. Whether the Linux JRE image has a sans-serif font, and how the card looks there, is **assumed, not verified**.
- **The Docker image build** (web runtime stage, `npm install serve-handler compression`, `CMD node server/server.mjs`): Docker is down. code-review.md simulated the `/app` layout; I did not repeat that.
- **NFL end to end**: no NFL seasons or games on 5433. NFL wording and many-square wrapping (16-18 rounds) are covered by unit tests only.
- **Real iMessage, Slack, Discord and X clients**: not run. Only their user-agent strings were sent to a local server, and the image URL was `localhost`, which no real unfurler could reach.
- **`dailyNumber` form** ("Redraft daily #N · …"): the seam passes null, so only the date form was seen live.
- **Old-format results, a missing draft score, missing `pickFinishes`**: no such COMPLETE game exists locally. Unit tests only.
- **Reduced daily after midnight ET**, the same preview in full view: not live. Game 605, a past daily now in full view, had the same shape of words. The reduced-equals-full equality is asserted in `SharePreviewTest` and the IT; I did not watch it flip.
- **The 503 busy path** (Semaphore(2)) and the render-failure 503: not forced live.
- **Phone-width layout**: 079 changes no visible UI, only the served head, so I did not run it.
- **The leagues and combined builds** (FR edge "unchanged"): not built or run.

## Defects found during verification

None.

Observations, not defects:
- **O-1.** Once the app mounts on `/game/<id>`, `applyPageHead` sets the tab title and the og tags back to the generic "Ball Knowers Redraft". So the browser tab shows a generic title for a shared result, even though the served HTML had a per-game title.
  - Unfurlers never run the script, so previews are unaffected. This matches the 074 behaviour.
  - Noted in case Allan wants the per-game title kept in the tab.
- **O-2.** A word-cache hit (10 min) keeps serving per-game tags after the backend goes down, so `og:image` points at an unreachable PNG for up to 10 minutes.
  - This is by design (PR-11). An unfurler would show the card without an image.

## Assumed, not verified

- That production `VITE_API_BASE` is an absolute `https://api.ballknowers.co`, so `og:image` is absolute and reachable by unfurlers. Locally it was `http://localhost:8093`.
- That the prod JRE image renders text at all. See "Not run" above.
- That real unfurlers accept an `og:image` on a different host (`api.`) from the page (`www.`). This is common practice; I did not test it with a real client.
- The per-render allocation figure (about 3.2 MB) comes from the unit test reported in code-review.md, not from this run.
