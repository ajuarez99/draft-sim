# Research: 074 Redraft SEO

Each entry says whether it was **read** (from this repo's code), **measured** (run), **searched** (web results read on 2026-11-17) or is **judgment**.

## R1. The name

- **Decision**: "Redraft", full form "Ball Knowers Redraft", always paired with a year or "past season".
- **Rationale** (searched, then judgment): result pages for this idea use "redraft"/"re-draft", "perfect draft" and "hindsight draft". "Time Machine" returned nothing relevant. "Perfect Draft" is FantasyPros' product name.
- **Not measured**: search volume for any of these. No keyword tool was used.
- **Alternatives**: keep "Time Machine" (no search demand found); "Hindsight Draft" (used only in one article series); "Perfect Draft" (taken).

## R2. Where static pages come from

- **Decision**: fetch season summaries from the backend during `vite build`; fail the build if they cannot be had; allow a fixture file through `REDRAFT_SEASON_DATA`.
- **Rationale** (judgment): the data lives only in the production database (the local 5433 cluster is not a full import). Fetching keeps pages equal to what the game computes.
- **Cost, stated**: the web build now depends on the backend being up and already carrying the new endpoint. First deploy order is backend, then web. Written into DEPLOY.md.
- **Alternatives**: commit generated JSON to the repo (hermetic builds, but silently stale after a re-import, and someone must remember to regenerate); server-side rendering (a new runtime for 75 pages that change only on re-import); a prerender service (outside dependency, and can fail silently).

## R3. What a season page can honestly show

- **Read**: the ranked part of the board is deterministic per season; the unranked tail is shuffled by seed (`HistoricalBoardBuilder.java:174`). Season value per player needs no game (`SeasonReplay.java:95,127`). The game's hindsight roster needs a specific draft (`Comparisons.hindsight`).
- **Decision**: "going in" = position on the game's board among ranked players, described on the page as "our pre-season ranking, built from the season before", never as ADP or draft position. Busts and steals skip unranked players and say how many. The roster shown is a "dream roster" (best-scoring roster that fits the lineup, ignoring draft order), not "hindsight-best".
- **Alternative rejected**: run one fixed bot draft per season and show its hindsight roster. It would print a roster that depends on an arbitrary slot and seed as if it were a fact about the season.

## R4. Real 404 from a static host

- **Read**: the container runs `serve -s dist` (`web/Dockerfile`), which answers every path with `index.html`.
- **Decision**: drop `-s`; generate `serve.site.json` per site with explicit rewrites for app paths; ship `404.html`.
- **Unverified**: serve 14.2.6's behaviour for `404.html`, `cleanUrls` with a file and a directory of the same stem, and `**` rewrite equivalence with `-s`. From memory of serve-handler's documentation, not from running it. First build task runs it.
- **Fallback**: a small Node static server in the repo if serve cannot do (a) or (b).

### R4, amended 2026-11-17 after measuring (serve@14.2.6 via npx, Windows, scratch `dist`)

The three unverified claims were run. Results:

- **(a) holds.** Unknown paths (`/xyz`, `/xyz/abc`, `/nba/1900`) answer 404 with `404.html`'s body.
- **(b) holds with `trailingSlash: false`.** `/nba` (from `nba/index.html`), `/nba/2016-17`, `/daily` answer 200 with no redirect; the `/` and `.html` forms 301 to the slashless form. With the key absent both forms answer 200 (duplicates), so the key is required. Canonical, sitemap and links use the slashless form.
- **(c) holds.** A lone `**` rewrite matched `serve -s` on status, content type and body for every path tried, including real files (`robots.txt`, `sitemap.xml`).
- **The plan was wrong about one thing:** a `headers` rule is matched against the *rewritten* file, not the requested URL. `X-Robots-Tag` on `source: "/game/**"` never appears; on `/index.html` it would also mark the home page noindex. Measured fix: rewrite the app-only paths to a byte copy of `index.html` (`app-shell.html`) and put the header on that file.
- **Two side effects, accepted:** `/404` and `/app-shell` answer 200 as ordinary pages. Both carry `noindex` (meta tag in `404.html`; header on the shell) and neither is linked or in the sitemap.
- **Not measured:** whether the 404 response carries the security headers; `Cache-Control`. Checked in live verification.
- The Node-server fallback is not needed.

## R5. Guest identity

- **Read**: identity today is an unauthenticated header; anyone who types a Sleeper username is that user (`web/src/user.ts` comment, "Identity, not auth").
- **Decision**: a guest is a browser-generated UUID in `X-Guest-Id`, stored as `guest:<uuid>` in the same owner column. Server-enforced allowance. Claim on sign-in.
- **Stated limit**: clearing the browser gives a new guest and a new allowance. Nothing here is abuse-proof, and nothing was before.
- **Forward fit**: spec 075 (email sign-in link) adds a third owner namespace and reuses `claim`.
- **Alternative rejected**: cookie session issued by the server (the Time Machine site is cookie-free by stated policy, spec 052).

## R6. Shared result addresses

- **Decision**: public read of a finished game by its numeric id, with no owner in the payload.
- **Stated property**: ids are sequential, so finished games are enumerable. They contain a season, picks and scores, and no identity.
- **Alternative**: a random share key per game (a column, a longer URL, and old shared links stop working). Not built; cheap to add later if enumeration turns out to matter.

## R7. Season address form

- **Decision**: the season label: `/nba/2016-17`, `/nfl/2007`.
- **Rationale** (judgment): basketball seasons are named by two years in the articles the search turned up; a bare `/nba/2016` is ambiguous between 2015-16 and 2016-17.

## R8. Things Allan owns (cannot be done from the repo)

- Make `ballknowers.co` → `www` a permanent (301) redirect at the registrar. Measured today: 302.
- Verify the site in Google Search Console and Bing Webmaster Tools; submit `sitemap.xml`.
- After two to four weeks: pages indexed (Search Console), visits to `/nba/*` and `/nfl/*` (existing anonymous page counts).
