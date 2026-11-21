# Implementation Plan: Redraft share preview

**Spec**: `spec.md` · **Date**: 2026-11-21

## Summary

The web container stops running the `serve` CLI and runs a ~100-line Node server, `web/server/server.mjs`, built on
`serve-handler@6.1.7` (the library `serve@14.2.6` itself runs, same config file, so every other path behaves
exactly as today). It intercepts `GET|HEAD /game/<digits>`, asks the backend for that game's preview words, and
answers `app-shell.html` with the per-game tags written into the head. The backend builds the words and renders a
1200×630 PNG with Java2D, both from `publicGame(id)`'s output and only from fields the reduced view keeps.

## The choice, and why (alternatives measured against the constraints)

| Option | Why not / why |
|---|---|
| Keep `serve`, add a rewrite to the backend | `serve-handler` rewrites only to local files; its redirects cannot tell a bot from a person, and redirecting everyone to `api.` loops or breaks the page. Rejected. |
| Share a backend URL (`api.ballknowers.co/s/<id>`) that redirects people back | Ugly domain in the pasted text, every link already shared stays generic, and the backend would own a human-facing page. Rejected. |
| Path routing at Railway's edge | Railway routes a domain to one service; no path rules. Rejected. |
| UA-sniff bots in the web server | Fragile lists, and different HTML for bots and people is cloaking. Not needed: the head is cheap. Rejected. |
| Render the image in Node | Needs a native canvas/resvg dependency in the web image. Java2D is in the JRE already. Rejected. |
| **Node server on serve-handler + backend preview JSON + backend PNG** | Same routing library and config as today; one new runtime dep the CLI already pulled in; the privacy rule lives in one place (the backend, next to `reduced(...)`). **Chosen.** |

## Design

### Backend (`backend/src/main/java/com/ballknowers/draftsim/`)

- `historical/SharePreview.java` — record `SharePreview(String title, String description, String imageAlt,
  Card card)` where `Card` is what the image draws: `brandLine`, `headline` (e.g. "Champion"), `champion` boolean,
  `record` ("13-1", nullable), `draftScore` (Integer, nullable), `seasonLine` ("2003-04 NBA"), `dayLine`
  ("Daily · Nov 21", nullable), `chosenSeason`, `marks` (List<String> of ahead/same/behind in pick order).
  Static `from(PublicGame pub, Integer dailyNumber)`. **Reads only**: `state.sport`, `state.season.label`,
  `state.teams`, `state.userSlot`, `state.dailyDate`, `state.chosenSeason`, and from `state.result`: `userFinish`,
  `userOutcome`, `format`, the standings row whose slot == userSlot (wins/losses/ties), `hindsight.draftScore` if
  present else `DraftScore.of(userPointsFor, pointsFor)`, and `pickFinishes[].{pickNo, mark | the two ranks →
  markOf}`. Nothing else. Old format detection mirrors web `isOldFormat` (check `historicalCopy.ts`).
  Text mirrors `historicalShare.ts` / `outcomeHeadline` with a neutral voice ("Lost the final", "Missed the
  playoffs (7th of 10)", "Finished 3rd of 12").
  - title: daily → `Redraft daily · Nov 21 · 🏆 Champion` (🏆 only for CHAMPION, new format); with dailyNumber →
    `Redraft daily #23 · 🏆 Champion`; free play → `Ball Knowers Redraft · 2003-04 NBA · Champion`.
  - description: `[record · ]Draft score 87% · 2003-04 NBA · <cta>`, cta "Can you beat it?" for a daily (a stranger
    can play the same one today), "Draft your own past season." otherwise; chosen season adds the 074 line's sense.
    Squares are NOT in the description (emoji runs render inconsistently in card text; they are in the image).
  - imageAlt: plain sentence: "Champion, 13-1, draft score 87%, 2003-04 NBA. 9 picks ahead of their draft spot, 3 even, 2 behind."
  - **dailyNumber seam**: `HistoricalGameState` has no `dailyNumber` on main at c00a846. The controller passes
    `null` with a `// SEAM(079→daily-number): pass state.dailyNumber() once it is on main` comment.
- `historical/SharePreviewImage.java` — `byte[] render(Card)`: `BufferedImage` 1200×630 TYPE_INT_RGB, antialiasing,
  colors from `og-image-tm.svg` (bg `#040913`, text `#f1f3f6`, muted `#8d96a5`, accent `#4fd1c5`, gold `#b58a1d` /
  champion, square green `#37a05a`, grey `#5b6472`, red `#d4504c`). Font: logical `Font.SANS_SERIF`, bold, sizes
  fitted with `FontMetrics` so the longest headline fits; squares are filled rounded rects in rows of ≤ 10, size
  shrinking for more picks; all content inside x 120..1080 so a centred crop keeps the essentials as far as possible.
  `ImageIO.write(png)`. Requires `java.awt.headless=true` (set in the class's static init only if unset, or in
  `DraftSimApplication`; Spring Boot sets headless true by default — check, and say which).
- `historical/SharePreviewService.java` — `Optional<SharePreview> preview(long id)` (calls `games.publicGame`);
  `Optional<byte[]> png(long id)` with a bounded LRU cache (`LinkedHashMap` access-order under a lock, 256 entries,
  ~30-60 KB each → ≤ ~15 MB worst case; measure). Only cached after a successful render of a COMPLETE game.
- `api/RedraftPublicController.java` — two new GETs under the existing `/api/history/public` mapping:
  `/games/{id}/preview` (JSON, `Cache-Control: public, max-age=300`) and `/games/{id}/preview.png`
  (`image/png`, `public, max-age=86400`). 404 under the same rule as `/games/{id}`.
- Tests: `SharePreviewTest` (words for champion daily, lost final free play, old format, ties, no score, no squares,
  chosen season, dailyNumber present), **reduction test**: preview of the reduced PublicGame equals the preview of the
  full PublicGame of the same game, and no player name from the full result appears in title/description/alt;
  `SharePreviewImageTest` (valid PNG 1200×630, renders 18 squares, pixel at a square's centre has the mark's colour,
  timing+allocation printed); `RedraftPublicMvcIT` additions (today's daily reduced preview carries no name; 404s);
  `LeagueLockMappingsIT` both routes with the lock ON.

### Web (`web/`)

- `web/server/preview.mjs` — pure functions, unit-tested by vitest: `gameIdFromPath(path)` (`/^\/game\/(\d{1,18})\/?$/`),
  `injectPreview(shellHtml, {title, description, imageAlt}, {gameUrl, imageUrl})` which replaces the existing tags
  (title, meta description, og:title/description/url/image/image:alt/width/height/type, twitter:title/description/
  image/image:alt) and adds any that are missing before `</head>`; escapes `& < > " '`. Must handle whatever exact
  markup `landingHtml.ts` writes for app-shell (read it; attribute order/quotes).
- `web/server/server.mjs` — `node:http` + `serve-handler`. Reads `../serve.site.json` and `../server.site.json`
  (`{site, apiBase, origin}`) at start, loads `dist/app-shell.html` into memory. For `GET/HEAD /game/<digits>` on the
  timemachine site: cached preview (TTL 10 min, ≤ 500 entries) or `fetch(apiBase + /api/history/public/games/<id>/preview,
  {signal: AbortSignal.timeout(1500)})`, body ≤ 16 KB, shape-checked; on success 200 injected HTML, else 200 plain
  shell. Sets the `**` headers from the serve config plus `Content-Type: text/html; charset=utf-8` and
  `Cache-Control: no-cache`. Everything else → `handler(req, res, serveConfig)`. Listens on `PORT || 3000`.
  Logs one line per preview failure (no game id spam beyond that).
- `vite.config.ts` / `src/siteFiles.ts` — write `server.site.json` next to `serve.site.json`
  (`{site, apiBase: env.VITE_API_BASE, origin: siteOrigin(cfg)}`), every site.
- `web/Dockerfile` runtime: `npm install serve-handler@6.1.7` (no `-g serve`), copy `server/` and
  `server.site.json`, `CMD ["node", "server/server.mjs"]`. `railway.toml` healthcheck `/` unchanged.
- `web/package.json`: `serve-handler` as an exact-pinned dependency so local runs and vitest use it.
- Tests (vitest): `preview.test.mjs` / `.ts` for injection + escaping + id parsing; `server.test` that starts the
  server on port 0 against a stub backend (http server) and a temp dist: injected on 200, plain on 404, plain on
  timeout, headers present, `/nba` still served by serve-handler, `/game/abc` plain.

### Docs

- `DEPLOY.md`: the web service now runs `node server/server.mjs`; rollback = previous image. `HANDOFF.md` block.

## Not being built, and why

- No per-player content in any preview, even after midnight: one code path, no flip, no answer key. (Stricter than
  required; the full result is one tap away on the page.)
- No numbering of dailies (other session owns it). No bot detection. No preview for the leagues site.
- No disk cache of PNGs: a restart re-renders on demand; measured cost decides if that is fine.

## Acceptance for the verification pass

Real `bootRun` on 5433 data and a real `npm run build` (VITE_SITE=timemachine) served by `node server/server.mjs`:
curl `/game/<id>` with a Slackbot/Twitterbot UA → per-game tags; an OG parser (e.g. `open-graph-scraper` or a small
parse) agrees; fetch the PNG and LOOK at it; reduced daily checked for absence of names; backend stopped → plain
shell within ~1.5 s; `/`, `/nba`, `/nba/<season>`, unknown path 404, CSP header all as before. Check fonts in the
`eclipse-temurin:21-jre-jammy` image if Docker is available; if not, say not checked.

## Amended after review 2026-11-21 (plan-review.md PR-01..PR-19)

Decisions (Allan said "continue" without picking; these are the session's recommended answers, stated so he can overrule):
- **PR-05** `applyPageHead` (web/src/pageTitle.ts) also resets `og:image`, `twitter:image` to the site image and
  `og:image:alt`/`twitter:image:alt` to `redraftCopy.imageAlt`, so per-game image tags never stick after a client-side
  move. Unfurlers read the served HTML only, so this does not touch previews.
- **PR-07** Title format follows spec 078's "#8 · Nov 21": with a number `Redraft daily #8 · Nov 21 · 🏆 Champion`, without
  (today, seam) `Redraft daily · Nov 21 · 🏆 Champion`. 079 merges after 078; 079 tests must not call
  `HistoricalGameState`'s positional constructor where avoidable (build PublicGame JSON fixtures / use existing helpers).
- **PR-10** CTA is evergreen: daily → "Play today's Redraft.", free play → "Draft a past season." No "Can you beat it?".
- **PR-11** Accepted: web server timeout 1000 ms; a failure (timeout, 5xx, network) is cached 30 s per id, a 404 for 60 s,
  a success for 10 min; fallback is the plain shell.

Folded in:
- **PR-01** `serve-handler` gets `public: <absolute dist path>`; test that `/serve.site.json`, `/server.site.json`,
  `/package.json` are 404 and `/` is 200.
- **PR-02/03** `compression@1.8.1` in front of the handler; ETag via serve-handler `etag: true`; one log line per request
  (method path status ms); SIGTERM/SIGINT close the server and exit.
- **PR-04** The server loads `app-shell.html` only when `server.site.json.site === 'timemachine'` and the file exists;
  any other site never intercepts.
- **PR-06** The app shell head is `landingHtml.ts:168-182` (`pageHeadTags`, kind appShell): title, robots noindex,
  theme-color, description, og:title, og:description, og:type, og:site_name, og:image, twitter:card, twitter:image.
  Injection replaces those and ADDS og:url, og:image:width/height/type/alt, twitter:title/description/image:alt.
- **PR-08** Java copy of headline/ordinal/dayLabel gets the same test vectors as `historicalCopy.test.ts` cases where they
  exist; voice is the shared-page voice ("This team lost the final"), not a third phrasing. Constraint from the
  coordinating session: `historicalShare.ts`, `historicalDaily.ts`, DailyPage/DailyCards, `api.ts`,
  `HistoricalDailyService`, `HistoricalGameService` are NOT edited in 079 (daily-number owns them).
- **PR-09** `SharePreview.from` first projects the PublicGame result through its own whitelist identical to `reduced(...)`'s
  kept fields (userFinish, userOutcome, format, own standings row, hindsight.draftScore computed, pickFinishes → mark),
  then builds from that projection only; a test asserts reduced and full give equal previews.
- **PR-12** PNG renders go through a `Semaphore(2)`; a render waiting > 2 s answers 503 (web falls back to nothing:
  the og:image URL is still in the HTML; the unfurler retries). Measure heap + RSS around 50 cold renders.
- **PR-13** Render catches `Throwable` subclasses `Exception | Error` from AWT (not OOM: rethrow `VirtualMachineError`);
  fonts: logical SansSerif; prod font presence unchecked (Docker down) and said so.
- **PR-14** Local NFL data absent: NFL verified by unit tests only; said so in verification.md.
- **PR-15** `/game/<id>/` (trailing slash) → serve-handler's own redirect as today; query strings ignored for matching.
  `/history/<id>` links stay generic (leagues/combined), said in spec.
- **PR-16** PNG route `produces = image/png`; non-numeric id → 400/404 as Spring gives; tested.
- **PR-17** serve-handler + compression exact-pinned in package.json; runtime stage `npm ci --omit=dev` from the lockfile is
  too heavy (react), so runtime installs the two pinned versions; `server.site.json` git-ignored like serve.site.json.
- **PR-18** og:image = `${apiBase}/api/history/public/games/<id>/preview.png?v=1` (backend host, absolute).
- **PR-19** `?v=1` is the render version; bump `SharePreviewImage.VERSION` when the drawing changes.
