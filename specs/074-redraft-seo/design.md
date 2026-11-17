# Screen design: 074 Redraft public pages

From the ui-designer agent's proposal, 2026-11-17, condensed by the session (copy strings, class names and state rules kept as proposed). Nothing here was rendered in a browser: contrast, touch heights and scroll behaviour are **verify** items for the live pass.

## Decisions on the proposal's open questions (session's call, 2026-11-17; Allan can overrule)

1. Shared result is written in the third person ("This team finished 3rd of 12"). A test renders the shared voice and asserts no "you"/"your".
2. `SeasonPage` gains `boardPlayers` so a rank reads "131st of 412".
3. Season-page rows carry `positions` (list), joined as the draft room does; `topByPosition` is keyed by base positions (PG SG SF PF C / QB RB WR TE), not roster slots.
4. NBA points stay the one number the game computes, with a sentence saying what it is. No second "season total".
5. Inside the spoiler fold: finishers, busts, steals, dream roster, then positions in a nested fold.
6. A guest's "Sign in" in the bar links to the sign-in panel on the home page (`/#sign-in`).

## Findings that changed the plan

1. `SeasonPage` needs `excludedStats` (basketball's "not counted" caveat lives there; `NbaHistoricalSport.dataNotes` returns an empty list).
2. `boardPlayers` (above).
3. `dreamRosterPoints` must be labelled as what it is: best-ball team total (rows will not add up to it) or sum of starters. Builder confirms which and uses the matching copy.
4. Multi-position (above).
5. `unrankedLeftOut` = unranked players whose finish rank is ≤ teams × 5 (the only ones a steal list can lose).
6. Guests need the shell: `TimeMachineShell` renders bare children with no user (`TimeMachineShell.tsx:32`) and the room's height depends on `.app-shell`/`.app-main`. The bar always renders; right side is "Sign in" for a guest, `AccountMenu` when signed in.
7. `SignInForm` gets an `autoFocus` prop (default true); every use here passes false, or a phone scrolls to the bottom and opens the keyboard on load.
8. `HistoricalResults` (and `HistoricalBracket`, `HistoricalPickRail`) say "you" about 46 times and tint the drafter's rows with `--you`. A `voice: 'you' | 'shared'` prop swaps both.

## Shared pieces

- **Frame**: `div.content.tm.tm-page` inside `.app-shell > main.app-main`, static and React alike. 16px gutters on phone.
- **Bar**: wordmark text "Ball Knowers Redraft" (`.topbar-wordmark.cond`, aria-label "Ball Knowers Redraft, home"). Guest: `a.topbar-signin` "Sign in" → `/#sign-in` (home scrolls `#sign-in` into view when the hash is set). Static pages carry the same bar with "Sign in".
- **Footer nav** `nav.tm-foot[aria-label="Redraft pages"]` on every page: `Home · Game of the day · NBA seasons · NFL seasons`, then `Free. No password.`
- **`.tm-pill-link`** (new): navigation pill, `--raised`, 12px radius, 10×14 padding, 15px/800, tabular. Never brand-filled.
- All strings live in one module, `web/src/redraftCopy.ts`, read by both the React pages and the static bodies, and covered by the banned-words test and the "Time Machine" grep.

## 1. Season page (`/nba/2016-17`, `/nfl/2007`)

Top to bottom:

```
header.tm-hero
  p.tm-over        Fantasy basketball · Ball Knowers
  h1.tm-title.tm-title-sm   2016-17 NBA Redraft
  p.tm-tagline     Draft a team from the real 2016-17 NBA season, knowing only what players did the year before. Then see how your picks held up.
  button.tm-play   Draft this season            (above the fold at 375x812)
  p.tm-facts       12 teams · 13 rounds · best ball
  p.muted.small    (state line, below)   /  .tm-limit panel  /  .error
section  "The draft"   (no spoilers)
  Lineup pills (ul.hist-counted > li.hist-pill per starting slot) + "Bench {n}"
  scoringNote (muted small)
  p.tm-going-in
details.tm-fold.tm-spoilers   (closed)
  summary: h2 "Spoilers: how 2016-17 played out" + span.tm-fold-hint
  h3 Top 12 finishers      ol.tm-rank-list > li.tm-rank-row   (lead row larger)
  h3 Biggest busts         ol.tm-movers.bust   (--down 14% tint)
  h3 Biggest steals        ol.tm-movers.steal  (--up 14% tint)   + unranked line
  h3 The dream roster      ul.hist-slots (+ .tm-slot-pts column), p.tm-dream-total
  details.tm-fold  "Top 5 at each position"  → div.tm-pos-grid
section.hist-disclosure  "About this season's data"   (not folded)
  ul.hist-excluded, ul.hist-notes, HowThisWorks, p.hist-attribution (NFL)
nav.tm-neighbours   Previous season / Next season  (.tm-pill-link; null side omitted)
a.tm-pill-link      All NBA seasons
button.tm-btn.quiet Draft 2016-17
nav.tm-foot
```

Mover row: name, position badge, team, points on the right; under it a `dl.tm-move-nums`: `Going in` 4th · `Finished` 131st of 412 · `.tm-move-delta` "Fell 127 spots". Ranks are text; no bars.

### Copy

| Element | Copy |
|---|---|
| over | `Fantasy basketball · Ball Knowers` / `Fantasy football · Ball Knowers` |
| H1 | `{label} NBA Redraft` / `{label} NFL Redraft` |
| tagline | `Draft a team from the real {label} {NBA\|NFL} season, knowing only what players did the year before. Then see how your picks held up.` |
| button | `Draft this season`; starting: `Starting…`; guest with this season in progress: `Resume your {label} draft` |
| facts | `{teams} teams · {rounds} rounds · best ball` |
| state line, guest with allowance | `Free, no sign-up. Guests get one season draft of their choice; the game of the day is free every day.` |
| state line, signed in | `Seasons you pick don't count toward achievements or your daily streak.` |
| state line, static HTML | `Free, no sign-up for your first season.` |
| "The draft" heading | `The draft` |
| going-in | `"Going in" is our own pre-season ranking: {rankingLabel}. It is the order our draft board starts in, not where people really drafted players that year.` |
| spoiler h2 | `Spoilers: how {label} played out` |
| spoiler hint | `Top finishers, busts, steals and the dream roster. Draft first if you want the surprise.` |
| top heading / sub | `Top {n} finishers` / `By season points in this game's scoring.` |
| top row second line | `Ranked {ordinal} going in` / `No pre-season rank` |
| tie | `T{n}` with `aria-label="tied for {ordinal}"` |
| busts heading / sub | `Biggest busts` / `Ranked in our pre-season top {teams*5} (the first five rounds), then finished far lower.` |
| steals heading / sub | `Biggest steals` / `Finished in the season's top {teams*5} after being ranked much lower going in.` |
| mover numbers | `Going in` {ordinal} · `Finished` {ordinal} `of {boardPlayers}` · `Fell {n} spots` / `Rose {n} spots` (`spot` when 1) |
| busts short / empty | `Only {n} {player\|players} in our pre-season top {teams*5} finished lower than ranked.` / `Nobody in our pre-season top {teams*5} finished lower than ranked.` |
| steals short / empty | `Only {n} {player\|players} in the season's top {teams*5} had been ranked lower going in.` / `Nobody in the season's top {teams*5} had been ranked lower going in.` |
| unranked line | `{n} more {player\|players} finished in the top {teams*5} with no pre-season rank (no stats the season before), so {it is\|they are} not listed here.` |
| dream heading / sub | `The dream roster` / `The highest-scoring roster that fits the lineup, ignoring draft order. Nobody could have drafted all of it.` |
| dream total, best-ball | `Team total: {pts} points, best lineup each week. The players' own season points add up to more, because bench weeks only count when they beat a starter.` |
| dream total, sum of starters | `Starters' season points added up: {pts}.` |
| position fold | `Top 5 at each position`; each list headed by the `.pos` badge + full word |
| about heading | `About this season's data`; excluded heading `Not counted in {label}` |
| HowThisWorks, NBA | `Points are this game's scoring: each week, a player's best single game counts, added up over the whole real season. They are not his usual season fantasy total. Finished is his rank in those points among every player on our board. Going in is our pre-season ranking ({rankingLabel}).` |
| HowThisWorks, NFL | `Points are full-PPR fantasy points over the whole real season. Finished is his rank in those points among every player on our board. Going in is our pre-season ranking ({rankingLabel}). Quarterbacks, running backs, wide receivers and tight ends only.` |
| neighbours / index / end | `Previous season: {slug}` / `Next season: {slug}` / `All NBA seasons` / `Draft {label}` |
| loading / error | `Loading the {label} season…` / `This season didn't load.` + `Try again` |

The two HowThisWorks sentences describe scoring; the builder checks each against the code (`EraScoring`, the sport classes) and corrects the sentence, not the code, if they differ.

### Guest limit panel (`.tm-limit`, inline, never modal)

```
h3  You've used your free season draft
    Guests get one season of their choice. Sign in with your Sleeper username to draft any season, earn achievements and keep your history.
    SignInForm autoFocus={false}
    No password. Your games from this browser come with you.
    ( Play today's game )   [Resume your {label} draft]   (only while that game is drafting)
```

Shown after a 403, or up front when the guest's game list already holds a non-daily game. The Draft button stays visible, disabled. After sign-in the panel collapses to the claim line; the draft does not start by itself. When it appears after a click, focus moves to its h3.

### New classes

`.tm-facts`, `.tm-going-in`, `.tm-spoilers`, `.tm-rank-list`, `.tm-rank-row` (grid `40px minmax(0,1fr) auto`), `.tm-rank-num`, `.tm-rank-who`, `.tm-rank-pts`, `.tm-rank-sub`, `.tm-movers.bust|.steal` (`color-mix(in oklab, var(--down|--up) 14%, var(--panel))`), `.tm-move-nums`, `.tm-move-delta`, `.tm-slot-pts`, `.tm-dream-total`, `.tm-pos-grid` (`repeat(auto-fit, minmax(220px,1fr))`), `.tm-neighbours`, `.tm-limit` (`--brand` 12% over `--panel`, 22px radius). Add `@media (prefers-reduced-motion: reduce) { .tm-fold > summary::after { transition: none } }`.

### States

Static (button disabled, all content present, fold closed) · React first paint from embedded `#redraft-data` · client-nav loading · fetch error · unoffered season (not-found body) · guest with allowance · guest with this season in progress · guest at limit · signed in · starting · start failed · short or empty busts/steals (never padded) · `unrankedLeftOut > 0` · no caveats (modern NBA) · null prev/next.

## 2. Sport index (`/nba`, `/nfl`)

Decade groups of year pills, newest decade first; years oldest to newest within a decade. One large "Newest season" tile on top. No per-season fact (anything from the outcome is a spoiler).

```
header.tm-hero:  over · h1 "NBA Redraft: every past season" · tagline · a.tm-newest
section.tm-decade (per decade):  h2.tm-decade-head "2010s" + "{n} seasons"  ·  ul.tm-years > li > a.tm-pill-link
p.muted.small  naming rule + era note
nav.tm-foot
```

| Element | NBA | NFL |
|---|---|---|
| H1 | `NBA Redraft: every past season` | `NFL Redraft: every past season` |
| tagline | `Pick a season from {first} to {last}. Each page shows who finished on top, who busted and who was a steal (folded, so you can draft first), and lets you draft it.` | same |
| newest tile | `Newest season` / `{label}` | same |
| naming rule | `Seasons are named by both years: 2016-17 is the season that started in fall 2016.` | `Seasons are named by the year they started: 2007 is the season played in fall 2007.` |
| era note | `Older seasons count fewer stats, because the NBA was not tracking them all yet. Each season page says which.` | `Each season page lists its data notes.` |
| loading / error / empty | `Loading seasons…` / `The season list didn't load.` + `Try again` / `No {NBA\|NFL} seasons are on offer yet.` | |

New: `.tm-newest` (sport-tinted tile), `.tm-decade`, `.tm-decade-head`, `.tm-years` (`repeat(auto-fill, minmax(92px,1fr))`).

## 3. `/daily`

```
header.tm-hero:  over "Ball Knowers" · h1 "Daily Redraft: a past season every day" · tagline
  ul.tm-daily-cards (the home page's component, reused)
  p.tm-daily-note   · guest: "Sign in to keep a daily streak."
section  h2 "Yesterday's seasons"   ul.tm-games rows: "{Basketball|Football} · {day}" · {seasonLabel} · a.tm-btn.quiet "How it played out"
section  How to play (existing steps)
nav.tm-foot
```

- Tagline: `Every day, everyone gets the same past NBA season and the same past NFL season to draft, from a random slot. New games at midnight Eastern.`
- Static cards: sport name, `Today's season shows when the page loads`, Play disabled. Today's season is never in the static file.
- Streak pill: signed in only.
- Yesterday with no row: `No {basketball|football} game was dealt yesterday.`
- Rollover while reloading: `A new game is ready. Loading it…` · load error: `Today's games didn't load.` + `Try again`.

No new classes.

## 4. Home `/` (guest and signed in)

```
header.tm-hero:  mark · over · h1 "Redraft a past NBA or NFL season" · tagline
  section.tm-daily  Game of the day cards · note · link "About the daily game" → /daily
  (signed in only) sport segmented control + "Draft a random year"
section  h2 "Pick a season"   div.tm-sport-tiles > a.tm-sport-tile.nba|.nfl
         "Basketball" · "{first} to {last}" · "{n} seasons to pick from"
section  Your games   (guest: only if this browser has games, with "Saved in this browser only. Sign in to keep them."; chosen games carry span.tm-game-chosen "Chosen season")
(signed in) HistoricalProfileCard
section  How to play
section#sign-in.tm-signin (guest only)
         h2 "Keep your games"
         Sign in with your Sleeper username to keep your history, earn achievements and play any season.
         SignInForm autoFocus={false}; note "No password. This site never asks Sleeper for anything private." + analytics line
nav.tm-foot
```

- Tagline: `Draft a team from a real past season using only what players did the year before, then watch the year play out.`
- Head title: `Ball Knowers Redraft: Draft a Past NBA or NFL Season`
- Head description: `Redraft a real past NBA or NFL season: pick a team using only what players did the year before, then watch the year play out. Free, no sign-up.`
- "Draft a random year" is not shown to guests (the server refuses it; a button that always fails is a trap).
- Claim line after sign-in: `{moved} {game|games} from this browser {is|are} now in your history.` and, when `leftBehind > 0`: `{n} game of the day stayed behind, because you had already played that day's game in that sport.`
- After a guest finishes a game, the results page shows a `.tm-signin` panel: `Keep this result` / `Sign in with your Sleeper username to save it beyond this browser, keep a daily streak and earn achievements.` Shown, not forced.

New: `.tm-sport-tiles`, `.tm-sport-tile.nba|.nfl` (sport colour 14% over `--panel`, 22px radius), `.tm-signin`, `.tm-game-chosen`, `.topbar-signin`, `.tm-foot`.

Static home: daily placeholders as on `/daily`, tiles with build-time ranges, sign-in panel with the form disabled.

## 5. Shared result (`/game/:id`, viewer is not the owner)

```
p.tm-kicker   Shared result · {label} {NBA|NFL} Redraft · Game of the day, {day}   (or "· Chosen season", or nothing)
section.hist-finish
   h2  This team finished 3rd of 12
   record · points · draft score (existing)
   ( Play today's {basketball|football} game ) .tm-play      [How {label} played out] .tm-btn.quiet
   (no Share button)
…the rest of HistoricalResults, voice="shared"
nav.tm-foot
```

Voice swaps (pattern for all ~46 strings): `You …` → `This team …`; `The story of your draft` → `The story of this draft`; `What you got right` / `{a} of your {t} picks` → `What this draft got right` / `{a} of {t} picks`; `Best steal you passed on` / `At pick {n} you took` → `Best steal passed on` / `At pick {n} this team took`; `You against hindsight` → `This draft against hindsight`; `Your lineup, week {n}` → `This team's lineup, week {n}`; `You had a bye this week.` → `This team had a bye this week.`

Colour: `--you` is for the viewer only. In shared voice `.hist-me` / `.hist-champ.me` are not applied; the drafter's rows get `.tm-drafter` (`--raised` + `inset 3px 0 0 var(--text)`) and the text suffix ` · this team`. No owner name anywhere.

Owner or not: with an identity, try the owner endpoint first and fall back to the public one; hold the loading state until that settles so the owner never sees the shared page flash. Not finished or missing: `No finished game here` / `A game shows here once it is finished.` + the 404 links. `noindex` meta added on mount.

## 6. Not found (static `404.html` and in-app)

```
header.tm-hero
  h1  Nothing at this address
  p   It may be a season we don't offer, or a link with a typo.
  nav.tm-links[aria-label="Where to go"]   [Home] [Today's game] [NBA seasons] [NFL seasons]   (.tm-pill-link)
```

Title: `Page not found | Ball Knowers Redraft`. Carries `<meta name="robots" content="noindex">`. New: `.tm-links`.

## Controls and colour

| Control | Job | Treatment |
|---|---|---|
| Draft this season, Play, Play today's game | primary action | `.tm-play` / `.tm-btn`, `--brand` fill |
| Resume, View, Draft {label} (repeat), How it played out | secondary action | `.tm-btn.quiet` |
| years, prev/next, tiles, Sign in, footer, 404 links | navigation | links; never brand-filled |
| spoiler and position folds | disclosure | native `<details>`, `.tm-fold` |

`--down` / `--up` tints mark busts / steals, with the heading and "Fell"/"Rose" carrying the meaning without colour. Sport colours tint the tiles, with the sport name as text.

## Verify in the browser (none measured)

Contrast of text on the new tints (≥ 4.5:1) and the `.tm-drafter` bar (≥ 3:1); 44px touch height of pills; `main.app-main` scrolling on a static page with no script; `/#sign-in` scroll under the router; the season page's button above the fold at 375×812; a fold opened before React mounts closing on mount; the guest markup flashing for a signed-in visitor on `/`.

## Not building

Player photos; a teaser fact per season on the index; index search; a random year for guests; Share on someone else's result; any email or password wording; auto-starting a draft after sign-in; charts for busts and steals.
