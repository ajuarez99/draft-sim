# A full league suite, built on top of draft-sim

Design note, 2026-09-07. **Promoted from `ideas/` to a real plan the same day**,
after its two blocking open questions were measured rather than assumed.
**Nothing here is built** — planning only, per the `AGENTS.md` convention where
that is a legitimate outcome for a doc like this.

## The pitch

draft-sim currently answers one question: "what would this draft look like." The
bigger idea is to grow it into the thing a league actually opens all season --
history and polls first, with room to keep adding once those exist:

- **League history.** Standings and records across seasons, not just the current
  one. Past drafts already ingested (`draft` table) are the obvious seed --
  final rosters, who reached, who got value, per the same `valueDelta` /
  `positionalPrior` math the simulator already uses. A "how did last year's mock
  compare to what actually happened" view falls out of data already in the
  database with no new ingest.
- **Polls.** Weekly power rankings, prediction polls, "grade this draft" votes.
  This is the one genuinely new category -- it needs manager-facing input, not
  just Sleeper ingest, which means auth-for-humans (see Open questions) rather
  than the single-user assumption the app runs on today.
- **Whatever else a league wants**, once the first two exist and there's a
  container to hang it on: side bets, trade history, a manager leaderboard for
  "biggest reach of the year." Deliberately not speccing these now.

---

## Measured 2026-09-07 — two of the three open questions are now answered

### 1. There is no Sleeper OAuth. This is settled, from Sleeper's own docs.

The original open question offered "Sleeper OAuth (if it exists) vs. a bespoke
login vs. something lighter", marked **unresearched**. Researched now.
`docs.sleeper.com` states it directly:

> "We do not perform authentication as our API is read-only and only contains
> league information."

So the appealing option is gone: **there is no way to let a manager prove they are
themselves via Sleeper.** Any multi-user feature needs a bespoke identity of its
own, mapped onto the existing `manager.sleeper_user_id` (`V1__init.sql:38-42`) by
hand or by an invite the commissioner sends. That is a real cost, and it lands
entirely on the polls half of this doc — which is the argument for doing history
first, unchanged but now evidence-backed rather than instinct.

The same page also scopes the API as **"free to use for non-commercial purposes."**
Nothing here is commercial, but a league suite is the first thing in this repo that
could plausibly grow an audience, so record it before rather than after.

### 2. The read-only history slice needs no new ingest path. Verified live.

Every endpoint the history half wants was called against (Foot) Ball Knowers 2025
(`1254190892974084096`), unauthenticated, this session:

| endpoint | what came back | why it matters |
| --- | --- | --- |
| `/league/{id}/rosters` | 12 rosters; `settings` carries `wins`, `losses`, `ties`, `fpts`, `fpts_decimal`, `fpts_against`, `ppts` | **standings are a read, not a computation** — no need to sum matchups |
| `/league/{id}/winners_bracket` | bracket rows (`m`,`r`,`w`,`l`,`t1`,`t2`,`t2_from`) | final placement / who won it |
| `/league/{id}/matchups/{week}` | per-roster `points`, `starters`, `players`, `matchup_id` | weekly detail, if ever wanted |
| `/league/{id}/transactions/{week}` | waivers/trades with `creator`, `settings.seq` | trade history, later |
| `/league/{id}` | `metadata.latest_league_winner_roster_id`, trophy fields, `status: complete` | champion without parsing the bracket |
| `previous_league_id` chain | already implemented — `SleeperClient.leagueChain` (`SleeperClient.java:64-73`) | multi-season walk exists today |

So open question 3 ("unverified whether that's a small extension or a new ingest
path") resolves to **small extension**: four new methods on `SleeperClient`
(`rosters`, `winnersBracket`, `matchups`, `transactions`, alongside the seven at
`SleeperClient.java:25-57`), one new table, and the season walk already in the
client. No auth, no new crawl concept, no change to anything the simulator reads.

---

## Why this is bigger than it looks

Everything shipped so far is single-user: Allan runs ingest, Allan looks at the
board, Allan runs mocks. A league suite that other managers open means:

- **Multi-user auth**, not the current binary `API_TOKEN` on/off switch
  (`ApiTokenFilter.java:26`, `WebConfig.java:36-49` — one shared bearer token that
  is either on for everything or off for everything). Each manager needs their own
  identity to vote in a poll or see "your" history. Per the measurement above, that
  identity cannot come from Sleeper.
- **Write paths with real users behind them.** Everything today is read-mostly
  (ingest writes, humans only read) except mock-draft sessions, which are
  already scoped to one browser session with no login. Polls are the first
  feature where arbitrary league members submit data that has to be
  attributed and can't be re-run like a sim.
- **A reason for 13 other people to open the app at all.** Right now the only
  user is Allan. Nothing about the data model says this doesn't work for a
  whole league, but nothing about the product does either -- there's no invite
  flow, no per-manager view, no notion of "your" anything.

None of this is a reason not to do it. It's the reason it's a two-phase plan and
not one feature: it's a different kind of app (multi-user, always-on, social)
layered on top of a simulator (single-user, on-demand, analytical), and that
seam should be crossed deliberately, not backed into feature by feature.

## What already exists that this would build on

- `draft`, `league`, `manager`, `manager_profile` and `board` tables already hold
  the history a "league history" view would read (`V1__init.sql:38-107`). No new
  ingest for the first version — just new read paths and UI over data already there.
- `draft_pick.adp_at_time` (`V1__init.sql:71-83`) is the denormalized board
  position at the time of each pick. **That column is what makes this app's history
  view different from Sleeper's own** — "biggest reach of 2025" and "best value of
  2025" are one query over a column that already exists, using the same reach
  definition the profiles are fit from.
- The manager-tendencies work already put real managers, not just bots, into the
  product (`/managers`, `App.tsx:67`). History and polls are both "more surface
  area for the real managers," same direction.
- `SportRules` seam exists for basketball — a league suite is sport-agnostic in a
  way the simulator mostly is too, worth keeping in mind if this gets built before
  basketball support does.

---

## Proposed design

### Phase A — league history, read-only, no auth (do this first)

**Ingest.** Four new `SleeperClient` methods (above). One new migration — the next
free version is **V5** (`V4__mock_from_draft.sql` is the highest on disk). Shape:

    create table roster_season (
        id            bigserial primary key,
        league_id     bigint not null references league (id) on delete cascade,
        manager_id    bigint references manager (id),      -- nullable: orphan rosters exist
        roster_id     int    not null,                     -- Sleeper's own, for bracket joins
        wins/losses/ties        int,
        points_for/points_against/points_possible numeric(7,2),
        final_placement         int,                       -- from winners_bracket, nullable
        unique (league_id, roster_id)
    );

Written by a new `LeagueHistoryIngestService` walking `leagueChain`. **It must not
write to `draft_pick` or `manager_profile`** — same wall as the mock tables, same
reason (`MockDraftContaminationIT` is the pattern to copy).

**Read paths.** `GET /api/leagues/{sleeperId}/history` — seasons, standings,
champion per season. `GET /api/managers/{id}/history` — one manager across seasons,
record plus their draft-side numbers (reach, value, positional tilt) from data
already present.

**Frontend.** One route, `/history`, alongside the six in `App.tsx:62-68`. Reuse
the existing table/pill vocabulary in `styles.css` — read its house-style header
first, per `HANDOFF.md`.

**This phase is the whole validation.** It answers "would anyone but Allan open
this" without building auth for an audience that may not want it.

### Phase B — polls (only if Phase A gets used)

**Auth, bespoke and minimal.** A per-manager magic link: commissioner generates one
link per `manager` row, the link carries a long random token, the token maps to a
`manager_id` in a `manager_session` table, no passwords, no email dependency if the
links are handed out in the league chat. This is the smallest thing that attributes
a vote to a person. It replaces nothing — `API_TOKEN` stays exactly as it is for
the machine-facing `/api/**` surface.

    poll(id, league_id, season, week, kind, question, opens_at, closes_at)
    poll_option(id, poll_id, label, player_id?, manager_id?)
    poll_vote(id, poll_id, option_id, manager_id, created_at, unique(poll_id, manager_id))

One vote per manager per poll, enforced in the schema, not in the service.

**Do not start here.** Everything in Phase B is new risk (auth, spam, moderation,
"who can see results before close") and none of it is testable without other humans.

## Explicitly not being built

- **Real accounts** — no passwords, no OAuth (there is none to have), no password
  reset flow. Magic links or nothing.
- **A Sleeper chat competitor.** Open question 2 below is still open, and the
  honest answer may be "they already have this and ignore it."
- **Anything commercial**, per Sleeper's own API terms quoted above.
- **Merging league-history tables with `draft`/`draft_pick`.** Same argument as
  `next-features-roadmap.md` §3.4: separate producers, one consumer contract. A
  season-standings row is not a draft pick and should not learn to be one.
- **Multi-sport now.** The tables carry `league.sport` already; nothing in Phase A
  needs to branch on it.

## Acceptance criteria (Phase A)

1. A season ingest of the Ball Knowers chain produces standings that match
   Sleeper's own UI exactly for 2025 and 2026 — wins, losses, points for, champion.
   **Checked against the real site, not just against the API response the code
   itself parsed.**
2. `ProfileService.fit()` output is byte-identical before and after the history
   ingest runs. The contamination guard is a test, not a code comment — extend
   `MockDraftContaminationIT`'s pattern to the new table.
3. A league with a missing/partial season (a chain that ends, a roster with no
   manager) renders rather than 500s. `previous_league_id` chains do end.
4. The history view distinguishes **"what happened"** (wins, points) from
   **"what this app thinks about it"** (reach, value) visually — the second is
   model output and gets the same provenance honesty as everything else.
5. No new endpoint requires auth, and none of them writes to a table the simulator
   reads.

## Open questions still open

1. ~~Auth model~~ — **answered above**: bespoke, because Sleeper offers nothing.
   What remains is only *which* bespoke shape, and that decision belongs to Phase B,
   not now.
2. **Where polls live relative to Sleeper.** Sleeper already has league chat. Is
   this additive or competing with something managers already use and ignore?
   Unresolved, and the cheapest way to find out is to ask the league, not to build.
3. ~~Scope of "history" ingest~~ — **answered above**: small extension, four client
   methods and one table.
4. **New:** does anyone open Phase A? That is the only question whose answer
   changes whether Phase B is worth building at all.

## Recommendation

Unchanged by the measurements, and now supported by them: **start with Phase A,
the read-only slice that needs no new auth.** The measurement made the case
stronger in both directions at once — history got cheaper than assumed (no new
ingest path), and polls got more expensive than assumed (no Sleeper OAuth to lean
on, so auth is fully bespoke).
