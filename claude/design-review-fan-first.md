# Design review: fan-first Ball Knowers

**Status:** planning doc. Nothing built. Written 2026-09-30.

**Brief (Allan):** go through every page as a designer. Make the site look less
engineery and more like something a fantasy fan opens to get an edge. Suggest
color, navigation, and whether fewer boxes would make it flow better.

**Design references** (also saved to memory as `reference_design_inspiration_sites`):
- FantasyPros QB rankings: https://www.fantasypros.com/nfl/rankings/qb.php
- ffwrapped standings: https://ffwrapped.com/?leagueId=1346366555759341568&view=standings
- DraftSharks rankings: https://www.draftsharks.com/rankings

**Branch:** `013-fan-first-redesign`, cut from `origin/main` at f684189.

## Clarifications

### Session 2026-09-30
- Q: Does this spec cover all three phases in §6, or Phase 1 only? → A: All three phases, in one spec on one branch.
- Q: How does the site decide who is the commissioner, for showing Compute/Recompute? → A: Sleeper's own flag. `is_owner` on `/league/{id}/users`, already ingested as `league_member.is_commissioner` (V9). Buttons show only when the signed-in user is a commissioner of *that* league. The server still requires the commissioner key (`X-Admin-Token`) to run the action, because sign-in is a typed username, not auth.
- Q: What screen sizes must the redesign work on and be checked at? → A: Desktop (1440×900), tablet (768×1024) and phone (375×812), each live-checked on its own. How the draft room's "Your pick" panel and the grouped menu adapt at tablet and phone is decided in the plan, not assumed from desktop.
- Q: Does every part of the redesign apply to NBA leagues as well as NFL? → A: Yes, both sports get every change and are checked separately. Measured 2026-09-30, all HTTP 200: headshots at `sleepercdn.com/content/{nfl|nba}/players/thumb/{sleeperPlayerId}.jpg`, team logos at `sleepercdn.com/images/team_logos/{nfl|nba}/{team}.png`. When an image is missing (a team defense, a player without a photo, a 403/404), show the team logo, then initials, never a broken image.
- Q: How are the letter grades on Team strength and Bench points decided? → A: By rank within the league, not by fixed score thresholds. The rank-to-grade cutoffs are a new hand-set constant in `config/weights.yml`, labelled arbitrary. While fewer than `SeasonSuperlativesService.EARLY_THRESHOLD_WEEKS` (4) weeks are scored, the grade carries the "early — this is mostly noise" badge. It reuses that one constant rather than a copy of it. The number is always shown beside the grade.

**How this was checked:** I opened every route below on production
(`www.ballknowers.co`, signed in as popsharky, 1440×900) on 2026-09-30, plus
the three reference sites. Screenshots of scrolled pages were unreliable in the
browser pane, so for long pages only the first screen was seen. Anything below
the fold is inferred from the code, and I say so where it matters.

---

## 1. What the reference sites do that we don't

| | FantasyPros | ffwrapped | DraftSharks | Ball Knowers today |
|---|---|---|---|---|
| Surfaces | White page, table sits right on it | Light page, one table, no nesting | White page, dark tier bands | Dark page > panel > card > pill, often 3 layers |
| Faces | Team in parens | Sleeper avatars | **Team logos** next to every player | Sleeper avatars for managers, **nothing for players** |
| Quick read | ★★★ matchup stars, **A+/B letter grades** | Blue = best in column, red = worst | Colored pills (SOS %, injury %), one **highlighted signature column** (3D Value) | Raw numbers, often monospace, rarely ranked visually |
| Grouping | Tiers | None needed | **TIER 1 / TIER 2 bands** | None |
| Nav | Icon rail + grouped, collapsible sub-menu | League switcher on top; grouped sidebar: Hub / League Analysis / My Team / History | Top bar + secondary tab bar | Flat list of 10 league links; collapses to unlabeled dots in draft views |
| Personal | "My Leagues" filter | **"Which team is yours?"** then a personal dashboard | "Use my league" scoring | We already *know* who you are, but no page leads with you |
| Copy | Says the result | Says the result | Marketing voice, bold | **Explains the method** in the subtitle of nearly every page |

The main takeaway: the references feel like fan tools because **the answer comes
first and the method is one click away**. Our pages usually put the method first.

---

## 2. Five problems that show up on every page

### 2.1 Boxes inside boxes
Home has panel > league card > pill row > pill. Superlatives has panel > 13
bordered cards, each with its own colored top border. The manager profile has
panel > stat cards > bordered rank rows. The page looks like a component
inventory, not a story.

**Rule to adopt:** a page gets **two surfaces**: the page background and one
raised surface. Tables sit **on the page** with hairline row dividers, the way
ffwrapped and FantasyPros do. A section title is plain text, not a panel header.
Use a card only when the thing really is card-shaped (a matchup, a player). This
extends the existing DENSITY rule in `styles.css` to the whole site.

### 2.2 The copy is written for the person who built it
These are real subtitles and empty states from the site:
- Forecast: "…the page reads a stored simulation rather than running a new one."
- Manager profile: "Every figure below comes from the same per-week optimal-lineup computation the Roster management page uses — never Sleeper's own stored season potential…"
- Managers: "…The shaded band is one standard error…"
- History: "What this app thinks about a draft (reach, value) lives on a manager's own history page, kept visually separate…"

The honesty rule in AGENTS.md stays. **Caveats move, they don't disappear:**
- Subtitle = the takeaway in one fan sentence ("Who's been lucky, who's been robbed.").
- Methodology goes behind a small **"How this works" ⓘ** disclosure at the bottom of the section it describes.
- A caveat that changes how to read a number stays **inline, as a badge** next to that number. The existing "early — this is mostly noise" tag on Superlatives already does this well.

### 2.3 Everything is the same weight
All-caps Oswald is used for the H1 *and* every section title *and* table headers.
Numbers are in a monospace face. Teal is the color for buttons, active nav,
chips, bars *and* links. Nothing stands out, so the eye has nowhere to land.

### 2.4 Players have no faces
We show Sleeper avatars for managers (a big improvement when it shipped). Players
are text only: "B. Robinson ATL". Sleeper serves player headshots and team logos
from the same CDN we already use for avatars, for **both sports** (URL
patterns measured, see Clarifications). DraftSharks' rankings table works
mostly because of the team logo next to each name.

### 2.5 "You" is never the headline
The app knows you're popsharky, knows your seat, and knows your team. Only Power
rankings leads with it (the "Your team" strip). Home, Weekly report, Superlatives
and the draft room treat you as one row among twelve. ffwrapped's first question
is "Which team is yours?" We can skip the question and just answer it.

---

## 3. Color

Keep the dark theme. Your memory note says you prefer a card/avatar dark UI, and
both DraftSharks' navy header and our own board read well dark. What changes is
**how many jobs each color has.**

| Token | Today | Proposed | Job (one only) |
|---|---|---|---|
| `--bg` | oklch(13% .02 250) | oklch(14% .025 255) | Page, and what tables sit on |
| `--panel` | oklch(18% .025 250) | oklch(19% .03 255) | The **one** raised surface |
| *(new)* `--row-hover` | — | oklch(22% .03 255) | Row hover instead of borders |
| `--crimson` | You | **unchanged** | You: your row, seat, pick, team |
| `--teal` | Buttons, nav, chips, bars, links | oklch(72% .14 175), **interaction only** | Focus, hover, active tab |
| *(new)* `--volt` | — | oklch(88% .19 125) (electric lime) with dark text | **Primary call to action only**: "Start the mock draft", "Make your pick". This is the sporty, "gain an edge" color. |
| *(new)* `--up` / `--down` | Green/red used on Expected wins only | oklch(74% .16 150) / oklch(66% .19 25) | Good vs bad, **site-wide**: best/worst in a column (ffwrapped), luck, risers/fallers, value vs ADP |
| `--fitted` (amber) | Fitted manager | unchanged | Also trophies and awards, since both mean "earned" |
| Position colors | 6 NFL + 5 NBA | unchanged | Already a solid system |

Also **tint data cells, not containers.** Add a subtle heat tint to the best and
worst values in each stat column (Analysis, Roster management, History PF/PA)
instead of putting each section in a box.

**Typography:**
- Oswald: page H1 and big hero numbers only (the "1" on Power, 24/168 in the draft).
- Section titles: Plus Jakarta 600, sentence case, ~15px.
- Numbers: Plus Jakarta with `font-variant-numeric: tabular-nums` instead of the
  monospace face. They'll still line up, and they'll stop looking like a terminal.
  This is the biggest single "less engineery" change for the least work.

---

## 4. Navigation

### Today
- League rail: 10 flat items (Draft board, History, Power rankings, Analysis,
  Roster management, Expected wins, Season forecast, Weekly report, Superlatives,
  Mock it). They're all the same weight, and it's not ordered by how often a fan
  uses them.
- In draft views the rail collapses to a column of **unlabeled dots**. Screen
  reading can't recover "which dot is Power rankings".
- Names describe the calculation, not what a fan gets from the page.

### Proposed: group by what the fan is doing (ffwrapped's model)
```
[ (Foot) Ball Knowers ▾  2026 ▾ ]        ← league + season switcher pinned at top

  League home                            ← NEW: your hub (§5.1)

  THIS WEEK
    Matchups & awards      (Weekly report)
    Power rankings

  THE SEASON
    Standings              (History, current season)
    Team strength          (Analysis)
    Luck                   (Expected wins)
    Bench points           (Roster management)
    Playoff odds           (Season forecast)
    Awards                 (Superlatives)

  DRAFT
    Draft room / board
    Mock draft
    Scouting report        (Managers — tendencies)

  HISTORY
    Past seasons
    Managers
```
- Keep URLs as they are. Only labels and grouping change, so links and memory
  notes stay valid.
- Groups are collapsible. The group holding the current page opens by default.
- In the draft room, collapse the rail to **icons with a hover label**, not dots,
  or hide it behind the hamburger. The board needs the width.
- Add a "you" shortcut at the top when a week is live: "Week 4 · You vs jpelwell →".

---

## 5. Page-by-page

Pages are in rough order of how often a fan hits them. **Observed** = seen on prod
today. **Proposal** = what to change.

### 5.1 Home `/`
**Observed:** a "Viewing ALL SPORTS" hero, then a panel of league cards. Each card
has a seasons pill row, three nav pills, a status line, Refresh and "Mock it". "From
Sleeper" and "Mock drafts" are each in their own panel. Four levels of nesting.
**Proposal:**
- Hero becomes **you**: avatar, "popsharky", and one line per active league,
  e.g. "(Foot) Ball Knowers · 2–1 · 5th · Week 4 vs jpelwell".
- Leagues become **rows** (logo, name, sport pill, your record/rank, one primary
  action), not cards with six controls. Season pills and Refresh move to the
  league's own page.
- Clicking a league opens the **new league home**: your team strip, this week's
  matchup, a standings snippet, Power's headline ("Master Bates is your new No. 1"),
  and the newest award. This is the page ffwrapped calls Dashboard, and it becomes
  the default landing after a league is picked.

### 5.2 Draft room / simulator `/drafts/:id` and mock `/mock/:id`
**Observed:** the board is great. It's the product. But the answer to "who should I
take?" is hidden behind a **"Make your pick" button that opens a modal**. The modal
list has name, rank and team need, but no availability %, no value and no headshot.
The sim route showed a blank panel for ~10 seconds before "Ready when you are",
with no skeleton or progress.
**Proposal:**
- A persistent **right-hand "Your pick" rail** in place of the modal whenever you're
  on the clock or next up: best available in **DraftSharks-style tiers**, each with
  a headshot, position rank, and an **"available at your next pick: 38%"** bar in
  `--up`/`--down`. The availability curve is our one-of-a-kind stat and it should be
  on screen by default.
- The "Your pick 2.12 · 24/168" header shrinks to a slim sticky bar. Put the big
  24/168 in Oswald and the "4 of the last 6 were PG" run alert as a volt callout.
  That alert is exactly the kind of edge fans want.
- Board cells: **drop the inner border** and let the position tint be the cell.
  Add a tiny team logo. Soften the empty future cells further so the filled rounds
  read as a front line.
- Loading: a skeleton board plus "Simulating 500 drafts…" so the wait feels like
  work, not a hang.

### 5.3 Completed draft board `/drafts/:id/board`
**Observed:** same grid. Readable. Rail is reduced to dots.
**Proposal:** add a "steals & reaches" toggle that tints cells `--up`/`--down` by
pick vs ADP. That's the fan question about a finished draft, and we already have
the reach data. Add team logos. Give the rail real icons.

### 5.4 Mock setup `/mock/new`
**Observed:** clean, but "You" is a crimson box and every other seat is a
bordered box with a dropdown under it.
**Proposal:** one row of seat chips on the page (no panel). A seat you fill with a
real manager shows their avatar and a one-word tendency ("Reacher", "QB early").
"Start the draft" in volt.

### 5.5 Power rankings `/leagues/:id/power`
**Observed:** the best page on the site. It has a real headline, "Your team"
strip, Riser/Free fall, and a ladder. It still has boxes around the hero, the
number-one card, Riser, Free fall and the ladder, plus five control groups
(Box score / Commissioner / League vote, Rank the league, Recompute).
**Proposal:** keep the headline and let it breathe with no box. Riser/Free fall
become two inline stat callouts with `--up`/`--down` arrows. The ladder sits on the
page. Commissioner-only controls (Recompute) go behind a "Commissioner" menu so a
regular fan sees one control: **Rank the league**.

### 5.6 Weekly report `/leagues/:id/weekly-report`
**Observed:** matchups are two-team boxes inside a panel. Awards are three cards.
Top performers is a list. The week input shows **2** while Power talks about week
3/4. *(Observed, not diagnosed. It may default to the last fully scored week. If
so, say "Week 2 (latest complete)".)*
**Proposal:** lead with **your matchup** at full width as a scoreboard (avatars,
big score, win/loss color). The other five matchups follow as a compact scoreboard
strip. Awards become a single "Week in review" list with an icon per award. Use a
week stepper (‹ Week 3 ›), not a number input.

### 5.7 Superlatives `/leagues/:id/superlatives`
**Observed:** 13 bordered cards in a 4-column grid, each with a different colored
top border, "See all", and expandable "Games". It's the most boxed page on the site.
**Proposal:** a **trophy shelf**: one list, one row per award (amber trophy icon,
award name, winner avatar + name, the stat in big tabular numbers, "See all"
expanding inline). The Jabari Smith Jr. and Joel Embiid awards get a one-line joke
subtitle. This is fan content and should sound like it.

### 5.8 Standings / History `/leagues/:id/history`
**Observed:** a good plain table, closest to ffwrapped. But "season in progress"
repeats in the Rank column for all 12 rows, and the 2025 table shows **"not
computed yet · Compute"** buttons to every visitor.
**Proposal:** drop the repeated text (one badge in the section title covers it).
Hide Compute from non-commissioners. Borrow ffwrapped's move of coloring the best
and worst values in each column. Add "Record vs all" and "Median record" columns
(ffwrapped has them, and we already compute expected wins).

### 5.9 Team strength / Analysis `/leagues/:id/analysis`
**Observed:** a composite 1–100 score pill with a raw figure under it, plus Record,
Avg, High, Low, all in monospace.
**Proposal:** add a **letter grade** (A+…F) next to the number, FantasyPros-style.
It's the fastest read a fan can get. The grade comes from rank within the league
(see Clarifications), and gets the "early" badge before week 4. Heat-tint High/Low. Move
"raw" into the ⓘ.

### 5.10 Bench points / Roster management
**Observed:** a good bar chart with a rainbow of bar colors by rank.
**Proposal:** bars in one color, with the **bench gap** as the story: "left 70.22 on
the bench" in `--down` for the worst three. Add an efficiency grade (rank-based, same rule as §5.9). The rainbow
encodes rank twice.

### 5.11 Luck / Expected wins
**Observed:** clear, and already uses green/red correctly.
**Proposal:** rename it to Luck, and add a subtitle like "Hunter? I Barkley Goedert
has stolen 1.27 wins". Otherwise leave it. It's the model for the rest.

### 5.12 Playoff odds / Season forecast
**Observed:** empty state says "No forecast for this league. … the page reads a
stored simulation rather than running one on load" with a **"Recompute through
week 2"** button shown to a regular visitor.
**Proposal:** fan empty state: "Playoff odds land after week N's games are
scored." The recompute button only appears for the commissioner.

### 5.13 Scouting report / Managers `/managers`
**Observed:** strong content (reach bars, QB early / TE late pills). But the lede
is three sentences about standard errors, and each row shows "(±8.3)".
**Proposal:** lead with archetype labels ("The Reacher", "Waits on QB", "Drafts like
the room"). The ± goes into the bar as the shaded band, which it already is, and
the number moves to hover. Methodology goes to ⓘ. Group by **your league** first.
"Who am I drafting against?" is the fan question.

### 5.14 Manager profile `/managers/:id/history`
**Observed:** the most engineery page. A long methodology paragraph, six stat
boxes, three bordered rank rows, and then more boxes.
**Proposal:** a **player-card header**: big avatar, name, career record, a titles
count with trophy icons, and 3–4 headline stats in one row (no boxes). League ranks
become a sentence ("3rd of 17 in lineup efficiency"). Draft tendencies from
/managers sit right under it. The paragraph goes into ⓘ.

### 5.15 Sign-in
**Observed:** a small box in the top-left of an empty dark page. It's the first
thing a new fan sees.
**Proposal:** a centered hero with the product name, one line of pitch ("Your
league's real managers, simulated. Know who's gone before you pick."), a large
username field and a volt Continue button. Keep the "no password" reassurance.

---

## 6. Order of work

All three phases are in scope for this spec (see Clarifications). They are still
built in this order, and each phase is live-verified before the next one starts.

**Phase 1: skin and copy, CSS and strings only, low risk**
1. Tokens (§3): `--volt`, `--up/--down`, surface consolidation.
2. Numbers from monospace to tabular Jakarta. Section titles to sentence case.
3. Flatten panels: tables on the page, section headers unboxed.
4. Copy pass: takeaway subtitles, methodology into ⓘ disclosures (caveats kept).
5. Hide commissioner controls (Compute / Recompute) unless the signed-in user is that league's Sleeper commissioner (`is_owner`, see Clarifications).

**Phase 2: navigation and the league home**
6. Grouped rail with fan labels and a pinned league/season switcher (§4).
7. League home dashboard (§5.1).
8. Your matchup first on Weekly report. Scoreboard strip.

**Phase 3: faces and edge features**
9. Player headshots and team logos, NFL and NBA (CDN paths measured, see Clarifications), with logo → initials fallback.
10. Draft room "Your pick" rail with tiers and an availability bar (§5.2).
11. Letter grades (Analysis, Bench points), rank-based with cutoffs in `weights.yml`. Steals/reaches toggle on boards.
12. Superlatives trophy shelf. Manager profile player card.

## 7. Explicitly not proposed
- **A light theme.** The references are light, but you prefer dark and the board
  reads better dark. We take their *structure*, not their palette.
- **Removing caveats.** Every caveat either stays inline as a badge or moves behind
  ⓘ. Nothing that makes the tool look more certain than it is.
- **New stats.** Every proposal above reads data already on the wire, except
  headshots/logos (new image URLs, no new data) and "Record vs all / Median record" (derivable
  from weekly scores we already ingest; not verified).

## 8. Acceptance for whoever builds it
- No page has more than two nested bordered surfaces (check the computed styles,
  not by eye).
- Every page subtitle is one sentence and contains no implementation noun
  ("computation", "stored", "simulation run", "standard error").
- A signed-in user who is not that league's Sleeper commissioner (`is_owner` false or absent) sees no Compute/Recompute control anywhere; popsharky on "(Foot) Ball Knowers" (measured `is_owner: true` on 2026-09-30) does. The server's key check is unchanged.
- In the draft room, availability for your next pick is visible without opening a modal.
- Letter grades: a test asserts **ordering** (the league's top-ranked team never
  gets a lower grade than a team below it, per lessons.md bug class 1), and one
  asserts the early badge shows at 3 scored weeks and is gone at 4.
- Every changed page is live-checked on an NFL league **and** an NBA league
  (see Clarifications), including headshots and the fallback for a missing one.
- Every changed page is live-checked at 1440×900, 768×1024 and 375×812 (see
  Clarifications): no horizontal page scroll, nothing cut off, and the draft
  room's "Your pick" panel reachable at each size.
- Live-verified in the browser on prod-like data, per this repo's bar. A green
  build is not enough.
