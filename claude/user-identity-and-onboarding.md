# Who are you? — username identity, scoped leagues, first-run flow

Written 2026-09-09 for the 09-10 session. Branch to work on:
`ball-knowers-multi-sport` (or a branch off it — multi-sport is done but
unmerged, and this builds on its sport-tagged picker).

**Read `claude/merge-review-multi-sport.md` first.** A parallel session reviewed
the branch this builds on and put it on HOLD for two blockers (B1: forking an NBA
live draft builds a basketball room full of football players; B2: the live poller
writes null picks for an NBA draft). Neither is caused by anything below, but
both ship to production the moment this deploy happens, and B2 fires on NBA draft
night specifically. Land them before or alongside step 7.

Goal: deploy Ball Knowers somewhere public and have it be usable by someone who
is not Allan. Explicitly **not** auth. The entry point is "type your Sleeper
username", and everything downstream of that is a function of the id it resolves
to.

## 1. What "only works for me" actually is

Three separate things, not one. Worth separating because only the first two are
in scope tomorrow.

**a. One configured identity.** `draftsim.owner.sleeper-user-id`
(`APP_OWNER_SLEEPER_USER_ID`, `config/OwnerProperties.java`) is the app's entire
notion of "me". Every personalized behaviour reads it through one function,
`engine/OwnerSlot.resolve(draft, managers, owner)` — two callers:
`LeagueController.seats()`'s `mySlot`, and `MockDraftService` line 168 for the
fork-a-live-draft path. `mySlot` is what highlights your column on the board,
what `LiveStatusBar` compares against to say "you're on the clock", and what
`CompletedDraftBoard` filters `myPicks` with. For any visitor who isn't the
configured owner, all of that resolves to `null` and the app silently degrades to
`DEFAULT_SLOT` / a manual slot input. That is the literal answer to "it only
works for me".

**b. One shared, unfiltered pool.** `GET /api/drafts` is
`DraftRepository.allWithLeague()` — *every* draft in the database, newest first.
A second user would land on the picker and see Allan's four leagues. There is no
user→league relation stored anywhere; there is also no need for one (§3).

**c. A shared bearer token in the browser.** `VITE_API_TOKEN` ships to every
client and is readable in devtools, which DEPLOY.md already flags as "fine for a
private tool, not fine for a link you hand out". Handing the link to anyone makes
`/api/ingest/*` public. **Out of scope tomorrow**, but write the sentence down
(§7) rather than letting the deploy quietly change what that tradeoff means.

## 2. Decisions taken (2026-09-09, don't re-litigate)

1. **Scoped list.** A user sees only leagues they are a member of. Membership is
   derived from data already ingested — no `app_user` table, no join table.
2. **Lazy ingest.** The username lookup fetches their Sleeper leagues read-only
   and lists them. Ingest runs when they choose to enter one, not at signup.
3. **Hard gate.** No stored user ⇒ `/` renders the username screen instead of the
   picker. Deep links still render (they just lose `mySlot`).

Non-goals, stated so they don't creep in: passwords, sessions, cookies, an
`app_user` table, per-user boards or profiles, per-user data isolation in the
database. This is identification, not authentication (§7).

## 3. Membership is already in the database

The reason no new table is needed:

- `manager (id, sleeper_user_id unique, display_name)` — every Sleeper user in
  any ingested league already has a row, keyed on exactly the id a username
  lookup returns.
- `draft.slot_to_manager` — `{"<slot>": <manager.id>}`, so "I have a seat in this
  draft" is a value lookup in that jsonb.
- `roster_season (league_id, manager_id)` — V5. Covers a league you're in that
  has no ingested draft at all, which `slot_to_manager` alone would miss.
- `draft_pick.manager_id` — a third path, redundant with the first; ignore it.

So: **a league is yours if a `roster_season` row or a `slot_to_manager` value in
it points at your manager id** — the union of the two.

### The chain trap

`league.previous_league_id` chains seasons together, and the picker collapses a
chain into one card with "Seasons" links (`web/src/leagueLineage.ts`,
`DraftPicker`'s `leagueLineages(drafts)`). If you joined the league in 2026, you
have no `roster_season` row and no draft seat in the 2025 league — a naive
membership filter drops it, and the card silently loses a season link that used
to work. Scoping must therefore be **chain-aware**: take the leagues you are a
member of, then walk `previous_league_id` (a recursive CTE) and include every
predecessor. Walk backwards only; a *successor* league you were dropped from is
genuinely not yours.

## 4. Backend

### 4a. Sleeper passthrough — new `api/SleeperUserController.java`

`SleeperClient` already has both primitives; neither is called by anything today.

    GET /api/sleeper/user/{usernameOrId}
        -> { sleeperUserId, username, displayName, avatar }
        404 when Sleeper has no such user (it answers null/404 — verify which,
        RestClient throws on the latter and returns null on the former).

    GET /api/sleeper/users/{userId}/leagues
        -> [ { sleeperLeagueId, name, sport, season, totalRosters, draftId,
               status, previousLeagueId, ingested: bool } ]

Fetches `SleeperClient.leagues(userId, sport, season)` for **both** `nfl` and
`nba` and returns one mixed, sport-tagged list — the picker is already one mixed
list with a sport pill and no switcher (multi-sport-and-rebrand.md Phase 6), so
this matches it. `ingested` is a lookup against `league.sleeper_id`; it drives
the "Ready / Set up" state on the card.

**Season trap, and it is the multi-sport class of bug again.** Sleeper's leagues
endpoint requires an explicit season, and "the current season" is not the same
integer in the two sports on the same calendar day. Do **not** hardcode 2026 and
do **not** compute it from `LocalDate`. Call `SleeperClient.state(sport)` per
sport and read its **`league_season`** field — not `season`; they differ during
the offseason, which is exactly when someone opens a draft app. One call per
sport, cacheable for the process lifetime if it ever shows up in a profile. If a
sport-specific rule creeps in beyond that, it belongs behind `SportRules`, not in
the controller.

Do **not** upsert a `manager` row on username lookup. A manager with no league
would show up on `/managers`, and the row appears on its own the moment their
league is ingested.

### 4b. Scoped drafts — `DraftRepository.allWithLeagueFor(sleeperUserId)`

Same projection as `allWithLeague()` (the `DraftSummary` record is unchanged),
with a `where l.id in (...)` on top:

    with me as (select id from manager where sleeper_user_id = :uid),
    mine as (
        select rs.league_id from roster_season rs join me on me.id = rs.manager_id
        union
        select d.league_id from draft d, me
         where exists (select 1 from jsonb_each_text(d.slot_to_manager) x
                        where x.value = me.id::text)
    ),
    chain as (          -- backwards along previous_league_id; the chain trap above
        select l.id, l.previous_league_id
          from league l join mine on mine.league_id = l.id
        union
        select p.id, p.previous_league_id
          from league p join chain c on p.sleeper_id = c.previous_league_id
    )
    select ... from draft d join league l on l.id = d.league_id
     where l.id in (select id from chain)
     order by d.start_time desc nulls last, d.season desc, d.id desc

`allWithLeague()` stays, unfiltered, as the no-identity fallback. Verify the
recursive CTE terminates on the real data — `previous_league_id` is Sleeper's and
nothing enforces acyclicity.

### 4c. Identity as a request header

`X-Sleeper-User: <sleeperUserId>`, read with
`@RequestHeader(value = "X-Sleeper-User", required = false) String user` on the
handful of endpoints that care. No filter, no argument resolver, no
request-scoped bean — four annotations is less machinery than any of those.

A header rather than a query param because `web/src/api.ts` funnels every call
through one `apiFetch`, so it is one line there versus a param at every call
site. `useLiveDraft`'s `EventSource` cannot send headers — check that nothing it
hits needs identity (`/live-stream` streams draft state; `mySlot` arrives
separately via `getSeats`, so it is fine). Do not add an identity requirement to
that route.

| Endpoint | Use |
|---|---|
| `GET /api/drafts` | scope the list (§4b); absent ⇒ unfiltered, as today |
| `GET /api/drafts/{id}/seats` | `mySlot` |
| `POST /api/mocks/from-draft/{id}` | default seat when `?mySlot=` is absent |
| `GET /api/leagues` | scope, if anything still reads it |

`OwnerSlot.resolve` gets an overload taking an explicit `String sleeperUserId`,
with the existing `OwnerProperties` one delegating to it. **Precedence: header
first, configured owner as fallback.** Keeping the fallback means Allan's own
deploy behaves identically with an empty localStorage, and every existing test
keeps passing unchanged.

## 5. Frontend

### 5a. `web/src/user.ts` — the stored identity

    export type BkUser = { sleeperUserId: string; username: string
                           displayName: string; avatar: string | null }

localStorage key `bk.user.v1` — versioned so a shape change is a clean reset
rather than a crash on someone's stale JSON. Wrap the parse in try/catch and
treat garbage as signed out. Export a module-level `currentUserId()` for
`apiFetch` to read synchronously **and** a React context/hook for components.
Two accessors on purpose: `api.ts` must not import React state, and components
must re-render on a user switch.

### 5b. The gate

`SignIn.tsx`: one input ("Your Sleeper username"), submit → `getSleeperUser()`,
three distinct states — resolving, "no Sleeper user called X" (their typo, the
likeliest case), and "couldn't reach Sleeper" (not their fault; offer retry). On
success, store and land on the picker. Accept a pasted `sleeper.com/user/...`
URL the same way `DraftPicker.leagueIdFrom` already accepts a league URL; people
paste.

`App.tsx`: `/` renders `<SignIn/>` when there is no stored user, `<DraftPicker/>`
otherwise. Other routes render regardless. The header gets the user's avatar and
display name on the right, with switch-user / sign-out (sign-out clears the key;
there is nothing server-side to revoke).

### 5c. Picker: two lists

- **Your leagues** — scoped `getDrafts()`, the existing league-card grid,
  unchanged except that it is now filtered.
- **From Sleeper** — leagues off `/api/sleeper/users/{id}/leagues` with
  `ingested: false`. Same card shape (crest, sport pill, size) so the two lists
  read as one thing, with the primary action reading **Set up** instead of
  Mock draft / Draft board.

An empty *Your leagues* with a populated *From Sleeper* is the first-run state,
and it should look deliberate rather than like a failed fetch — reuse the honest
null-vs-empty distinction the picker already makes.

### 5d. Ingest on entry, staged in the frontend

Clicking **Set up** turns that card into a progress card in place. Do **not**
call `POST /api/ingest/all/{id}`: drive the four sub-endpoints in sequence from
the frontend —

    /api/ingest/players?sport=<sport>   Sleeper's ~5MB dump
    /api/ingest/league/{id}             the chain crawl
    /api/ingest/adp?sport=<sport>
    /api/ingest/board?sport=<sport>     board rebuild + profile refit

Two reasons, both already in DEPLOY.md: `all` does three sequential Sleeper
crawls plus a rebuild and will exceed a 30–60s platform HTTP timeout, and those
sub-routes exist precisely so it can be split. The bonus is free staged progress
— "Fetching players / Reading your league's history / Building the board" —
which is the difference between a 40-second spinner and a 40-second thing that is
visibly working. On failure, name the stage that failed and keep the retry on
that card.

`sport` comes from the Sleeper league object, so this path needs no inference
call.

## 6. Measure before building 5d

Do this first. It takes 30 minutes and it decides how much progress UI is worth
building. With the backend up and a league not yet ingested, time each of the
four stages separately. Two numbers matter: total wall clock for a first-ever
ingest (players dominates), and total for a second user adding a second league
(players already there, so it is crawl + rebuild).

Two things to check while there, because opening the door makes both reachable:

- **`/api/ingest/board` is global.** It rebuilds the whole shared board and
  refits every manager profile for that sport. Two people setting up leagues at
  once means two concurrent rebuilds. Find out whether that is merely slow or
  actually unsafe (`BoardService.rebuild`, `ProfileService.persistFitted`) before
  N people can trigger it. If unsafe, the cheap fix is a per-sport lock or a
  single in-flight rebuild, not a redesign.
- **`adp_at_time`.** Re-ingest is the documented fix for a post-rebuild wipe,
  but drafts older than the ~4-day FFC snapshot window can never be backfilled.
  A stranger's league gets its history ingested against whatever board exists
  today; that is expected, not a bug — but check that adding one does not
  disturb the existing leagues' `adp_at_time`.

## 7. What to write down, not build

In DEPLOY.md, under a new "Multiple people" heading:

- **This is identification, not authentication.** Anyone can type `popsharky`
  and get Allan's seat highlighting and Allan's league list. There is no password
  and nothing is hidden. That is an accepted tradeoff for a friends-and-league-
  mates tool, and it stops being acceptable the moment anything private lands in
  the database.
- **The API token is shared.** `VITE_API_TOKEN` is in every visitor's devtools,
  so every visitor can call `/api/ingest/*` and `/api/drafts` unscoped. Nothing
  tomorrow changes this. The real fix is a server-side proxy or real auth; the
  interim mitigation, if it matters, is same-origin hosting so at least nothing
  new is exposed.
- **Live mode's EventSource still cannot send the token** (the existing gap at
  the top of DEPLOY.md). It bites a split-origin deploy with `API_TOKEN` set. If
  draft night matters more than the deploy shape, either keep it same-origin or
  accept the token as a query parameter on that one route.
- **`APP_OWNER_SLEEPER_USER_ID` is now a fallback**, not the identity. Say so in
  `.env.example`, which currently says the opposite.

## 8. Tests

Backend (296 today, real Postgres):

- scoped drafts: member via `roster_season`; member via `slot_to_manager` only;
  predecessor season included via the chain walk; a league you are in by neither
  path excluded; unknown user id ⇒ empty, not everything. That last one is **the
  failure mode that matters** — a null-id bug that silently returns the
  unfiltered list looks exactly like working software to the one person who is in
  every league.
- `OwnerSlot`: header id wins over the configured owner; configured owner used
  when the header is absent; both absent ⇒ null.
- Sleeper passthrough: unknown username ⇒ 404; `ingested` true and false.

Frontend (80 today):

- no stored user ⇒ gate renders at `/`; stored user ⇒ picker renders.
- corrupt `bk.user.v1` ⇒ treated as signed out, no crash.
- picker splits ingested vs Sleeper-only; Set up runs the four stages in order
  and names the failing stage on error.

## 9. Order of work

1. Measure (§6). ~30 min.
2. `SleeperUserController` + the two passthrough endpoints, season via
   `state(sport).league_season`. ~1h.
3. Scoped query + chain CTE + tests. ~1.5h.
4. `X-Sleeper-User` on the four endpoints, `OwnerSlot` overload. ~45 min.
5. `user.ts`, the gate, header identity. ~1.5h.
6. Picker two-list + staged setup card. ~2h.
7. Docs (§7) + a `docker compose --profile full` rehearsal. ~1h.

Steps 2–4 are independently shippable and leave the app working for Allan at
every point; 5–6 are what a second person actually sees. If the day runs short,
cut 6's staged progress down to a plain spinner before cutting anything in 3.
