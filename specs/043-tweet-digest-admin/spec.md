# Feature Specification: Morning Tweet Digest (admin copy-and-paste page)

**Feature Branch**: `043-tweet-digest-admin`

**Created**: 2026-10-20

**Status**: Draft

**Input**: User description: "i want an admin page for me to be able to copy and paste. these should be stas like how many points the trending players score tonight. how key players did. these should be digestable short form content for tweets. research some basketball fantsy account and football accounts and give me admin page to copy and paste each morning"

## Research: what fantasy accounts post (and how sure we are)

**Sourced vs assumed.** Web searches on 2026-10-20 did not return the posts themselves from the
named accounts (X profiles need a signed-in session to read). What they did confirm:

- Single-game stat lines in a compact `17 PTS 20 REB 2 AST 3 BLK` form are a staple
  ([@FantasyBall365](https://x.com/fantasyball365)).
- The official [@NBAFantasy](https://x.com/NBAFantasy?lang=en) account has posted ranked lists of
  the night's top fantasy performers (reported by Sportskeeda in 2023; current habit unverified).
- "Most added on Sleeper" (top adds, often paired with top drops) is a recurring weekly content
  format ([Sports Gambling Podcast](https://www.sportsgamblingpodcast.com/2024/10/28/week-9-waiver-wire-advice-top-10-most-added-players)).
- Season-best single-game fantasy performances are a tracked list in their own right
  ([LineupExperts](https://www.lineupexperts.com/basketball/top-performances)).
- NFL weekly content leans on usage leaders (target share, snap share) alongside points
  ([FantasyPros](https://fantasypros.com/2026/10/beyond-the-waiver-wire-fantasy-football-moves-to-make-week-7-2),
  [Fantasy Footballers](https://thefantasyfootballers.com/analysis/fantasy-football-target-trends-for-week-7-fantasy-football)).

**Assumed from the genre, not sourced:** a short hook line plus emoji, a numbered top-5, one
stat line per player, the platform credited ("per Sleeper"), and staying under the 280-character
limit so a post needs no thread. These shape the formats below; Allan can veto any of them.

The post formats this feature produces, each one a "card" on the page:

| # | Card | Sport | Example shape (illustrative numbers, not real) |
|---|------|-------|------------------------------------------------|
| 1 | **Trending adds: how they did** — the user's headline ask | both | `📈 Sleeper's most-added players last night:` then `1. Name (+4,210 adds) — 38.5 FP (22/9/4)` × up to 5 |
| 2 | **Top performers** | both | `🔥 Top fantasy scores last night:` then `1. Name — 62.5 FP (34 PTS/12 REB/8 AST)` × 5 |
| 3 | **Key players** — how the big names did, good or bad | both | `⭐ How the stars did:` then `Name — 48.0 FP ✅` / `Name — 14.5 FP ❌` |
| 4 | **Single stat-line spotlight** — the night's best line on its own | both | `Name last night: 34 PTS, 12 REB, 8 AST, 3 STL — 62.5 fantasy points.` |
| 5 | **Position leaders** — top scorer at each position | NFL only | `🏈 Week 7 leaders: QB Name 31.2 · RB Name 28.4 · WR Name 27.0 · TE Name 19.6` |

## Clarifications

### Session 2026-10-20

- Q: Which scoring do the tweets' fantasy points use? → A: Sleeper's default scoring for the
  sport, labelled on the page, so numbers match what outside followers see in their own apps.
- Q: Who counts as a "key player"? → A: Automatic — the top ~15 players by preseason rank/ADP,
  switching to season-average rank once enough games exist; ✅/❌ is judged against the player's
  own average or projection. No hand-kept list.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Copy the trending-adds recap (Priority: P1)

Each morning Allan opens one admin page, sees a ready-to-post "most-added players and how they
did" post for the last completed game night, clicks Copy, and pastes it into X.

**Why this priority**: It is the exact example in the request ("how many points the trending
players score tonight"), and the data for it (Sleeper's trending adds, every player's game
stat lines) is already collected daily.

**Independent Test**: With trending adds and last night's games stored, open the page as admin,
click Copy on the trending card, paste into a text field: the text matches what the card shows,
is ≤ 280 characters, and each player's points match ~~that player's line on the app's own stats
pages for the same scoring~~ Sleeper's own computed points for that game *(amended after analyze
I1, 2026-10-20: the app's stats pages use league scoring; see SC-003)*.

**Acceptance Scenarios**:

1. **Given** NBA games were played last night and trending adds were fetched, **When** Allan opens
   the page, **Then** the trending card lists up to 5 most-added players with their add count and
   last night's fantasy points and short stat line.
2. **Given** a trending player did not play last night, **When** the card is built, **Then** that
   player shows "DNP" (or is replaced by the next trending player who did play — per FR-006),
   never a fake 0.
3. **Given** Allan clicks Copy, **Then** the exact card text is on his clipboard and the page
   confirms it (e.g. "Copied").

---

### User Story 2 - Top performers and key players (Priority: P2)

The same page also offers a "top fantasy scores last night" card and a "how the stars did" card,
each separately copyable.

**Why this priority**: "how key players did" is the request's second ask; top performers is the
most common nightly format in the genre.

**Independent Test**: ~~Compare the top-performers card against the app's existing top-players-of-
the-night list for the same night and scoring~~ Compare the top-performers card against the
night's stored games ranked by Sleeper's own points (resolved players only; NFL skill positions):
same players, same order, same points. *(Amended after analyze I1, 2026-10-20: the existing
night list covers only rostered players and uses league scoring.)*

**Acceptance Scenarios**:

1. **Given** last night's games are complete, **When** the page loads, **Then** the top-performers
   card ranks the 5 highest fantasy scores of the night with stat lines.
2. **Given** the key-players list, **When** the page loads, **Then** each key player who played
   shows points and a ✅/❌ against his own expectation, and one who did not play shows "DNP".

---

### User Story 3 - Football mornings (Priority: P3)

On the morning after an NFL game day (Friday, Monday, Tuesday), the page shows the same cards
for football, plus position leaders, and for a Sunday covers the whole Sunday slate.

**Why this priority**: The request names football accounts too, but basketball is the real target
and plays every night; football only has game days three times a week.

**Independent Test**: Pick a completed NFL week in the stored data and check every number on the
football cards against Sleeper's own PPR points for those games *(amended after analyze I1)*.

**Acceptance Scenarios**:

1. **Given** NFL games finished yesterday, **When** the page loads, **Then** football cards appear
   for that game day.
2. **Given** no NFL games yesterday, **Then** the football section says so ("No NFL games last
   night — next slate: Sunday") instead of showing stale cards as if they were new.

---

### User Story 4 - Edit before copying (Priority: P3)

Allan can tweak a card's text in place (fix a nickname, add a hashtag) before copying, with a live
character count that turns red past 280.

**Why this priority**: Generated text is a starting point; a post usually gets a human touch.

**Independent Test**: Edit a card, watch the counter, copy, confirm the edited text was copied.

**Acceptance Scenarios**:

1. **Given** a card, **When** Allan edits its text, **Then** the counter updates and Copy copies
   the edited text; reloading the page restores the generated text.

### Edge Cases

- **No games last night** (off day, All-Star break, offseason): each sport section says so plainly
  and names the next game day; no card pretends yesterday was a game night.
- **Stats never fetched for the night** *(added after plan review F2)*: nothing fetches stat lines
  on a schedule today. The page says "games were played but stats aren't fetched yet", offers a
  "Fetch latest stats" action, and never reads the gap as "no games".
- **Night not finished** (late West Coast games, stats not yet fetched): the page says the night
  is incomplete and when the stats were last fetched, rather than posting partial totals as final.
  It reuses the app's existing "night counts as complete" rule rather than inventing a second one.
- **Trending fetch missing or stale**: the trending card says when it was last fetched (or that it
  never was); it never silently uses a week-old list as "last night's".
- **Trending list contains a player with no game data at all** (injured, free agent): shown as DNP
  or skipped per FR-006.
- **Card text over 280 characters** (long names): the card drops trailing entries until it fits,
  and says it did (e.g. "top 4 shown to fit").
- **Not signed in as admin**: the page refuses and shows nothing; it never appears in the site
  navigation for anyone else.
- **A player's name has punctuation or diacritics** (Jokić, Dončić): copied text keeps them intact.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide one admin-only page that shows, for each sport with games on
  the most recent completed game day, a set of post cards (the formats in the Research table).
- **FR-002**: The page MUST be unreachable to anyone without the operator's admin credential, and
  MUST NOT appear in site navigation for non-admins.
- **FR-003**: Each card MUST have a one-click Copy action that puts exactly the card's current text
  on the clipboard and confirms success.
- **FR-004**: Each card's text MUST be ≤ 280 characters as generated; if entries must be dropped to
  fit, the card MUST say how many are shown.
- **FR-005**: The trending card MUST list Sleeper's most-added players over the stored lookback
  window, each with add count, fantasy points for the covered night, and a short stat line, and
  MUST state the window and fetch time somewhere on the page (not necessarily in the tweet text).
- **FR-006**: A trending or key player who did not play MUST be shown as "DNP", never as 0 points.
  Trending cards keep the true top-5 order (a DNP stays in its slot) so the list matches Sleeper's.
  *Amended after plan review (F8, 2026-10-20):* if a trending player's team had **no game** that
  night, he is left out of the tweet rather than shown as DNP. The true rank numbers stay, and the
  page says how many players were left out. A player who played and scored 0 shows 0. Sleeper
  leaves out zero-valued fields, and that is not a DNP (F1).
- **FR-007**: Fantasy points on every card MUST come from one stated scoring basis, shown on the
  page, using the same scoring math as the rest of the app. The basis is
  **Sleeper's default scoring for the sport** (clarified 2026-10-20), labelled on the page.
- **FR-008**: The key-players card MUST report on an automatically chosen list (clarified
  2026-10-20): the top ~15 players of the sport by preseason rank/ADP~~, switching to season-average
  fantasy points once enough of the season has been played~~. Each player's ✅/❌ compares the
  night's points with that player's own expectation (season average, ~~or projection before one
  exists~~ else last season's average); the page MUST state which expectation each verdict used.
  The list size and the switch-over point are hand-set constants, labelled as such.
  *(Struck text superseded by the amendment below; analyze I2.)*
  *Amended after plan review (F5/F6, 2026-10-20):* the list stays Sleeper's search rank all season,
  because Sleeper recaptures it daily and so it already tracks in-season value. Until a player has
  5 or more games this season, his expectation is **last season's** average rather than a
  projection, because basketball projections carry no Sleeper points figure. Expectations use only
  games played before the night.
- **FR-009**: The top-performers card MUST rank the covered night's highest fantasy scores across
  all players in the sport, not only rostered ones.
- **FR-010**: The page MUST state which night (date, and for NFL the week) the cards cover and
  whether that night is complete, using the app's existing completeness rule.
- **FR-011**: When a sport had no games on the covered day, its section MUST say so and name the
  next game day; it MUST NOT show the previous game day's cards as if current.
- **FR-012**: For NFL the page MUST add a position-leaders card (top scorer at QB, RB, WR, TE).
- **FR-013**: Card text MUST be editable in place with a live character count; edits are not saved.
- **FR-014**: Stat lines MUST use sport-appropriate abbreviations (NBA: PTS/REB/AST/STL/BLK/3PM;
  NFL: passing/rushing/receiving yards and TDs, receptions) and show only non-zero headline stats.
- **FR-015**: The page MUST NOT post to X or any other service; it only produces text to copy.

### Key Entities

- **Digest day**: one sport's most recent completed game day — date, NFL week if any, completeness
  status, fetch time.
- **Post card**: a format type, the generated text, character count, the entries it was built
  from, and a note when entries were dropped to fit.
- **Player entry**: player name, team, fantasy points (or DNP), short stat line, and for trending
  the add count, for key players the ✅/❌ verdict.
- **Key player list**: the automatically chosen players the key-players card reports on (FR-008),
  each with the expectation his verdict is judged against.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a morning after games, Allan goes from opening the page to having a post on his
  clipboard in under 30 seconds, with no editing required for the post to make sense.
- **SC-002**: 100% of generated cards are ≤ 280 characters.
- **SC-003**: For a sampled night, every fantasy-point figure on every card matches Sleeper's own
  computed points for that player's game (0 mismatches).
  *Amended after plan review (F3, 2026-10-20):* this used to say "matches the app's stats pages
  under the same scoring". Those pages use each league's own scoring, never Sleeper's default, so
  it could not be met.
- **SC-004**: A non-admin visitor who requests the page sees no digest content (verified for a
  signed-out visitor and a signed-in non-admin member).
- **SC-005**: On a day with no games in a sport, that sport shows a "no games" note and zero cards.
- **SC-006**: Pasted text on X renders exactly as on the page (emoji, diacritics, line breaks).

## Assumptions

- "Each morning" means the page is opened by hand; no scheduled generation, email or push is in
  scope. The page computes cards on load from data the daily refresh already stores.
- "Tonight"/"last night" means the most recent completed game day in US Eastern time.
- The admin credential is the existing operator admin token; the page asks for it once per
  browser session. Multi-admin roles are out of scope.
- No new data source: Sleeper trending adds and every player's per-game stat lines are already
  fetched daily for both sports (specs 009, 014). If a planned card needs data that is not stored,
  that card is dropped rather than a new feed added.
- Images/graphics cards for posts, scheduling posts, and auto-posting to X are out of scope
  (FR-015). They can be a later spec.
- Basketball comes first in layout (it is the real target and plays nightly); football follows.
- Not built: most-dropped players (only adds are fetched today), usage stats like snap or target
  share (*corrected after plan review F15:* the data is stored, as `off_snp` and `rec_tgt`; these
  are left out for scope, not for lack of data), season-high/milestone detection.
