# draft-sim → Ball Knowers: rebrand and a second sport

Written 2026-09-07. Design doc for the work; nothing in the "Phases" section has
been built at the time of writing. Companion to `HANDOFF.md`. Promotes
`ideas/README.md`'s parked bullet ("Basketball as a second `SportRules`
implementation (the seam exists; nothing else does)") into planned work.

**Amended after review, same day.** An adversarial cold review of the first draft
ran the numbers on three things this doc had asserted and found three of them
wrong. Corrected in place below, each marked; the substance of what changed:

- The `allWithLeague()` / `LeagueRepository.all()` "fix" was **backwards** — those
  back the deliberately-mixed picker list and must gain a `sport` column, not a
  sport filter.
- `DraftContextFactory` has a **second** by-type coupling this doc missed: it
  passes `scoring.football()` unconditionally into every `DraftContext`.
- **The Sleeper position lock will almost never bind**, and the review has the
  arithmetic. The real basketball work is `rosterNeed`, which the first draft
  underweighted.

**Phases 3 and 5 reconciled 2026-09-08.** The amendment above was applied to
Phases 0-2 and 4 only; Phases 3 and 5 still carried the pre-review text and
contradicted this header. Phase 3 has been re-cut into 3a/3b/3c so the effort
sits where the review says the work actually is, and Phase 5 has gained the
`reversal_round` verification recipe the "Assumed, not verified" section
promises but never contained. One bullet in the verified section
("a position lock binds for real in the last rounds") was wrong and is corrected
in place.

Two changes, deliberately kept separable:

1. **Branding.** The project takes the name of the league brand it was always
   about — **Ball Knowers** — to match the Twitter account. Branding and docs
   only.
2. **Multi-sport.** Basketball becomes a real second sport, reaching
   ingest → board → manager profiles → batch Monte Carlo sim.

---

## Verified against the live Sleeper API this session

Everything in this section was executed, not reasoned about. Sleeper user
`popsharky` / `1122386008709910528`.

**The NBA league chain exists and ingests like football does.**

    Ball Knowers   2024  league 1141438340626231296  draft 1141438341108498432  complete
    Ball Knowers   2025  league 1229352720222134272  draft 1229352720230514688  complete
    Ball Knowers   2026  league 1339351318115946496  draft 1339351318128517120  pre_draft

`previous_league_id` chains 2026 → 2025 → 2024, exactly the shape
`LeagueIngestService.ingestChain` already walks. All three: 12 teams, snake,
`draft.settings.rounds = 14`, roster
`[PG,SG,G,SF,PF,F,C,UTIL,UTIL,BN,BN,BN,BN,BN]`.

- **Two complete drafts, 168 picks each, zero keepers** (`is_keeper` null
  throughout). Same order of history football had when profiles were first fit,
  so `shrinkage.k` does the same job here — a manager's own basketball history
  will carry 1/3 weight and mostly be the league average. That is the correct
  outcome at this sample size and should be said out loud in the UI, as it is
  for football.
- **10 of the 12 managers are the same Sleeper user ids in both leagues.** This
  is what turns the `allCompletedPicks()` bug below from theoretical into
  certain.
- **2092 of 2109 NBA `player_id`s collide with NFL `player_id`s.** Sleeper reuses
  the numeric id space per sport: id `1290` is Jermaine Kearse (WR, nfl) and
  Keith Bogans (SG, nba). Already handled — `player` is `unique(sport,
  sleeper_id)` with a global `bigserial id`, and `PlayerRepository` filters on
  `sport` everywhere.
- **All 168 players drafted in 2025 resolve in `/players/nba`, and all 168 have a
  `search_rank`.** Zero unmatched. The board's existing search_rank +
  observed-order derivation has real inputs for basketball.
- **Today's plain-snake math already reproduces all 168 observed `draft_slot`
  values** for the 2025 draft. `DraftSlot.slot()` is correct as written for
  `reversal_round = 0`.
- **The upcoming 2026 draft has `reversal_round = 3`.** Both completed drafts
  have `reversal_round = 0`, so this case has never been exercised. Plain snake
  is wrong from round 3 onward for the draft we actually care about.
- **79% of the NBA pool (1656 of 2109) is multi-position eligible**, and **66% of
  players actually drafted (111 of 168)** are. Most common eligibility sets among
  drafted players: `(PG,SG)` 36, `(C)` 27, `(PG)` 20, `(PF,SF)` 20, `(SF,SG)` 19,
  `(C,PF)` 16, `(PF,SF,SG)` 15.
- **`enforce_position_limits = 1`** on the 2025 and 2026 drafts.
- **NBA roster slots (14) equal NBA draft rounds (14)** — as football's 15 equal
  its 15. Every roster finishes exactly full. That makes the lock *reachable* in
  the last round, but see the review's arithmetic below: with 5 bench slots and
  2 UTIL, it is reached by "roster full", not by any position limit.
- **There is no FFC for basketball.** `fantasyfootballcalculator.com` is a
  football-only vendor. Basketball's board is search_rank + observed order, which
  is where football started and is the weakest link there too.

Added by the review, also executed:

- **The NBA board survives `dropOffRoster`.** 1508 of 2109 players have
  `team == null`; **601 survive**, comfortably above the 168 picks
  `DraftContextFactory` requires. Only 6 of the top 200 by `search_rank` are
  dropped (Cam Thomas, Jaden Ivey, Westbrook, Lonzo Ball, Chris Paul,
  Valančiūnas — all FA/RET). The top 400 loses 103.
- **The largest `fantasy_positions` set is 4**, so a 5-bit mask over
  `{PG,SG,SF,PF,C}` is sufficient.
- **The position lock never binds in a real draft.** Max count of any single
  position on any 2025 roster: PG/SG/SF/PF = 5, C = 4. See Phase 3.
- **`player_alias` is dead code.** Declared at `V2__ffc_adp.sql:20`, zero reads
  and zero writes anywhere in the Java source.

## Assumed, not verified

- **What "Sleeper locking mode" means.** Read here as: mirror Sleeper's
  `enforce_position_limits` — a player carries his `fantasy_positions` *set* and
  is draftable only while some slot he is eligible for (`PG/SG/G/SF/PF/F/C/UTIL/BN`)
  is still open. Phase 3 is where this gets corrected if the reading is wrong.
- **What `reversal_round: 3` means.** Read here as "flip the normal snake parity
  for every round ≥ 3": R1 forward, R2 reverse, R3 reverse, R4 forward. **This
  has not been executed against real data** and cannot be from what exists —
  both completed NBA drafts are `reversal_round: 0`, and the only draft that uses
  it is `pre_draft` with no picks. Verification recipe in Phase 5; run it before
  writing the `DraftSlot` change, not after.
- **Basketball's weights.** They start as a copy of football's. Nothing is fit
  for either sport. Per this repo's rule, they stay labelled as guesses and do
  not get tuned to make a later measurement agree.

---

## What the seam actually is today

The seam was left open on purpose and the door is real, but narrower than
`ideas/README.md` implies.

- `domain/Sport.java` — `NFL("nfl")`, `NBA("nba") // seam only; not implemented in v1`.
- `sport/SportRules.java` — 8 methods. Four are genuinely called through the
  interface: `isDraftable` (`PickDecider:83`), `prepareLineup` (`:102`),
  `rosterNeed` (`PickScorer:67`), `value` (`DraftContext:80,123`),
  `startingLineupValue` (`PowerRankingService:122`). **`isEligible(Player, String
  rosterSlot)` and `lineupValue(Object)` are implemented and never called** —
  `isEligible` is exactly the method basketball needs, sitting unused.
- `config/weights.yml` already nests under `scoring.football:`.
- `player`, `league`, `adp_snapshot` and `manager_profile` are already
  sport-scoped in the schema; `manager` deliberately is not, because a Sleeper
  user is one identity across sports.

**What it is not.** It is a *substitution* seam, not a *selection* seam.
`FootballRules` is a bare `@Component` injected by type in two places
(`DraftContextFactory:36,39`, `PowerRankingService:42,47`), so a second
implementation fails startup with `NoUniqueBeanDefinitionException`. And `Sport`
is never a runtime variable — `Sport.NFL` is a hardcoded literal at roughly 30
call sites, including `SimulationService:47`, where it is a local.

---

## Non-goals

- Live draft poller / SSE tracking for basketball.
- Mock draft room (`/mock/new`) for basketball.
- Power rankings, league history, weekly points for basketball.
- Any real ADP feed for basketball.
- Renaming the Java package `com.ballknowers.draftsim`, the Postgres
  database/user `draftsim`, the `draftsim.*` config prefix, the docker-compose
  container names, the repo folder, or the git remote. The backend package was
  already moved from `com.example` to `com.ballknowers`; the `draftsim` leaf and
  everything in it stays.
- Backtesting or calibration of either sport. Unchanged from `HANDOFF.md`.

---

## Phases

Each is independently shippable and leaves football working.

| # | Phase | Shape |
|---|---|---|
| 0 | Branding and docs | Small, no behaviour change |
| 1 | `Sport` as a runtime value | Wide but shallow |
| 2 | Schema V6 + contamination fix | One migration, three queries, one failing-first test |
| 3a | Widen `Position`, sport-scope every `values()` | Mechanical |
| 3b | The Sleeper eligibility lock | Small; almost never binds |
| 3c | Basketball's lineup and `rosterNeed` | **The hard one** |
| 4 | `BasketballRules` + per-sport config | Moderate |
| 5 | NBA ingest, board, snake reversal | Moderate; gated on a `reversal_round` check |
| 6 | Frontend | Moderate |

### Phase 0 — Branding and docs

- `web/index.html` — title, and the metadata that does not exist today at all:
  favicon link, description, OG and `twitter:card` tags. The motivation is a
  Twitter account and the page currently has no social-card metadata of any kind.
- New `web/public/` with a favicon; there is no `web/public/` directory today.
- `web/src/App.tsx:61` — nav brand text.
- `web/package.json:2` — `"ball-knowers-web"`.
- Doc titles in `README.md`, `HANDOFF.md`, `AGENTS.md`, `DEPLOY.md`, and the
  `claude/*.md` files that open `# draft-sim — ...`.

**Deliberately not touched:** `settings.gradle.kts:5` (`rootProject.name`, which
names the jar), `application.yml:7` (`spring.application.name`),
`.claude/launch.json`, `DRAFTSIM_PROXY_TARGET`, the `draftsim.*` config prefix,
the DB name/user, and the docker-compose container names.

**The name now means two things, and that needs a rule.** "Ball Knowers" is
already the literal name of the real Sleeper leagues — `(Foot) Ball Knowers` for
football, `Ball Knowers` for basketball — referenced at `config/weights.yml:62`,
`README.md:95-96`, `HANDOFF.md`, `web/src/leagueLineage.test.ts:6`,
`web/src/pages/DraftPicker.tsx:178`, `web/src/styles.css:281`. Rule: the
**product** is written bare ("Ball Knowers"); a **league** is written as a quoted
Sleeper name with its sport and season, which is what already disambiguates the
rows in `README.md`'s league table. Recorded in `AGENTS.md` so it does not get
relitigated.

### Phase 1 — Make `Sport` a runtime value

Pure refactor. Football behaviour must be identical afterwards; that is the
acceptance test, and it is checked by running a simulation, not by reading code.

- Add a `SportRulesRegistry` (`@Component` over `List<SportRules>`, indexed by
  `rules.sport()`); change `DraftContextFactory:36,39` and
  `PowerRankingService:42,47` to resolve by sport instead of injecting by type.
- **Amended after review — there is a second by-type coupling in the same file.**
  `DraftContextFactory.build()` also passes **`scoring.football()`**
  unconditionally into the `DraftContext` constructor; `DraftContext` exposes it
  as `cfg()` and `PickScorer:34` is built from it. Resolving `SportRules` by
  sport while still handing every context football's scoring block would give
  basketball football's `valueDecay`, `adpScale`, `benchFloor`, `runWindow` and
  `latestRounds`. **Both** must resolve by sport, which means `build()` needs a
  `Sport` (or `LeagueSettings` must carry one).
- Replace the `Sport.NFL` literals in `SimulationService:47`, `IngestController`,
  `LeagueController`, `ManagerController`, `LeagueHistoryController`,
  `MockDraftService`, `LiveDraftPoller:294`.
- **Where each endpoint gets its sport:**
  - `/api/ingest/all/{leagueId}`, `/api/ingest/league/{id}`,
    `/api/ingest/league-history/{id}` — **infer it**. Sleeper's league JSON
    carries a `sport` field (verified: `"sport":"nba"`). Fetch the league first
    and read it. No route change and no frontend change, which means the "add a
    draft by Sleeper link" flow on the picker screen supports basketball for
    free.
  - Draft-scoped routes and `/api/sims` — derive via `draft → league.sport`. One
    new repository method.
  - Routes with neither in the path (`/api/ingest/players`, `/api/ingest/board`,
    `/api/board`, `/api/managers`) — an explicit `?sport=` defaulting to `nfl`,
    so every curl in `README.md` and `DEPLOY.md` keeps working verbatim.
- Rename the nested record `ScoringProperties.Sport` → `SportScoring`. It already
  collides conceptually with `domain.Sport`, and Phase 4 adds a
  `forSport(domain.Sport)` lookup that would be unreadable otherwise.

### Phase 2 — `V6__multi_sport.sql` and the contamination fix

Migrations are append-only; V6 is next.

- `draft.reversal_round int not null default 0` — needed by Phase 5.
- `mock_draft_session.sport text not null default 'nfl'` — the mock room is out
  of scope, but the column is one line and migrations are append-only, so adding
  it now avoids a V7 later.
- **Amended after review: `player_alias.sport` is cut.** The first draft added
  it. The table is declared at `V2__ffc_adp.sql:20` and is **never read or
  written by any Java code** — `PlayerMatcher` is built from
  `players.findAll(sport)` at `FfcAdpService:83` via the no-alias overload.
  Adding a column to a dead table is noise.

**The bug this work would otherwise expose — amended after review, and the
narrowing matters.** `DraftRepository:210-224` `allCompletedPicks()` joins
`draft_pick → draft` with no sport filter and no join to `league`. The first
draft of this doc said that contaminates reach, tilt and priors alike. It does
not, and the reason is worth writing down: `posById` is built from
`players.findAll(sport)` (`ProfileService:78`), so NBA picks hit `pos == null`
and `continue` at `:123` (tilt) and `:251` (priors). **Priors and tilt are
already safe.** The damage is confined to two places:

- `ProfileService:107-108` — `reachByManager` takes NBA reaches, but only for
  picks with a non-null `adp_at_time`.
- `ProfileService:91` — `draftsByManager` takes NBA draft ids **unconditionally**.
  That inflates `observed` at `:141`, which is the shrinkage N at `:151-152` and
  is handed to `fitTilt` at `:156`. **This fires the moment an NBA draft is
  ingested, even with zero NBA board coverage**, and silently under-shrinks every
  football manager toward their own thin fitted values. Since **10 of 12 managers
  play in both leagues**, it hits nearly every seat.

So the fix is a `join league l on l.id = d.league_id where l.sport = ?` on
`allCompletedPicks()` **only**.

**And the other two must NOT be filtered.** The first draft got this backwards.
`DraftRepository.allWithLeague()` (`:179`) backs the home picker — the single
mixed, sport-tagged list this design deliberately chose. Filtering it by sport
would break that decision. It needs `l.sport` **added to the `DraftSummary`
record and select list**. `LeagueRepository.all()` (`:109`) has two callers:
`LeagueController:69` (`GET /api/leagues`, wants everything) and
`SimulationService:53-55`, which immediately filters to
`l.id() == draft.leagueId()` — a sport filter there is a no-op. Leave both
unfiltered.

### Phase 3 — Positions, the eligibility lock, and basketball's lineup

**Re-cut 2026-09-08 to match the review.** The first draft called this "the hard
one" and then spent itself on the lock. The review's arithmetic says the lock is
nearly inert; the hard part is `rosterNeed`. Both still get built — the effort
estimate moves, not the scope. Three independently reviewable pieces:

| | | |
|---|---|---|
| 3a | Widen `Position`, sport-scope every `values()` | Mechanical, football-neutral |
| 3b | The eligibility lock | Small, correct, almost never binds |
| 3c | Basketball's lineup and `rosterNeed` | The actual hard one |

3a and 3b can ship together. **3c does not start until 3a/3b are merged and
football's simulation baseline is confirmed unmoved** — it is the only piece that
can move football's numbers by accident, and it should not be diagnosing a
`Position` widening at the same time.

#### 3a — widen `Position`, sport-scope every `values()`

`domain/Position.java` is a closed enum `{QB,RB,WR,TE,K,DEF}` used directly — not
behind the seam — by `PickScorer`, `PickDecider`, `PositionalPriors`,
`ProfileService`, `RosterState`, `BoardService`, `PlayerMatcher`,
`LeagueSettings`, `PlayerIngestService`.

Widen it to `{QB,RB,WR,TE,K,DEF, PG,SG,SF,PF,C}`, each constant tagged with its
`Sport`, plus `Position.forSport(Sport)`. This keeps `Position` usable as an
`EnumMap` key and an array index, so `PickDecider`'s scratch arrays (`:38-39`)
and every switch site keep compiling. `G`/`F`/`UTIL`/`FLEX`/`BN` are **roster
slots, not positions**, and stay out of the enum.

**The Dirichlet risk, checked rather than assumed.** `ProfileService:262` sets
`int k = Position.values().length` and uses it as the denominator
`(count + alpha) / (total + alpha * k)`. Going from 6 to 11 takes a
~24-observation bucket from `24 + 8x6 = 72` to `24 + 8x11 = 112`, scaling every
football prior in that bucket by ~0.64.

That first looked like it would move football's output. On reading the code it
probably does not, and the reason matters: the bucket comes from `pickNo`, not
from the candidate, so within a single pick every candidate shares the
denominator, `logProbability` shifts them all by the same constant, and softmax
is shift-invariant.

What does move, and why the loops must be sport-scoped anyway:

- The fallback paths are not uniformly shifted — `PositionalPriors:81`
  (`overall.getOrDefault(pos, 1.0 / Position.values().length)`) and the `1e-6`
  miss at `:82`.
- Five basketball positions would each take `8/112 ~ 0.071` of the mass in a
  football profile, and `forBucket()` hands those maps out for display — phantom
  `PG`/`C` rows in a football manager's P(position | round) table.

**The ten sites split into two categories, and getting them backwards is how
this phase silently breaks football.**

*Denominators and iteration — these MUST be sport-scoped:* `PositionalPriors:81`
(the `1.0 / values().length` fallback), `PositionalPriors:107`,
`ProfileService:220`, `ProfileService:262` (the Dirichlet `k`),
`ProfileService:268,276`, `PickDecider:108`.

*Array widths — these must NOT be:* `PickDecider:38-39` (`positionalCache`,
`runCache`) and `FootballRules:30` (`POSITIONS`), which the first draft of this
doc missed entirely. All three size a scratch array by `values().length` and
index it by `pos.ordinal()`. Ordinals are **global**, so a width of 11 is
wasteful by five doubles and correct, while a per-sport width would need a dense
per-sport index — a much larger change for no gain. Leave them global with a
comment saying why. (An earlier version of this paragraph listed
`PickDecider:38-39` among the sites that "become sport-scoped". That was wrong,
and it is exactly the mistake that would rescale football's priors.)

Verified 2026-09-08: those ten sites are still the complete set.

#### 3b — the eligibility lock: build it, don't budget for it

**It is correct to build and it will almost never fire.** The NBA roster is
`PG,SG,G,SF,PF,F,C,UTIL,UTIL` + 5 `BN` = 14 slots over 14 rounds. Capacity for a
player eligible at exactly one position: a pure centre 8 (`C` + 2 `UTIL` + 5
`BN`), a pure point guard 9 (`PG` + `G` + 2 `UTIL` + 5 `BN`). The most anyone
actually rostered at a single position across every real 2025 roster was **5**
(PG/SG/SF/PF; `C` peaked at 4). So `isDraftable` reduces, for this league shape,
to "roster not full" — which the simulator already enforces by construction.

Build it anyway: Sleeper sets `enforce_position_limits = 1`, a simulation that
can produce a roster Sleeper would have rejected is wrong even if it never does,
and the implementation is a dozen lines. Do not spend a design pass on it.

- Implement `isEligible(Player, String rosterSlot)`, the seam's dead method
  (implemented on `FootballRules`, zero callers today), and give it its first
  caller. Slot to eligible positions is owned per sport: football
  `FLEX <- {RB,WR,TE}`; basketball `G <- {PG,SG}`, `F <- {SF,PF}`, `UTIL <- any`,
  `BN <- any`.
- `isDraftable` becomes the lock: undraftable when every slot he is eligible
  for, bench included, is full.
- **A bitmask test, not a matching problem**, and that matters because
  `RosterState`'s own comment records ~25M calls per run. Sleeper's
  `enforce_position_limits` only asks *"is there an open slot this player
  fits?"* — never *"can I still field a legal lineup afterwards?"* So the check
  is `(eligibleSlotMask & openSlotMask) != 0`: a precomputed `int` mask per
  `Position` of the slot kinds that accept it, and a per-seat mask of which slot
  kinds still have a vacancy. O(1), no allocation. Bipartite maximum matching
  answers the stronger question Sleeper does not ask, and is out of scope *here*
  — but see 3c, where the same structure returns for a different reason.
- **Where the open-slot mask lives — corrected 2026-09-08.** An earlier version
  of this section had `RosterState` gain slot-occupancy counters beside its
  `byPosition` `EnumMap`. That is the wrong home: maintaining the mask on
  `add()` forces `RosterState` — which is sport-agnostic and shared — to know a
  sport's slot model, and it forces an assignment decision at insert time.
  `prepareLineup` already exists to compute exactly this kind of once-per-pick,
  roster-wide structure, and its result is already threaded to every candidate.
  So the mask is computed there and `isDraftable` takes the prepared lineup:

      boolean isDraftable(BoardEntry entry, Object lineup, int round, int totalRounds)

  `FootballRules` ignores the new parameter and keeps its `latestRounds` gate
  unchanged. `RosterState` is not touched at all. The one adjustment is in
  `PickDecider.choose`, where `prepareLineup` currently runs *after* the
  candidate filter loop and has to move above it — it does not depend on the
  candidates, so this is a reorder, not a behaviour change, and football's
  output must prove it by not moving.

**The eligibility data already exists and is being discarded.** `Player.positions`
is already a `List<Position>` from the `text[]` column; `BoardEntry.position()`
just calls `player.primary()`, which returns `positions.getFirst()` and defaults
to `Position.WR` when empty, under the comment "Multi-eligibility is a basketball
problem." No schema change and no data plumbing — engine logic only. `primary()`
survives for display. The largest observed `fantasy_positions` set is 4, so a
5-bit mask over `{PG,SG,SF,PF,C}` is sufficient; 66% of players actually drafted
are multi-eligible.

#### 3c — `rosterNeed` and `prepareLineup`: why football's model does not port

This is the piece the first draft underweighted. `FootballRules.prepareLineup`
builds a `Lineup` record of (dedicated starters keyed by `Position`) plus (one
FLEX pool, filtered by `Position.isFlexEligible`), and `rosterNeed` scores a
candidate in O(1) off that. Three of its assumptions are football-only:

1. **A player has one position.** `rosterNeed` indexes `countByPos` and
   `weakestStarterByPos` by `candidate.position().ordinal()` — that is
   `primary()`, the first element of the list. Two thirds of drafted NBA players
   are eligible somewhere else.
2. **There is one flex tier.** Basketball has three, nested: `G <- {PG,SG}`,
   `F <- {SF,PF}`, `UTIL <- any`. Football's greedy "fill dedicated, then FLEX
   from the leftovers" is optimal precisely because FLEX accepts a superset of
   the dedicated slots it competes with — the comment on `startingLineupValue`
   says exactly that. With three nested tiers and multi-eligible players the
   argument no longer holds.
3. **`LeagueSettings.dedicatedStarters()` (`:32-41`) and `flexSlots()` hardcode
   the six football positions outside the seam.** Both are consumed directly by
   `FootballRules` and must move behind it.

So "what is this roster's starting lineup worth" becomes a **maximum-weight
bipartite matching** (players to slots) rather than a greedy fill. The
constraint that decides the design is cost: ~25M `rosterNeed` calls per run
forbids running a matching per candidate.

**Design direction, to be validated in 3c's own pass rather than settled here.**
Keep football's *shape* — an O(1) `rosterNeed` reading a table that
`prepareLineup` computes once per seat per pick:

- A player's eligibility set is one of at most 32 masks over `{PG,SG,SF,PF,C}`,
  and far fewer occur — the seven most common sets cover 153 of the 168 players
  drafted in 2025.
- `prepareLineup` solves the current roster's matching once (9 starting slots
  against at most 14 players is trivial at that frequency), then, for each mask,
  computes the single augmenting step that adding one player of that mask would
  allow: which slot he takes, whom he displaces, and where the displaced player
  cascades to.
- `rosterNeed` is then `table[mask]` plus the same own-value-versus-displaced
  arithmetic football already does, returning the same
  `benchFloor + (1 - benchFloor) * captured` shape.

Fallback if that proves wrong or too fiddly: greedy assignment in
most-constrained-slot-first order (`C, PG, SG, SF, PF, G, F, UTIL`), accepting a
bounded and *measured* suboptimality rather than an assumed one. Either way the
choice gets written down with the number that justified it.

**Football must be bit-identical through all of 3c.** `FootballRules` keeps its
existing greedy implementation untouched; nothing in 3c becomes shared code
until there is a second working implementation to factor against.

### Phase 4 — `BasketballRules` and per-sport config

- New `sport/BasketballRules.java`, registered through the Phase 1 registry.
- `weights.yml` gains `scoring.basketball:`. `ScoringProperties` widens to carry
  both sports with a `forSport(Sport)` lookup. Two named components rather than a
  map keeps `scoring.football:` exactly where it is, at the cost of a code change
  for a third sport — an honest trade at two. `latestRounds` is already
  `Map<String,Integer>` keyed by position name, so basketball supplies an empty
  map and `FootballRules:51-57` keeps its hard failure for football's.
- `board.observedDrafts` becomes per-sport (today a flat untagged list at
  `weights.yml:62`); basketball gets the two complete NBA drafts.
  `priors.buckets` becomes per-sport (14 for NBA). `adp.ffc` gets an explicit
  football-only note.

### Phase 5 — Basketball ingest, board, snake reversal

`SleeperClient` is already sport-parameterized (`allPlayers(sport)`,
`leagues(user, sport, season)`, `state(sport)`), and `PlayerIngestService.ingest(Sport)`
and `BoardService.rebuild(Sport)` already take a sport. What changes:

- `PlayerIngestService.fantasyPositions()` (`:100-106`) → `Position.fromSleeper`
  needs the basketball cases, and must keep dropping the 30 `["DEF"]` entries in
  the nba payload.
- `BoardService.dropOffRoster`/`draftable()` (`:166-195`) reasons about "not on
  an NFL roster"; `loadFfc` (`:203-212`) is skipped for basketball;
  `observedPickNumbers()` (`:215-233`) reads the now per-sport observed list.
- `PlayerMatcher:32,53-66` — the DEF-by-team-abbreviation branch is football-only.
- `LeagueIngestService:133` defaults `rounds` to 15; read it from the draft, and
  persist `reversal_round`.
- **Snake reversal is smaller than it sounds.** All of it lives in one file, two
  methods: `DraftSlot.slot()` (`:16`) and `picksForSlot()` (`:23`), each keyed on
  the same `round % 2 == 1` predicate. Replace it with
  `isForward(round, reversalRound)` — `round % 2 == 1` while
  `round < reversalRound`, flipped from `reversalRound` on. At `reversalRound = 3`
  that is R1 forward, R2 reverse, **R3 reverse again**, R4 forward.
  `reversalRound = 0` is exactly today's behaviour, verified to reproduce all 168
  observed 2025 slots. `LiveDraftPoller.onTheClockSlot:326` carries its own copy
  of the formula and should call `DraftSlot` instead of growing a second
  parameter.

#### Verifying `reversal_round: 3` before writing that change

**Added 2026-09-08.** The "Assumed, not verified" section above promises a recipe
here, and the section did not contain one. The semantics of `reversal_round: 3`
are an assumption: both completed NBA drafts are `reversal_round: 0`, and the
only draft that uses it (`1339351318128517120`, the 2026 draft that actually
matters) is `pre_draft` with no picks. Getting it wrong misorders every pick from
round 3 on, in the one draft this project exists to simulate. Ordered
cheapest-first; stop at the first step that produces real picks.

1. **Look for one that already exists, at zero cost.**
   `GET /user/1122386008709910528/drafts/{sport}/{season}` returns *every* draft
   the user has touched, mock drafts included, not just league drafts. Sweep both
   sports across every season with data, filter `settings.reversal_round != 0`,
   and keep any whose `status` is `complete`. If one turns up, pull
   `GET /draft/{id}/picks` and compare each observed `draft_slot` against the
   candidate `isForward` formula — the same check that already reproduced all 168
   slots of the 2025 NBA draft. That is a decisive answer for free.
   **Executed 2026-09-08: nothing.** All ten drafts the account has ever touched
   (nfl 2024-2026, nba 2024-2026; four of them mocks) are `reversal_round: 0`.
   The only draft in reach with `reversal_round: 3` is the `pre_draft` 2026 NBA
   draft itself, which has no picks. Step 1 is closed.
2. ~~Ask Allan for a throwaway mock.~~ **Decided 2026-09-08: sidelined.** Rather
   than block on proving Sleeper's semantics, `reversalRound` becomes a
   **user-editable draft option in the settings popover** — the same place
   temperature and league shape already live. The simulator ships the assumed
   reading as the default, and if a real draft ever contradicts it, the fix is a
   dropdown, not a redeploy. That also covers the case Sleeper does not: a league
   that runs a reversal round the API never reported.
3. **Write the `DraftSlot` change on the assumption, and surface the override.**

**So the change ships on the assumption.** Plain snake is *definitely* wrong for
the 2026 draft, so shipping the assumed reading beats shipping the known-wrong
behaviour. It ships with the assumption named in a comment on `isForward` and a
unit test pinning the assumed round-3/round-4 ordering explicitly, so a later
contradiction from real data surfaces as a failing test that names the
assumption rather than as a silently misordered simulation — and with the
settings-popover override above, so a wrong assumption is recoverable by the
person running the draft rather than only by the person deploying it.

**The override is Phase 6 work, not Phase 5.** Phase 5 persists
`draft.reversal_round` from Sleeper and honours it; Phase 6 adds the control that
lets a user disagree with it. Listed here so the two do not get separated and the
control quietly never gets built.

### Phase 6 — Frontend

- `web/src/api.ts` — add `sport` to `DraftSummary`, `SeatsResponse`,
  `SimulationResult`; widen the closed position union at `:8`. Lands in the same
  change as the Java records, per this repo's rule.
- One exported per-sport position list replacing the **three duplicated**
  `POSITIONS` constants (`AvailabilityPanel.tsx:16`, `PlayerPicker.tsx:18`,
  `OnTheClockPickInput.tsx:16`) and `pickRun.ts`'s `RUNNABLE` (`:11-14`).
- `teamNeeds.ts:9-20` — `FLEX_ELIGIBLE` / `RECOGNIZED_SLOTS` and the literal
  `'FLEX'` branches (`:56,75,99`) become the same slot-eligibility model as the
  backend; `needLabel`'s `'Fills FLEX'` becomes slot-named.
- `playerName.ts:45-57` — the `DEF` "Seattle Seahawks → Seahawks" case is
  football-only; guard it.
- `styles.css` — five position tokens beside the six at `:86-96`, as an oklch ramp
  along the guard→center continuum so the board still reads as one system, plus
  the matching `.pos.X` (`:464-469`), `.board .cell.pos-X` and hover (`:572-583`),
  and `.chip.pos-chip.X` (`:756-763`) rules. An unknown position currently
  renders unstyled, because class names are built from the raw position string in
  seven files.
- Fix `styles.css:553-554`, which uses `var(--rb)` as the *provenance* colour for
  `.col-head-dot.fitted`. That coupling is accidental and gets worse with eleven
  position tokens.
- `DraftPicker.tsx` — a sport pill on each league card. One list, both sports, no
  switcher.
- **Draft settings popover — a `reversalRound` control.** Per the Phase 5
  decision above: the round from which snake parity flips, `0` meaning never.
  Sleeper's value is the default; the user can disagree with it. This is what
  turns an unverifiable assumption into a recoverable one.

---

## Acceptance criteria

The bar here is live verification; a green suite is not enough, and note that 9
integration tests **skip rather than fail** when Postgres is unreachable, so a
green IT run can be green-by-skipping. Confirm Postgres is actually up first.

1. **Football is unmoved.** Captured 2026-09-08, before Phase 1; re-run and diff
   after Phases 1, 2 and 3. No number moves.

   This needed a code change first: `SimulationService:77` seeded from
   `System.nanoTime()`, so "re-run and diff" was impossible. `SimulationRequest`
   now takes an optional `seed` (null = today's behaviour). **And an empty
   `startState` is not a cold start** — `resolveStartState:108` treats an empty
   map as "not supplied" and replays every pick Sleeper recorded, so a request
   against a `complete` draft simulates nothing and is byte-identical under any
   seed. Every draft in the local DB is `complete`. A baseline must therefore
   pass a *partial* `startState`.

   The two baselines, both `mySlot: 5`, `iterations: 500`,
   `startState: {"1": "9221"}`, `seed: 20260908`, `POST /api/sims`. Two league
   shapes on purpose (14-team and 12-team). Proven live: the same seed
   reproduces the hash exactly; a different seed does not.

   **Hash the response with `name` and `team` stripped from every object.**
   Learned the hard way during Phase 3a/3b: the 14-team baseline "moved", and
   the entire diff was one player's `team` going from `"NYG"` to `null` —
   Darius Slayton was released between two runs an hour apart, and a player
   re-ingest picked it up. His `adp` and `positionalRank` were unchanged, so
   nothing the engine decided had moved at all. Hashing the raw body makes a
   football-parity check fail on real-world roster churn, which trains you to
   ignore it — the worst possible outcome for the one check standing between
   this refactor and a silent regression. Player identity (`id`, `sleeperId`)
   and everything the engine computes stay in the hash; the two mutable display
   fields come out.

       draft 1391509064357273600  (fantasy, 14 teams, 15 rounds)
         sha256 22dbc2d5a408bee4947d73d6f8c6b0ab4e073f296056cafbc2c8a10991ceaacb
       draft 1346366555776126976  ((Foot) Ball Knowers 2026, 12 teams, 15 rounds)
         sha256 6e4ce7bfdb45beb098c3bcb2b2b91d4f6e336a00b667e662d314a084c7fec7c0

   Superseded raw-body hashes, valid only against the player data as it stood on
   2026-09-08 morning: `db192817be…` and `752470a8c4…`.

   **The local Postgres is shared with other sessions**, so player data can
   change under a run. That is what happened here. `PickScorerTest.aPlayerWhoFellPastHisBoardSlotIsValueAndReachingIsNot`
   and `DraftSimulatorTest.theModalBoardStartsWithTheBestPlayerAndStaysNearTheTop`
   stay green.
2. `curl localhost:8080/api/health` — `weightsLoaded` stays true after the
   `ScoringProperties` change. A mis-shaped yml unbinds silently.
3. `POST /api/ingest/all/1339351318115946496`, then
   `GET /api/board?sport=nba&limit=40` read by eye. The 2025 first round
   (Jokić, Dončić, Wembanyama at 1–3) is the sanity check.
4. Fit both sports; a manager who plays in both has separate `picksScored` per
   sport, and his NFL reach bias equals its pre-basketball value.
5. Replay the 2025 NBA draft and confirm all 168 `draft_slot`s still match. Then
   assert the `reversalRound = 3` case against a hand-computed round 3–4
   ordering, since no completed draft exercises it.
6. Both dev servers up: `/` lists both leagues with correct sport pills; the NBA
   board renders with **no unstyled cells** (an unstyled cell means a missing
   position token).
7. Browser tab reads "Ball Knowers", favicon renders, OG tags resolve in a card
   validator.
