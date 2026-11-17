# Feature Specification: Rename Time Machine to Redraft and make it findable in search

**Feature Branch**: `074-redraft-seo`

**Created**: 2026-11-17

**Status**: Draft

**Input**: Allan, 2026-11-17: "now that separation is done lets optimize seo for timemachine. anything is game lets research and figure this out name change is also ideal me thinks but can keep". After the research below he chose the name "Ball Knowers Redraft" and asked for this spec.

## What is there now

Measured 2026-11-17 by fetching `www.ballknowers.co` as a crawler (curl, Googlebot user agent), and read from `origin/main` 81c780e. Nothing here was run locally.

- **The basics are in place.** The delivered HTML carries a title ("Ball Knowers Time Machine: Redraft a Real NBA or NFL Season"), description, canonical, og/twitter tags, JSON-LD and a prerendered landing (`web/src/landingHtml.ts`, `web/src/tmLandingCopy.ts`, the plugin in `web/vite.config.ts`).
- **One indexable address.** `sitemap.xml` lists only the root (`web/src/siteFiles.ts:15-23`).
- **Unknown paths answer 200.** `/some-nonexistent-path-xyz` returned HTTP 200 with the landing page and the root's canonical.
- **A sign-in wall.** The landing asks for a Sleeper username before anything can be played. Every game route needs `X-Sleeper-User` and answers 401 without it (`backend/.../api/HistoricalGameController.java:32,187`), including the list of seasons (`:63-70`) and a finished game's result (`:104-108`).
- **A game cannot be started for a chosen season.** `CreateRequest(sport, userSlot)` (`HistoricalGameController.java:78`) draws the season at random; the only other way in is the game of the day (`:160`).
- **Share text has no search word in it.** It reads "Time Machine · 2016 Football" plus a link to the site root (`web/src/historicalShare.ts:47-58`).
- **The apex address redirects with a 302.** `ballknowers.co` answers a temporary redirect to `www`, served by the registrar, not by this codebase.
- **"Time Machine" appears in 61 lines of non-test web source** across 28 files, plus the og image (`web/public/og-image-tm.png`).

## Research behind the name and the page plan

Search volumes were **not measured**: no keyword tool was used. Everything in this section is judgment from reading search result pages on 2026-11-17, and should be read as that.

- The words in use for this idea are "redraft" (also "re-draft"), "perfect draft" and "hindsight draft". "Time Machine" turned up nothing for this kind of game.
- Football: FantasyPros' Perfect Draft Challenge is the known product. From its own pages it is NFL only, one season a year, run as a contest. A small app (FantaSim Football) offers past-season drafts with rotating free seasons.
- Basketball: no playable past-season fantasy draft game was found. Results were articles ("2016 NBA Re-Draft: Fantasy Edition") and all-time-team drafting games.
- "Redraft" has a second meaning in fantasy football (a league with no keepers). The name is therefore always paired with a year or with "past season" wherever a searcher or crawler reads it.
- "Perfect Draft" is avoided: it is another product's name.

## Clarifications

### Session 2026-11-17 (Allan, answered in two batches)

- Q1, may a visitor play without a Sleeper username? → Yes: "this is where anyone can play and then you can make an account to keep playing any year or get acheivements / save history."
- Q1a, what is an account? → **An email sign-in link.** That needs an outside email-sending service and is its own spec after this one (planned as 075). Until it ships, the existing Sleeper username is what unlocks the account-only things; this spec builds guest play so a guest's games can be attached to an account later.
- Q1b, what may a guest do? → Play the game of the day every day, and one draft started from a season page. Choosing more seasons, achievements, streaks and saved history need an account.
- Q2, what does "Draft this season" do? → It starts a draft of that exact season. The outcome sections of the season page sit folded under a spoiler heading (still in the delivered HTML). Chosen-season games are marked as such and do not count toward achievements or the daily streak.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A fan searching for a past season finds a page about it (Priority: P1)

Someone searches for a past fantasy season ("2016 fantasy basketball redraft", "2007 fantasy football redraft"). A page for that season on this site can appear in the results, because it exists at its own address with real content in the delivered HTML: who finished on top, who busted and who was a steal against where they went in, and the best roster hindsight could have drafted. From it they can start a draft.

**Why this priority**: One page can rank for about one phrase. Season pages are what gives the site something to be found for, in the sport where no rival product was found.

**Independent Test**: Fetch a season page without running scripts and read its HTML: title, description, canonical, the season's content and links to other seasons are all there. Open it in a browser signed out and it renders the same content.

**Acceptance Scenarios**:

1. **Given** a playable season in either sport, **When** its address is fetched without running scripts, **Then** the HTML contains that season's top finishers, biggest busts, biggest steals and hindsight-best roster, a title and description naming the year, the sport and "redraft", a canonical pointing at itself, link-preview tags and structured data.
2. **Given** a season page, **When** a visitor reads a bust or a steal, **Then** each one states both numbers it rests on (where the player ranked going in, and where they finished) and names what "going in" means on the page.
3. **Given** a sport index page, **When** it is fetched without running scripts, **Then** it links every playable season of that sport with ordinary links, and each season page links back to it and to the neighbouring seasons.
4. **Given** a season the game does not offer, **When** its address is requested, **Then** the answer is a real not-found (US4), not an empty season page.
5. **Given** a season with a known data gap (a stat not counted that year, thin coverage), **When** its page is read, **Then** the same caveat the game shows for that season is on the page.
6. **Given** a visitor on a season page, **When** they press "Draft this season", **Then** a draft of that exact season starts (as a guest if they have no sign-in and have not used their one guest season draft; otherwise they are asked to sign in first).
7. **Given** a season page in a browser, **When** it first shows, **Then** the outcome sections (top finishers, busts, steals, hindsight roster) are folded under a heading that warns they give the season away, and open on one press; the same content is in the delivered HTML either way.

---

### User Story 2 - The game is called Redraft everywhere a person or crawler reads it (Priority: P1)

The site's title, description, link preview, preview image, structured data, front door, in-game wording and share text all say "Redraft" and say what the game is: drafting a past NBA or NFL season. Nothing a player can read still says "Time Machine".

**Why this priority**: The name is the keyword. It ships with the season pages so the two do not disagree.

**Independent Test**: Search the built site's delivered HTML and every screen of a full game (front door, home, room, results, share text, achievements, account menu, not-found) for "Time Machine": zero hits. Read the title and description: both contain "Redraft" with "past" or a year.

**Acceptance Scenarios**:

1. **Given** the home page HTML, **When** its title, description, og/twitter tags, structured data and preview image are read, **Then** each says "Ball Knowers Redraft" and describes drafting a past NBA or NFL season.
2. **Given** a finished game, **When** the share text is produced, **Then** it names "Redraft", the season and the sport, and its link goes to that game's own result address (US3), not the site root.
3. **Given** any screen on the Redraft site, **When** it is read, **Then** "Time Machine" does not appear, and no league wording appears (the existing guard still passes).
4. **Given** an old link (`/history`, `/history/<id>`, `/game/<id>`), **When** it is opened, **Then** it still lands on the same thing. Addresses do not change in this feature.

---

### User Story 3 - A shared result lands on content, and anyone can play (Priority: P2)

A friend taps a shared result. They see that finished game (the season, the finish, the draft score, the picks) without signing in, with a clear way to play today's game. They can play it at once as a guest, with no username. A guest gets the game of the day every day and one draft started from a season page; picking more seasons, achievements, streaks and history that outlasts the browser need signing in.

**Why this priority**: Shared links are the only source of inbound links this game has. Today they open a username prompt.

**Independent Test**: Open a finished game's address in a private window: the result shows. Open an unfinished game's address the same way: nothing about it is shown.

**Acceptance Scenarios**:

1. **Given** a finished game, **When** its address is opened with no identity, **Then** the result page shows read-only, with no owner shown. *(Amended 2026-11-17 by the plan: was "owner shown by display name only". The server holds no display name, and a guest has none.)*
2. **Given** a game that is still drafting or was abandoned, **When** its address is opened by anyone but its owner, **Then** no picks or state are shown.
3. **Given** a visitor with no sign-in, **When** they choose to play the game of the day, **Then** they reach a draft in at most two steps without typing anything.
5. **Given** a guest who has used their one season-page draft, **When** they try to start another chosen-season or random-season game, **Then** they are told what signing in adds and offered it; the game of the day stays open to them.
6. **Given** a guest with games in this browser, **When** they sign in, **Then** those games become part of that identity's history. A chosen-season game never earns achievements, before or after.
7. **Given** a guest, **When** they finish the game of the day, **Then** they see their result and can share it, and are shown (not forced into) what signing in would keep.
4. **Given** a shared result page, **When** a search crawler reads it, **Then** it is asked not to index it (individual games are not search content; the season pages are).

---

### User Story 4 - A daily page and honest answers for crawlers (Priority: P2)

There is a stable `/daily` address for the game of the day. Unknown addresses answer not-found. The sitemap lists every indexable page.

**Why this priority**: Cheap, and the season pages are worth less while unknown addresses answer 200 with the home page.

**Independent Test**: Request `/daily`, a made-up address, an unoffered season and `sitemap.xml` without running scripts, and read status and body.

**Acceptance Scenarios**:

1. **Given** `/daily`, **When** it is fetched without running scripts, **Then** the HTML explains the game of the day and links to play it. It does not name today's season unless the game itself shows it before the draft starts.
2. **Given** `/daily` in a browser, **When** a visitor opens it, **Then** they see today's game for each sport, the time until the next one, and yesterday's season with a link to its season page.
3. **Given** an address that is not a page (made-up path, unoffered season, unknown sport), **When** it is requested, **Then** the HTTP status is 404 and the body is a not-found page with links home.
4. **Given** `sitemap.xml`, **When** it is read, **Then** it lists the root, `/daily`, both sport indexes and every season page, and nothing that is not indexable (no game addresses, nothing from the league site).
5. **Given** the league site, **When** this feature ships, **Then** it is unchanged: still locked, still asking crawlers to stay out, no sitemap.

---

### Edge Cases

- A season is added to or dropped from the offered list after a build: the season pages and sitemap follow the offered list at the next build; a dropped season's address answers 404.
- The backend is unreachable when the site is built: the build fails loudly rather than publishing a site with no season pages or an empty sitemap.
- A season whose prior-year data is thin (first offered year, rookies with no prior line): busts and steals leave out players with no "going in" rank rather than inventing one, and the page says how many were left out.
- NBA season labels span two years ("2016-17"): one address per season, one consistent rule for which year names it, stated on the sport index.
- Two players tie on a ranking used by the page: order is deterministic so the page does not change between builds.
- A shared result for a game played under the older results format: shows what that format has, as the signed-in page does today.
- A visitor with scripts disabled: season pages, sport indexes, `/daily` and the home page are all readable.
- The game of the day rolls over at midnight ET while `/daily` is open: the page updates or says a new one is ready; it never shows yesterday's as today's.

## Requirements *(mandatory)*

### Functional Requirements

**Name**

- **FR-001**: Every place a player or crawler reads the game's name MUST say "Redraft" (full form "Ball Knowers Redraft"). "Time Machine" MUST NOT appear in any delivered HTML, screen, share text or preview image on the Redraft site.
- **FR-002**: Wherever the name is read without surrounding context (title, description, preview tags, structured data, share text, page headings on indexable pages) it MUST be paired with a year or with words meaning "past season".
- **FR-003**: Existing addresses (`/`, `/game/<id>`, and the forwards from `/history`) MUST keep working unchanged. The domain does not change.
- **FR-004**: The rule that no league wording appears on Redraft screens MUST still hold and still be enforced by a test, extended to the new pages.

**Season pages**

- **FR-005**: There MUST be one indexable page per playable season per sport, and one index page per sport, each at a stable, readable address containing the sport and the year.
- **FR-006**: Each season page's delivered HTML (before any script runs) MUST contain: the season and sport; the top finishers; the biggest busts and biggest steals; the dream roster (a greedy pick of the best-scoring roster that fits the lineup, ignoring draft order, labelled as that; *wording amended 2026-11-17 after review, PR-14: greedy is not proven highest-scoring*); any data caveat the game shows for that season; a control to draft; links to the sport index and neighbouring seasons.
- **FR-006a**: *(Added 2026-11-17 by the plan.)* The roster on a season page MUST NOT be called "hindsight": the game uses that word for a roster that depends on one specific draft, which a season page does not have.
- **FR-006b**: *(Added 2026-11-17 after review, PR-06 and PR-13.)* A season page MUST say what its "points" are in that sport's scoring where the first such number appears (basketball's are not a usual season fantasy total), MUST show weeks played on each bust, and MUST say that rookies' pre-season ranks come from draft position and that players who missed the whole season are not listed.
- **FR-007**: Every bust and steal MUST show the two numbers it compares and the page MUST state what the "going in" order is. A page MUST NOT present that order as a real historical draft position unless it is one.
- **FR-008**: Numbers on a season page MUST agree with what the game computes for that season under the same scoring; the page MUST state the scoring and roster shape they assume.
- **FR-009**: Each season page and sport index MUST carry its own title, description, canonical, link-preview tags and structured data. No two indexable pages share a title or a description.
- **FR-010**: Season pages MUST be reachable from the home page through ordinary links within two steps.
- **FR-011**: "Draft this season" MUST start a draft of that exact season. A game started this way MUST be marked as a chosen-season game wherever it is listed or shared, and MUST NOT count toward achievements, the daily streak or any best/average draft score shown as a record.
- **FR-011a**: In a browser, a season page's outcome sections MUST start folded under a heading that says they give the season away, and MUST be in the delivered HTML regardless.

**Daily page, not-found, sitemap**

- **FR-012**: `/daily` MUST exist as an indexable page with its own title and description, explaining the game of the day in its delivered HTML, and MUST NOT reveal anything about today's game that the game hides before the draft.
- **FR-013**: Any address that is not a page MUST answer HTTP 404 with a not-found body. Addresses that are pages (including game addresses, which depend on data) keep answering as they do now.
- **FR-014**: `sitemap.xml` MUST list every indexable page of the Redraft site and nothing else, and MUST be produced from the same list of seasons the pages are.
- **FR-015**: If the season list or a season's content cannot be obtained when the site is built, the build MUST fail with a message naming what was missing.
- **FR-016**: The league site's lock, robots answer and absence of a sitemap MUST be unchanged.

**Shared results and sign-in**

- **FR-017**: A finished game's result MUST be viewable read-only at its address by anyone, showing nothing about its owner. *(Amended 2026-11-17 by the plan, as above.)*
- **FR-017a**: *(Added 2026-11-17 after review, PR-07.)* The game of the day is the same season and draft for everyone, so a shared result of **today's** game MUST show only the finish, record, draft score and pick squares, and say the rest shows tomorrow. From the next day it shows in full.
- **FR-018**: A game that is not finished MUST NOT be readable by anyone but its owner.
- **FR-019**: Result pages MUST ask crawlers not to index them, and their link preview MUST be the site's general one, carrying no per-user data. *(Amended 2026-11-17 after review, PR-23: was "describe the game generally (name, season, sport)". A static host cannot put one game's season in its preview tags.)*
- **FR-020**: Share text MUST name Redraft, the season and the sport, and link to the game's own result address.
- **FR-021**: A visitor with no sign-in MUST be able to play as a guest without typing anything: the game of the day for each sport, every day, and one chosen-season draft started from a season page.
- **FR-021a**: Starting any other game (a second chosen season, a random season), earning achievements, keeping a daily streak and seeing history beyond this browser MUST require signing in. When a guest reaches one of these, the screen MUST say what signing in adds, and MUST NOT block the game of the day.
- **FR-021b**: In this feature "signing in" is the existing Sleeper username, and screens MUST describe it as that, not as an account with a password or email. The email sign-in link Allan chose is a later spec; guest identity MUST be built so a guest's games can be attached to whatever a visitor signs in with.
- **FR-021c**: When a guest signs in, the finished and in-progress games from that browser MUST move to the signed-in identity, once, with no game duplicated. *(Amended 2026-11-17 by the plan: a game of the day that the signed-in identity already played for the same sport and day cannot move, since there is one per person per day; it is left behind, counted, and the screen says so.)*
- **FR-021d**: A guest MUST NOT be able to read or act on another guest's or player's unfinished game, and the guest allowance MUST be enforced by the server, not only by the screen. An abandoned season draft still uses the allowance. *(Last sentence added 2026-11-17 after review, PR-16; the session's default, Allan can overrule.)* It is a courtesy limit (a cleared browser gets a fresh allowance); the spec does not claim it is abuse-proof.
- **FR-021e**: The front door MUST lead with playing, not with a username field.

**Honesty**

- **FR-022**: This spec, the plan and any copy written from them MUST NOT state a search volume, a ranking or an expected traffic gain as fact. Keyword choices are labelled as judgment.
- **FR-023**: The manual steps this feature cannot do (apex redirect made permanent at the registrar; Search Console and Bing verification and sitemap submission) MUST be written up as owed steps for Allan, with what to check afterwards.

### Key Entities

- **Season page**: one playable season of one sport, as a public summary: top finishers, busts, steals, hindsight roster, caveats. Derived from data the game already holds; belongs to no player.
- **Going-in order**: the order players are ranked in before a season's draft (what the game's draft board shows), which busts and steals are measured against.
- **Guest**: a visitor with no sign-in, known only by an identity kept in their browser. Has an allowance (daily games; one chosen-season draft) and games that can later be attached to a sign-in.
- **Chosen-season game**: a game whose season the player picked from a season page, as opposed to a random draw or the game of the day. Marked; earns no achievements or streak.
- **Shared result**: a finished game seen by someone who is not its owner. Read-only.
- **Indexable page list**: the root, `/daily`, sport indexes and season pages. The single source for the sitemap and for what answers 200.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Fetched without running scripts, 100% of season pages, both sport indexes, `/daily` and the home page return their own title, description, canonical and main content.
- **SC-002**: The sitemap lists exactly the indexable pages: every offered season in both sports, the two indexes, `/daily` and the root; a check comparing it against the offered seasons finds no extra and no missing address.
- **SC-003**: A made-up address, an unoffered season and an unknown sport each answer 404.
- **SC-004**: A search of the built Redraft site's files and of every screen in one full game finds "Time Machine" zero times.
- **SC-005**: For three seasons per sport checked by hand (earliest, latest, one in the middle), each listed player's season points on the season page match the points a game of that season reports for that player, and each top-by-position rank matches the game's positional finish rank. *(Amended 2026-11-17 after review, PR-15: was "every number matches the game's own result". No game reports an overall finish rank, so the overall ranks are pinned by the page's own tests instead.)*
- **SC-006**: A finished game's address opened in a private window shows the result; an unfinished game's address opened the same way shows none of its picks.
- **SC-007**: From the home page, a shared result or a season page, a visitor with no sign-in reaches a pick-making draft screen in at most two steps and without typing.
- **SC-009**: A guest who has used the season-page draft is refused a second non-daily game by the server (checked by calling it directly), and can still start the game of the day.
- **SC-010**: A guest's games all appear, once each, in the history of the identity they then sign in with.
- **SC-008**: The league site answers exactly as it did before this feature for its root, its robots file and its sitemap address.

Not a success criterion, on purpose: search ranking or traffic. Those take weeks, depend on things outside this change, and were not baselined. What can be checked afterwards (pages indexed in Search Console, visits to season pages in the existing anonymous page counts) is listed with the owed manual steps.

## Assumptions

- "Playable season" means the seasons the game offers today for each sport; this feature adds none.
- Season page content is built when the site is built, so it changes only when the site is rebuilt. The underlying seasons are finished and their data changes only on a re-import.
- The going-in order is the game's own prior-season draft board order, not real-world ADP, which this project does not hold for past years. Pages say so.
- Individual game results are not search content: they are shareable but not indexed.
- Addresses stay in English with the sport first (`/nba/2016`, `/nfl/2007`); the exact form for basketball's two-year seasons is a plan decision.
- No new analytics: the existing anonymous page counts cover the new pages.
- Out of scope: changing the domain; anything on the league site; paid keyword tools; articles or a blog; email sign-in accounts (Allan's choice for what an account is; its own spec next, since it needs an outside email service); passwords; the registrar-side redirect and search-engine verification (documented as owed, FR-023).
