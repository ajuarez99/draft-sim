# Feature Specification: ESPN and Yahoo League Ingest

**Feature Branch**: `035-espn-yahoo-ingest`

**Created**: 2026-10-14

**Status**: Draft

**Input**: User description: "ok we have sleeper leagues but i want the ability to ingest yahoo and espn. just joined an espn league"

## Context *(what exists today)*

Every league in Ball Knowers comes from Sleeper. The sign-in gate is a Sleeper
username, leagues are found through that user's Sleeper account, and the
platform's identifiers run through the whole store: players, drafts and
transactions are all keyed by their Sleeper ids (`sleeper_player_id` alone
appears in 16 places across the migrations). Sleeper's API is public and needs
no credentials. Neither of the other two platforms works that way:

- **ESPN** has an undocumented but long-stable read API. Public leagues read
  without credentials; private leagues need two browser cookies from a signed-in
  ESPN session (`claude/adp-sources.md` §6, marked unverified there).
- **Yahoo** has an official, documented API behind three-legged OAuth: an
  app registration, a redirect, and a stored refresh token before any data
  arrives.

The person asking has just joined an ESPN league, so ESPN is the one with a
real league to verify against. Yahoo has no real league behind it yet.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Add my ESPN league (Priority: P1)

A signed-in user pastes their ESPN league's link (or its league id and season)
into league setup. Ball Knowers reads the league's settings, teams, managers
and rosters, and the league shows up in their league picker alongside their
Sleeper leagues. They pick which ESPN team is theirs.

**Why this priority**: Nothing else in this spec works until an ESPN league
exists in the store, and there is a real ESPN league waiting.

**Independent Test**: Add the user's real ESPN league; confirm the team names,
manager names, roster sizes, scoring type and team count shown match what
ESPN's own site shows for that league.

**Acceptance Scenarios**:

1. **Given** a signed-in user and a valid ESPN league link, **When** they add it, **Then** the league appears in their picker labelled as an ESPN league, with the same name, season, sport and team count ESPN shows.
2. **Given** an added ESPN league, **When** the user picks their own team from its team list, **Then** pages that say "you" (your pick, your roster, your matchup) use that team.
3. **Given** an ESPN league link that is malformed, for a league that does not exist, or for a private league with no access granted, **When** the user submits it, **Then** they see a message naming which of those it is, and nothing is half-written to the store.
4. **Given** an ESPN league already added, **When** it is added again, **Then** it refreshes in place rather than creating a duplicate.

---

### User Story 2 - ESPN league pages show real data (Priority: P1)

Once an ESPN league is in, the pages that already work for Sleeper leagues work
for it too, within whatever the ESPN data can honestly support: standings,
matchups, rosters, transactions, draft results and the pages built on them.

**Why this priority**: An ESPN league that only appears in a picker is not
useful; the value is the existing pages working on it.

**Independent Test**: Open each in-scope page for the real ESPN league and
compare a sample of numbers (a week's matchup scores, a team's record, a
draft pick, a transaction) against ESPN's own site.

**Acceptance Scenarios**:

1. **Given** an ingested ESPN league, **When** the user opens an in-scope page, **Then** it renders the league's real data, not an empty or Sleeper-shaped placeholder.
2. **Given** a page that depends on data ESPN does not provide or that has not been mapped yet, **When** the user opens it for an ESPN league, **Then** the page says plainly that this view is not available for ESPN leagues yet, rather than showing an empty chart or a zero.
3. **Given** an ESPN player whose identity could not be matched to the app's player catalogue, **When** that player appears on a page, **Then** he is still shown by name, and the unmatched count is visible to the league owner rather than silently dropped.

In-scope pages (clarified 2026-10-14, Q1 = A): the league pages — standings, matchups, rosters, transactions, power rankings, league analysis and league history. Draft grades, mock drafts, the live draft room and manager tendencies are out of scope for ESPN leagues in this spec and must show the FR-008 message.

---

### User Story 3 - Keep the ESPN league current (Priority: P2)

After the first ingest, the ESPN league refreshes on the same rhythm Sleeper
leagues do, so scores, standings and transactions do not go stale.

**Why this priority**: Without it the league is a snapshot that rots within a
week; but a manual refresh button makes P1 usable on its own.

**Independent Test**: After a scoring week completes on ESPN, confirm the
league's matchup results and standings in Ball Knowers update without anyone
re-adding the league.

**Acceptance Scenarios**:

1. **Given** an ingested ESPN league, **When** the scheduled refresh runs, **Then** newly completed weeks and new transactions appear, and earlier weeks are not rewritten.
2. **Given** ESPN access that has stopped working (expired or revoked access, or ESPN changing its responses), **When** a refresh fails, **Then** the league keeps its last good data, shows when it was last refreshed, and the owner is told why the refresh failed.

---

### User Story 4 - Add a Yahoo league (Priority: P3)

A user connects their Yahoo account and adds a Yahoo league the same way an
ESPN league is added, with the same pages working for it.

**Why this priority**: Requested, but there is no real Yahoo league to verify
against yet, and Yahoo needs an app registration and account-connection flow
before a single row can be read.

**Independent Test**: Connect a real Yahoo account, add one of its leagues,
compare team names, records and one week's scores against Yahoo's site.

**Acceptance Scenarios**:

1. **Given** a user who has connected their Yahoo account, **When** they choose one of that account's leagues, **Then** it is added the same way an ESPN league is (Story 1's scenarios apply).
2. **Given** a user who declines or revokes the Yahoo connection, **When** they return, **Then** Yahoo leagues already added keep their last good data and say they can no longer refresh.

**Deferred (clarified 2026-10-14, Q3 = A):** this story moves to its own spec. It stays written here so the platform-neutral parts of this spec (FR-002, FR-003, FR-008) are built so as not to assume ESPN is the only other platform, but nothing Yahoo-specific is built in 035.

---

### Edge Cases

- **Same league name, different platforms.** A user with "Ball Knowers" on Sleeper and an ESPN league with a similar name must be able to tell them apart in every picker and header.
- **Same human, two platforms.** A manager who is in both a Sleeper league and an ESPN league is two separate managers in this spec; nothing tries to merge them.
- **ESPN scoring and roster settings the app does not model** (e.g. category leagues, unusual roster slots, IDP). The league is either refused at setup with the reason, or added with the affected pages marked unavailable — never silently scored as if it were points.
- **League created mid-season or just joined.** A league with no completed weeks or no draft yet still adds cleanly and shows the pages that make sense pre-season.
- **Prior seasons.** ESPN keeps older seasons of the same league; how many are read is bounded (see Assumptions), and a season ESPN no longer serves is reported as missing, not empty.
- **Player identity.** ESPN and Yahoo players carry their own ids. A player who matches nobody, or matches two people (same name, same position), is logged and shown by name; he is never attached to the wrong player.
- **Platform response changes.** ESPN's API is undocumented; if a field the ingest relies on disappears, the ingest fails loudly for that league rather than writing nulls that read as zeros.
- **Live draft room.** The live draft room polls Sleeper during a draft. An ESPN draft is not followed live in this spec (see Assumptions); the draft room should say so rather than sit waiting for picks that never arrive.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Users MUST be able to add an ESPN league by pasting its league link or entering its league id and season.
- **FR-002**: The system MUST record which platform every league, manager, draft, pick and transaction came from, so no two platforms' identifiers can collide.
- **FR-003**: Every league picker, page header and league list MUST show which platform a league comes from.
- **FR-004**: The system MUST let a user claim one team in a non-Sleeper league as their own, and use that claim wherever the app currently identifies "you" by Sleeper user.
- **FR-005**: Adding a league MUST be all-or-nothing: if the ingest fails partway, nothing from that attempt is left in the store.
- **FR-006**: Re-adding or refreshing a league MUST update it in place, never duplicate it.
- **FR-007**: The system MUST map ESPN players onto the app's existing player catalogue, MUST NOT guess when a match is ambiguous, and MUST keep a visible count and list of unmatched players per league.
- **FR-008**: Any page that cannot be produced for a league's platform MUST say so in words, naming the platform, instead of rendering empty or zero values.
- **FR-009**: (Clarified 2026-10-14, Q2 = A.) A private ESPN league MUST be readable using the two ESPN session cookies the user supplies. The system MUST store them encrypted, one set per user, use them only to read that user's own ESPN leagues, never return them to any client after they are saved, never write them to logs, and let the user replace or delete them. A public ESPN league MUST NOT require them. **Amended by plan (2026-10-14, research R13):** stored per ESPN league rather than per user, because the app's user identity is an unverified header — a per-user credential would let anyone presenting a user's Sleeper id read every private league that user's cookies can see. Adding a second private league means pasting the cookies again.
- **FR-010**: ESPN leagues MUST refresh on the same rule as Sleeper leagues (on visit, when stale), and a manual refresh MUST be available to the league owner. **Amended after plan review (F12):** originally said "the same schedule" — there is no per-league scheduler in this app; Sleeper leagues refresh on visit.
- **FR-011**: A failed refresh MUST keep the last good data, record when the league last refreshed successfully, and surface the failure reason to the league owner.
- **FR-012**: Leagues whose scoring format the app does not model MUST be refused at setup with that reason, or added with affected pages marked unavailable; they MUST NOT be scored as a different format.
- **FR-013**: Existing Sleeper leagues MUST behave exactly as before; adding platforms must not change any Sleeper league's pages or numbers.
- **FR-014**: (Story 4 — deferred to its own spec per Q3 = A.) Users MUST be able to connect a Yahoo account and add one of its leagues, under the same rules as FR-002 through FR-013.

### Key Entities

- **League source**: which platform a league comes from (Sleeper, ESPN, Yahoo) plus that platform's own league id and season. One app league has exactly one source.
- **Platform access**: whatever a league needs in order to be read from its platform — nothing for Sleeper or a public ESPN league; credentials or a connected account otherwise. Belongs to the user who added the league, has a last-worked-at time and a failure reason.
- **Team claim**: the link between a signed-in user and one team in a non-Sleeper league, standing in for the Sleeper user id the app uses today.
- **Player identity link**: one platform's player id mapped to one app player, or recorded as unmatched/ambiguous with the platform's name and position.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The user's real ESPN league can be added from a pasted link in under 2 minutes, with no help beyond the setup screen.
- **SC-002**: For that league, team names, manager names, team count, scoring type and every completed week's matchup scores match ESPN's own site exactly (checked against at least 3 weeks, or every completed week if fewer).
- **SC-003**: At least 98% of rostered and drafted ESPN players in that league are matched to the right app player; every unmatched one is listed, and a hand check of 20 matches finds zero wrong attachments.
- **SC-004**: Every in-scope page opens for the ESPN league with real data or an explicit "not available for ESPN leagues" message; none shows an empty chart or unexplained zeros.
- **SC-005**: Every existing Sleeper league's pages are unchanged after the feature ships (same numbers on a before/after comparison of the league analysis and standings payloads).
- **SC-006**: A completed ESPN scoring week shows up in Ball Knowers on the next visit after it completes, without the league being re-added. *(Amended after plan review F12: was "within one scheduled refresh cycle".)*

## Clarifications

### Session 2026-10-14

- Q: Which pages have to work for an ESPN league? → A: The league pages (standings, matchups, rosters, transactions, power rankings, analysis, history). Draft-side pages show the not-available message.
- Q: How are private ESPN leagues accessed? → A: Store the user's ESPN cookies encrypted, per user, used only for their leagues.
- Q: Yahoo here or its own spec? → A: Its own spec; 035 ships ESPN only.

## Assumptions

- **ESPN first, measured against a real league.** The user's real ESPN league is the acceptance fixture. ESPN's API is undocumented, so every field the ingest uses is confirmed against that league's real responses before it is relied on; `claude/adp-sources.md` §6's description of the ESPN API is marked unverified and is not treated as a spec.
- **Identity stays as it is.** Sign-in remains the existing Sleeper-username gate. A user with no Sleeper account at all is out of scope for this spec; non-Sleeper leagues are attached to an existing signed-in user.
- **Both sports.** ESPN leagues for football and basketball are both accepted, since the app is multi-sport; the sport comes from ESPN's league settings, never a default.
- **Points leagues only.** Head-to-head and roto *category* leagues are refused at setup with a reason, since the app models points scoring only.
- **History depth.** Up to the same number of prior seasons the app already reads for Sleeper leagues, where ESPN still serves them.
- **No live ESPN draft following.** The live draft room keeps following Sleeper drafts only; ESPN draft results are read after the draft. Live ESPN draft polling is a candidate follow-up.
- **Draft-simulator tendencies.** Manager tendencies fitted from draft history need ADP at the time of each pick, which Sleeper's history provides and ESPN's may not; if it does not, ESPN managers are seated as neutral and the simulator says so, as it already does for managers without fitted history.
- **No cross-platform manager merging.** The same person in a Sleeper and an ESPN league remains two managers.
