# Feature Specification: Fantasy-first landing page and share preview

**Feature Branch**: `050-fantasy-landing-page`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "we need to update the landing page and shared link to move off of monte carlo and more of a fantasy page to better showcase what we have built out now. research common seo strats and other fantasy sites that we know about to change this landing page"

## Context: what is there now (measured 2026-10-26)

- **The landing page is the sign-in screen.** A signed-out visitor at any URL sees one
  kicker ("Ball Knowers"), one headline ("Your league's real managers, simulated. See
  who's likely gone before you pick."), a Sleeper-username field and a privacy note.
  Nothing else on the page names any feature built since the draft simulator.
- **Every shared link previews the same way.** The search description and the
  social-preview description, on every URL of the site, read: "A fantasy draft
  simulator that fits manager tendencies from real Sleeper league history and runs
  Monte Carlo drafts." There is no preview image, and the preview card is the small
  variant.
- **A crawler that does not run scripts sees an empty page.** The page as first
  delivered has a title, the description above and an empty body; the headline and
  form only appear after the app's script runs.
- **`/robots.txt` and `/sitemap.xml` do not exist.** Both answer with the app's own
  page and a success status, so a search engine reading them gets HTML, not a robots
  file or a sitemap.
- **The bare domain redirects temporarily.** `ballknowers.co` answers with a temporary
  (302) redirect to `www.ballknowers.co`, issued by the domain registrar's forwarding,
  not by this app.
- **What the product actually is now.** Since the draft simulator, the site has grown
  into a whole-season league companion for NFL and NBA: power rankings, team strength,
  luck and bench points, playoff odds, matchups and awards, a weekly pot, rivalries,
  standings, league history and hall of fame, manager tendencies and head-to-head
  comparison, draft grades, trade verdicts, player stats, trends, comparison and a
  stat calculator, an NBA schedule grid, ADP and auction prices, and live draft-day
  tools (follow a live draft, mock drafts against your real league-mates). Sleeper
  leagues are fully supported. ESPN leagues get only a subset (standings, power
  rankings, team strength, bench points, rivalries, ADP), need ESPN login cookies to
  add, and their production happy path is unverified. *Amended after review
  2026-10-26 (plan-review F1): this paragraph first said ESPN was "supported for season
  pages", which overstated it.* Six features are NBA-only: schedule grid, trends,
  player pages, compare players, player stats, ADP & prices (plan-review F2).

## Research summary

Fantasy companion sites Allan has named as references (FantasyPros, ffwrapped,
DraftSharks), read 2026-10-26:

- **ffwrapped** leads with the league, not a method: a one-line headline about "your
  league's insights in one place", a subheadline that lists the season features by
  name and the platforms supported, one primary call to action, then a section per
  feature group, then a link list of its other tools.
- **DraftSharks** leads with an outcome ("Championships are won in-season"), shows the
  supported platforms as a row, splits tools into *draft season* and *in season*, and
  leans hard on social proof (accuracy awards, "1 million+ leagues synced").
- **FantasyPros** sells breadth by sport and by tool category.

Common search and share-preview practice for a single-page app (general guidance,
mostly practitioner and vendor sources, not platform documentation):

- Crawlers and link-preview bots generally do not run scripts, so the title,
  description, preview tags and the page's core text must be present in the page as
  first delivered.
- One preview image at roughly 1200×630 in a widely accepted format, referenced by an
  absolute URL, with its dimensions declared, renders acceptably across X, Discord,
  Slack, iMessage, LinkedIn and Facebook; keep key content centered because some apps
  crop it.
- A canonical URL, a real `robots.txt` and a sitemap of the public pages are baseline
  hygiene; a permanent (301) redirect is preferred over a temporary one between the
  bare and `www` hosts.
- Descriptive, keyword-bearing titles and one clear top heading per page ("fantasy
  football", "fantasy basketball", "Sleeper league", "power rankings", "playoff odds",
  "mock draft") are what match how fans actually search.

What this project will **not** borrow: unverifiable social proof. DraftSharks-style
accuracy claims and usage counts are exactly the kind of confidence this project has
refused to manufacture (AGENTS.md, "The thing to understand before changing
anything"). The landing page states what the tools do, not how good they are.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A fan lands on the site and understands it is a league companion (Priority: P1)

A fantasy player follows a link from a league-mate's group chat to the site. Before
signing in, they see what Ball Knowers does for their league across the whole season,
for football and basketball, with the draft tools as one part of it, and they can
start with their Sleeper username without leaving the page.

**Why this priority**: The landing page is the first and only screen every new visitor
sees, and today it describes a fraction of the product in terms (simulation) that
don't match why most fans would come.

**Independent Test**: Sign out, open the site root on a desktop and a phone-width
screen, and check that the page names the season features and both sports, that the
username field still works end to end, and that nothing on it claims accuracy or usage
the project hasn't measured.

**Acceptance Scenarios**:

1. **Given** a signed-out visitor, **When** they open the site root, **Then** the first
   screen shows a fantasy-first headline, a one-line subheadline naming the league and
   season value, the supported sports, and the username field as the one primary action.
2. **Given** a signed-out visitor, **When** they scroll, **Then** they see the product's
   features grouped the way the app itself groups them (this week, the season, the
   draft, history), each with a plain-language one-line description.
3. **Given** a signed-out visitor, **When** they enter a valid Sleeper username or
   profile link, **Then** they are signed in exactly as today, with the same three
   distinct outcomes (found, not found, Sleeper unreachable).
4. **Given** a signed-out visitor on a phone-width screen, **When** the page loads,
   **Then** the headline and username field are visible without scrolling and nothing
   scrolls horizontally.
5. **Given** a signed-in visitor, **When** they open the site root, **Then** they see
   their leagues exactly as today; the landing page is for signed-out visitors only.

---

### User Story 2 - A shared link previews as a fantasy product (Priority: P1)

A league-mate pastes a Ball Knowers link into iMessage, Discord, Slack or X. The
preview shows a large image, a fantasy-first title and a description of the season
companion, not "runs Monte Carlo drafts".

**Why this priority**: Group-chat links are how this product actually spreads (it is
built around one league's members); the preview is the first impression for most
people who ever see it.

**Independent Test**: Fetch the site root and a deep league URL as a preview bot would
(no script execution) and check the title, description, image and card type; then
paste the link into at least one real messaging app and look at the card.

**Acceptance Scenarios**:

1. **Given** any URL on the site, **When** a link-preview bot fetches it, **Then** it
   receives a fantasy-first title, a description that names the season features and
   both sports, and an absolute URL to a large preview image with its dimensions.
2. **Given** the preview image, **When** it is shown cropped to a square or to 16:9,
   **Then** the product name and its one-line message are still fully visible.
3. **Given** a shared deep link to a specific league page, **When** it previews,
   **Then** it shows the same site-level preview and does not reveal the league's name,
   members or data.

---

### User Story 3 - Search engines can read and index the landing page (Priority: P2)

Someone searches for terms like "Sleeper league power rankings", "fantasy basketball
playoff odds" or "mock draft against my league". A search engine that has crawled the
site has real text to match: a descriptive title, a top heading, the feature list, a
canonical address, a robots file and a sitemap.

**Why this priority**: Valuable but slower-acting than the landing page and preview,
and the product is gated behind a username, so the indexable surface is mostly this
one page.

**Independent Test**: Fetch the root, `/robots.txt` and `/sitemap.xml` without running
scripts and check each answers with the right kind of content.

**Acceptance Scenarios**:

1. **Given** a crawler that does not run scripts, **When** it fetches the root, **Then**
   the delivered page already contains the top heading, the subheadline and the
   feature list as text.
2. **Given** a crawler, **When** it fetches `/robots.txt`, **Then** it receives a plain
   robots file that allows the public landing page and points at the sitemap.
3. **Given** a crawler, **When** it fetches `/sitemap.xml`, **Then** it receives a
   valid sitemap listing only public, signed-out pages (the root, plus any other public
   page this feature adds).
4. **Given** a crawler, **When** it fetches any page, **Then** the page declares the
   canonical `https://www.ballknowers.co/` address for the landing page.
5. **Given** a crawler, **When** it fetches a URL that only exists for signed-in users,
   **Then** it is not listed in the sitemap, and what it receives is the landing page
   with the root as canonical (no league content). It is *not* disallowed in robots,
   because preview bots that obey robots would then show no preview for deep links.
   *Amended after review 2026-10-26 (plan-review F3).*

---

### Edge Cases

- **Visitor with scripts disabled or a slow first load**: the delivered page shows the
  headline, subheadline and feature list as readable text even before (or without) the
  app starting; once the app starts it does not flash a different headline.
- **Sleeper unreachable during sign-in from the landing page**: same retry message as
  today; the landing content stays on screen around it.
- **Signed-out visitor follows a deep link**: they see the landing page (as today they
  see sign-in), and after signing in they land where today's flow puts them.
- **ESPN-only fan**: the page must not imply ESPN is a sign-in path or that ESPN leagues
  get the draft room or most season pages; anything said about ESPN matches what is
  actually supported (a subset, see Context).
- **Sport-specific features**: a feature that exists for only one sport is labelled
  with that sport, so an NFL fan isn't promised the NBA schedule grid.
- **A feature listed on the page is later removed or gated**: the feature list comes
  from one place the team can edit, so the page doesn't keep advertising something
  that's gone.
- **Preview caches**: messaging apps cache old previews; a link already shared before
  the change may keep showing the old card until that app refreshes it. This is
  expected, not a defect.
- **Light/dark**: the landing page follows the app's existing dark theme; it is not
  required to add a light theme.

## Requirements *(mandatory)*

### Functional Requirements

**Landing page (signed-out root)**

- **FR-001**: The signed-out root MUST present Ball Knowers as a fantasy football and
  fantasy basketball league companion for the whole season, with draft tools as one
  part of it. The words "Monte Carlo" and "simulation/simulated" MUST NOT appear in the
  headline, subheadline, page title, search description or share preview.
- **FR-002**: The first screen MUST contain: the product name, one headline, one
  subheadline, the supported sports (NFL and NBA), the supported league platform(s),
  and the existing username field and continue action as the single primary action.
- **FR-003**: Below the first screen, the page MUST list the product's features in the
  same groups the signed-in app uses (this week, the season, the draft, history), each
  with a name and a one-line plain-language description of what a fan gets from it.
- **FR-004**: The landing page MUST NOT state accuracy, win-rate, usage counts,
  testimonials or any other claim the project has not measured.
- **FR-005**: Any visual that illustrates a feature MUST NOT show a real league's
  members, names, avatars or results unless those people have agreed to it; an
  illustrative example MUST be labelled as an example.
- **FR-006**: The sign-in behaviour (accepted inputs, the three outcomes, the privacy
  note that no password is needed) MUST be unchanged.
- **FR-007**: Signed-in visitors MUST NOT see the landing page at the root; their
  existing home is unchanged.
- **FR-008**: The page MUST work at phone width (headline and username field visible
  without scrolling, no horizontal scrolling) and at desktop width, using the app's
  existing dark visual language (rounded cards, the existing accent colors).
- **FR-009**: The page MUST present the product as built for Sleeper leagues (sign-in
  is by Sleeper username). ESPN may be mentioned only as "some pages also work for ESPN
  leagues" or narrower; it MUST NOT be listed as a supported platform on equal footing.
  *Amended after review 2026-10-26 (plan-review F1).*
- **FR-009a**: Each listed feature that exists for only one sport MUST show that sport;
  features for both show nothing extra or both. *Added after review 2026-10-26 (F2).*

**Share preview**

- **FR-010**: Every URL on the site MUST deliver, without script execution, a
  fantasy-first title, a description of the season companion that names both sports,
  a large-image preview card, and an absolute URL to a preview image with its width and
  height declared.
- **FR-011**: The preview image MUST be about 1200×630, in a format every major
  messaging app accepts, with the product name and one-line message inside a centered
  safe area that survives a square or 16:9 crop.
- **FR-012**: A deep link to a league page MUST NOT expose that league's name, members
  or data in its preview (site-level preview only).

**Search basics**

- **FR-013**: The landing page as first delivered (before any script runs) MUST contain
  its top heading, subheadline and feature list as text, and the running app MUST show
  the same wording so the page does not change copy on load.
- **FR-014**: The site MUST serve a real robots file at `/robots.txt` and a valid
  sitemap at `/sitemap.xml`, each with the correct content type, instead of the app page.
- **FR-015**: The sitemap MUST list only public, signed-out pages. Signed-in-only routes
  MUST NOT be listed, and MUST deliver only the landing content with the root as
  canonical; robots MUST NOT disallow them (deep-link previews depend on preview bots
  being allowed to fetch them). *Amended after review 2026-10-26 (plan-review F3).*
- **FR-016**: The landing page MUST declare `https://www.ballknowers.co/` as its
  canonical address and carry a page title that names fantasy football, basketball and Sleeper leagues,
  ≤ ~65 characters so search results don't truncate it. *Amended after review
  2026-10-26 (F6): the plan's first title missed these and ran 75 characters.*
- **FR-017**: The landing page MUST carry structured data describing the site as a web
  application (name, description, sports/category, free to use), with no rating or
  review fields.
- **FR-018**: The landing copy (headline, subheadline, feature list) MUST be defined in
  one place shared by the delivered page and the running app, so they cannot drift.

### Key Entities

- **Landing copy**: headline, subheadline, supported sports and platforms, and the
  grouped feature list (group heading, feature name, one-line description). One source,
  used by both the page as first delivered and the running app.
- **Share preview**: title, description, image (with dimensions and alt text), card
  type, site name and canonical URL. Site-wide, not per league.
- **Public page index**: the list of public pages that the sitemap names and robots
  points to.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the signed-out root, the first screen at 390px wide and at 1440px wide
  shows the headline, both sports and the username field without scrolling (checked in
  a real browser at both widths).
- **SC-002**: Fetching the root and three deep URLs without running scripts returns, on
  all four, a title and description that contain neither "Monte Carlo" nor
  "simulat", a large-image card type, and a preview image URL that itself loads as an
  image of 1200×630.
- **SC-003**: Fetching the root without running scripts returns the top heading and
  every feature group heading as text.
- **SC-004**: `/robots.txt` returns plain text and `/sitemap.xml` returns XML, both
  with a success status, in production after deploy.
- **SC-005**: Pasting the production root URL into at least one real messaging app
  (iMessage, Discord or Slack) shows the large preview with the new title and image
  (screenshot recorded in the verification doc).
- **SC-006**: A first-time visitor shown the landing page for 10 seconds can name at
  least three things the site does besides the draft (checked informally with at least
  one league-mate; recorded as anecdotal, not a measured rate).
- **SC-007**: The sign-in flow's existing behaviour tests (inputs, three outcomes) pass
  unchanged; the tests that assert the old headline text (`SignIn.test.tsx`,
  `App.test.tsx`) are updated to the new headline and nothing else; a real sign-in from
  the new landing page reaches the signed-in home. *Amended after review 2026-10-26
  (F4): "unchanged" was false — four assertions pin the old headline.*
- **SC-008**: Every feature named on the landing page corresponds to an entry in the
  app's own navigation list, with the sports that entry declares; time-bound entries
  (follow a live draft) are described as draft-day tools. *Amended after review
  2026-10-26 (F2).*

## Assumptions

- **The landing page replaces the signed-out sign-in screen** rather than adding a
  separate marketing URL; the username field moves into its hero. One public page keeps
  the indexable surface and the sign-in path in the same place.
- **Site-level preview only.** Per-league or per-page previews (e.g. a power-rankings
  card for a specific league) are not built: they would need the server to know about
  leagues before sign-in and would expose league names to anyone holding a link. Named
  here as a possible later feature, not in scope.
- **No new public data pages.** Indexable public player pages, rankings or articles
  (the main way FantasyPros/DraftSharks earn search traffic) are out of scope; this
  feature makes the one public page good, it does not create a content site.
- **The preview image is a static, designed image** with no live data on it.
- **No accuracy, usage or testimonial content** on the page (project principle, not a
  temporary gap).
- **The bare-domain 302 is outside this app.** It is issued by the domain registrar's
  forwarding; switching it to a permanent redirect is a domain-settings change Allan
  makes, recorded in the plan as an operational step, not code.
- **Analytics are not added.** There is no measurement of visits or conversions today,
  so success criteria are checked by direct inspection, not traffic numbers.
- **The existing dark theme and fonts** are used; no new brand identity or logo work.
- **Copy is written in this feature** and reviewed by Allan at the end with the rest of
  the change; no separate copywriting stage.
