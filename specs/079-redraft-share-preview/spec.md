# Feature Specification: Redraft share preview

**Feature branch**: `claude/redraft-share-preview-aa0f1c` · **Created**: 2026-11-21 · **Status**: Draft

## Context: what is there now (read from the code and from production 2026-11-21)

- A finished Redraft game is shared as plain text from `web/src/historicalShare.ts` `shareText`: season, finish,
  draft score, one 🟩⬜🟥 square per pick, then the link `https://www.ballknowers.co/game/<id>`.
- `/game/**` on the Redraft site is rewritten to `app-shell.html` by the generated `serve.site.json`
  (`web/src/siteFiles.ts:53` `APP_ONLY_SOURCES`, `serveConfig` at `:66`). The web service runs `serve@14.2.6` on
  that config (`web/Dockerfile` runtime stage). `app-shell.html` carries the generic head: title "Ball Knowers
  Redraft", the landing description and `og-image-tm.png` (`web/src/landingHtml.ts:53-71`, `redraftPages.ts:224`).
- So every shared link unfurls in iMessage / Discord / X / Slack as the generic site (measured with curl 2026-11-21,
  per the starting brief). The friend's result is only in the pasted text, if the app kept it.
- The public game read is `GET /api/history/public/games/{id}` (`RedraftPublicController.game`,
  `HistoricalGameService.publicGame` `:296`). While the game is today's game of the day it is REDUCED
  (`reduced(...)` `:317`): finish, outcome, format, the user's own standings row, `hindsight.{label, userPointsFor,
  userBeatIt, draftScore}`, `pickFinishes` rows of `{pickNo, mark}` only (CR-03 of spec 074). Season label is shown
  (Allan's call in 074). Full view from the next midnight ET.
- `/api/history/**` is on `LeagueLockInterceptor.PUBLIC_PATHS`; prod runs with the lock ON.
- Link-preview bots do not run scripts: whatever the preview says must be in the HTML response of `/game/<id>`, and
  the image must be a real PNG at an absolute URL.

## User Scenarios & Testing

### User Story 1 - A friend sees the result in the preview (P1)

A player finishes today's daily and pastes the share text into a group chat. The link card shows what they did:
the day, the finish, the draft score, the season, and an image with the finish, score and the pick squares.

**Acceptance**:
1. Given a finished daily game 42 on 2026-11-21 that won the title with a draft score of 87, when a client without
   JavaScript fetches `https://www.ballknowers.co/game/42`, then the HTML's `og:title` is
   "Redraft daily · Nov 21 · 🏆 Champion", `og:description` starts with the record and
   "Draft score 87%", names "2003-04 NBA" and ends with a call to play, and `og:image` is an absolute PNG URL
   for game 42.
2. Given that image URL, when fetched, then it is a 1200×630 PNG showing the finish, record, draft score, season and
   one square per pick in draft order, green/grey/red.
3. Given a free-play (not daily) game, the title names the season instead of the day; a chosen-season game says it
   was a picked season (spec 074 `shareChosenLine`).

### User Story 2 - The preview never gives away today's answer (P1)

**Acceptance**:
1. Given today's daily, the preview (title, description, image, alt text) contains nothing the reduced public view
   does not: no player, position, rank, pick position, lineup, hindsight roster or hindsight total.
2. Given the same game before and after midnight ET, the preview is the same (it is built only from fields the
   reduced view keeps), so there is nothing to flip and no stale cache to worry about.

### User Story 3 - Anything else falls back to today's page (P2)

1. An unknown, unfinished or abandoned game id, a backend that is down or slow (over the timeout), or a malformed
   answer: `/game/<id>` still answers 200 with the plain app shell (generic preview), and the page works as today.
2. A non-numeric `/game/abc` behaves exactly as today.

### Edge cases

- Old-format result (before playoffs): "Finished 3rd of 12", no 🏆.
- No `pickFinishes` (pre-065 result): no squares in image or description.
- No draft score (no hindsight total): the score is left out, never shown as 0.
- Many picks (NFL rounds up to ~16-18): squares wrap or shrink to fit inside the image.
- Ties in the record ("10-3-1").
- The leagues site and the combined build: unchanged (their game route is `/history/<id>` and private).

## Requirements

- **FR-001** `/game/<digits>` on the Redraft site answers with `app-shell.html` whose `<title>`, description,
  `og:title`, `og:description`, `og:url`, `og:image` (+ width, height, type, alt), `twitter:title`,
  `twitter:description`, `twitter:image` (+alt) describe that game. `twitter:card` stays `summary_large_image`.
  The noindex robots meta stays. No user-agent sniffing: every client gets the same HTML.
- **FR-002** The words and the image are built by the backend from `HistoricalGameService.publicGame(id)`'s output
  only (the same object a stranger's page reads), and only from fields the reduced view keeps, in either mode.
- **FR-003** `GET /api/history/public/games/{id}/preview` returns `{title, description, imageAlt}` (JSON), 404 under
  the same rule as the game read. `GET /api/history/public/games/{id}/preview.png` returns the PNG, 404 likewise.
  Both answer with the lock ON without a session (LeagueLockMappingsIT).
- **FR-004** The PNG is cached per game id in a bounded in-memory cache (only COMPLETE games; content never changes),
  and answered with `Cache-Control: public, max-age=86400`. Render time and allocation per render are measured and
  written in verification.md.
- **FR-005** The web server's backend call has a hard timeout (≤1.5 s) and a size cap; any failure serves the plain
  shell. Successful previews are cached in the web process with a TTL and an entry cap.
- **FR-006** Every value put into the HTML is HTML-escaped; the game id in URLs is digits only.
- **FR-007** The security headers `serve.json` sends today are sent on the `/game/<id>` response too.
- **FR-008** Daily numbering: when the daily response carries `dailyNumber` (other session's work), the title is
  "Redraft daily #23 · 🏆 Champion". Until that is on main, a clearly marked seam passes null and the date form is
  used. No numbering of our own.
- **FR-009** Games stay noindex; nothing is added to the sitemap.

## Success Criteria

- **SC-001** A curl of `/game/<id>` with a bot user agent against the real built web server and real backend shows the
  per-game tags; an OG parser reads the same.
- **SC-002** The PNG opens and shows the finish, score and squares (looked at, not assumed).
- **SC-003** Reduced daily: the preview JSON, HTML and image text carry no player name or rank (asserted in an IT).
- **SC-004** Backend down: `/game/<id>` still 200 with the generic shell, within the timeout plus a margin.

## Assumptions

- The Railway backend image (`eclipse-temurin:21-jre-jammy`) has at least one sans-serif font via fontconfig
  (DejaVu). To be checked in that image, not assumed; if it has none the renderer must still produce a PNG.
- Railway exposes `VITE_API_BASE` at build time; the web server takes its backend origin from a file the build writes,
  not from runtime env, so the two cannot drift.
