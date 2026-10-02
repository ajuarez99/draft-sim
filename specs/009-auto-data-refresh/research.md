# Research: Automatic data refresh

Phase 0 for `specs/009-auto-data-refresh/plan.md`. Each entry says how it was established:
- **measured**: run on 2026-09-28, against live Sleeper or the local app;
- **read**: code, not executed;
- **decided**: a judgement call, with its alternatives.

The local backend and Postgres (5433) were running for the measurements in the spec's Budget
section. They weren't running for the Sleeper probes below, which went straight to Sleeper.

---

## R1 — Where "a visit" is detected

**Decision**: the site-wide rail starts the refresh. It already knows which league a page belongs
to, and it runs on every league page.

- **Read**: `web/src/railLeague.ts` resolves the league from the URL path alone, because the rail
  lives outside `<Routes>` (AppShell). Every `/leagues/:sleeperLeagueId/*` page passes through it:
  nine routes in `App.tsx:102–128`.
- When the rail's league changes, it calls the refresh endpoint once (contract), then polls the
  status while a refresh runs.
- **How pages learn the refresh finished**: a small React context carries a per-league
  `dataVersion` number. The rail bumps it when a refresh completes. Each league page adds it to its
  data-fetch effect's dependencies, a one-line change per page (eight pages; the ninth route, `/power/verify`, is a verification tool, not a league data page). Pages don't
  re-implement the trigger.
- **Alternatives rejected**:
  - Each page calls the refresh itself: the same idea copied eight times, and the rail's comment
    records exactly that bug from an earlier session.
  - The backend refreshes inside every `GET`: it couples reads to Sleeper latency and fires on
    background fetches too, such as the rail's own `getDrafts`.

## R2 — Which seasons a visit refreshes

**Decision**: the URL's league-season, plus every earlier season in its chain that isn't marked
fully loaded.

- **Amended after analysis (2026-09-28)**: the unit of refresh is **the chain**, not each season.
  `ingestChain` already walks every season in the chain, so calling it once per season re-walked
  the whole chain each time, completed seasons included, against FR-011.
  - One refresh per chain, keyed by the chain's newest league id.
  - `ingestChain` gains an explicit `skip` set: the chain's `loaded_complete` seasons, passed at
    every call site and never defaulted. Existing callers pass an empty set.

- **Read**: several pages resolve a new, unplayed season back to the last played one
  (`LeagueSeasonResolver`, spec 008 amendment 15). Refreshing only the URL's season would miss the
  season the page actually shows.
- US4 (load a new league completely, once) falls out of the same rule. The first visit after a
  league is added finds its past seasons not loaded, and loads them. The setup flow also starts one
  refresh when it finishes, so the first visit usually finds nothing to do.
- **Read**: `LeagueHistoryIngestService.ingestChain` already walks every season in the chain. It
  also ingests each season's transactions (`LeagueHistoryIngestService.java:100`), wired in by spec
  004 after "five of six leagues held zero transactions". So one call covers scores, pairings,
  rosters and transactions for the whole chain.

## R3 — What "stale" and "complete" mean

- **Stale** (decided; the spec's FR-002 value): an active league-season whose last successful
  refresh is more than **1 hour** old. Hand-set, arbitrary, labelled where it's configured.
- **Active** (read): `league.status` (V21) is not `complete`. Null counts as active, never
  complete, following V21's own column comment: "null means not yet known and must never be
  treated as complete".
- **Fully loaded** (decided): `status = complete` **and** a refresh finished successfully after
  that status was first seen. Only then does the season get `loaded_complete = true`, after which
  nothing fetches it again (FR-011).
  - A refresh that fails, or that saw the league still `in_season`, never sets it.
  - This is also what fixes production's NBA 2025 gap. That season is `complete` but was never
    loaded after completing, so its first refresh under this feature loads it and only then
    marks it.
- **Amended after analysis (2026-09-28)**: the two bullets below are wrong. See R14.
  Production's gap is partial weeks that the skip rule never refetches, so re-running does *not*
  fill it.
- **Not diagnosed** (read): why production's NBA 2025 transactions are partial.
  - The transaction walk is already incremental. It skips stored weeks and refetches the last
    scored one (`TransactionIngestService.java:62–66`), so re-running it fills any gap.
  - The likeliest cause is simply that nothing re-ran it after the season ended. Production's
    database can't be queried from here, so this is inference, not a finding.

## R4 — One refresh at a time

**Decision**: an in-process single-flight map keyed by league-season id. A second visitor joins
the running refresh instead of starting another (FR-003).

- **Read**: `railway.toml` sets no replica count, so production runs one backend instance. An
  in-memory lock is enough for one instance.
  - **Risk if that changes**: two instances could double-refresh. That's safe, because every
    ingest upserts (R11), but wasteful. It's noted in the plan's Complexity Tracking rather than
    solved now.
- **Per sport, not per league, for shared data**: the player list and per-game stats are per
  sport (R6). They get their own single-flight key (`sport:season`), so two leagues in one sport
  refreshing together fetch Sleeper once.
- **Concurrency cap**: at most 2 league refreshes run at once (decided, arbitrary). A burst of
  visits queues instead of multiplying Sleeper calls (FR-014).

## R5 — Refresh state survives restarts

**Decision**: a `league_refresh` table records last success, last failure and `loaded_complete`
per league-season (data-model.md).

- "Running" is **not** stored. It's in-memory only (R4), so a restart mid-refresh can't leave a
  season stuck as "running" forever. The spec's edge case: the next visit simply starts again.
- **Why a table rather than memory**: Railway serverless restarts the process on every wake. With
  in-memory state only, every wake would look stale and refresh everything.

## R6 — Per-game stats: one call per week instead of one per player (measured)

This is the finding that most changes the plan. The spec's US5 assumed "incremental" meant
fetching fewer games per player. Sleeper's shape doesn't allow that. There's a better route.

**Today** (read): `PlayerGameIngestService` makes one call per rostered player:
`/stats/{sport}/player/{id}?season=…&grouping=week`. Each call returns that player's *whole
season*.
- So a per-player walk can't be made incremental in Sleeper calls. An active season costs every
  rostered player's full season on every run. The measured result was 331 calls and 101 s for
  NBA 2025.
- It also needs every walked player's full season in memory at once. Pass 2 builds the
  team-played-that-week evidence that tells a bye from an absence
  (`PlayerGameIngestService.java:126–148`).

**Sleeper's per-week endpoint** (measured, 2026-09-28): `/stats/{sport}/{season}/{week}?season_type=regular`.

| | NBA 2025 week 10 | NFL 2025 week 5 |
|---|---|---|
| Entries | 1,861 (562 players, up to 4 games each) | 2,102 |
| Size | 1.67 MB | 1.91 MB |
| Entries with empty stats (a DNP) | 648 | 739 with stats but no `gp` |

- **The same entries as the per-player endpoint**:
  - NBA: Jake LaRavia's three week-10 games have identical `game_id`, `date`, `team` and `pts` from
    both endpoints.
  - NFL: McCaffrey's week-5 entry has the same `game_id` (`202510532`), team and `gp`.
  - The field sets are identical except one, `is_away_team`, which only the per-player endpoint
    has.
- **`is_away_team` is used** (read): the Weekly Report shows home/away from `player_game.is_away`
  (`WeeklyReportService.java:278`).

**Sleeper's schedule endpoint** (measured): `/schedule/{sport}/regular/{season}`.

| | NBA 2025 | NFL 2025 |
|---|---|---|
| Games | 1,235 | 272 |
| Size | 1.05 MB | 27 KB |
| Home/away | `home.team` / `away.team` | `home` / `away` strings |

- Every game carries its `game_id`, `week`, `status` (`complete`), home and away.
- LaRavia's game `1261814783951241216` appears with LAL as away, so `is_away` can be derived from
  it exactly.
- It also lists outright which teams played in a week. Research R9 in spec 008 had to *infer* a
  bye from other players' entries. The schedule states it.

**Decision**: rebuild per-game ingest on per-week stats plus the season schedule.

| | Per-player (today) | Per-week + schedule |
|---|---|---|
| Sleeper calls, full NBA season | 331 **per league** | ~22 weeks + 1 schedule, **shared by every league in the sport** |
| Calls to refresh an active season | same 331 | the 1–2 most recent non-final weeks |
| Bye detection | inferred from other players' entries | read from the schedule |
| Rows stored | rostered players only | every player in the sport (see below) |

- **Rows**: every player in the sport. The per-week payload contains them all, and storing them all
  also covers US5 scenario 3 for free: a player rostered mid-season already has his earlier games
  stored.
- **Storage estimate**: NBA 2025 week 10 has 1,861 entries. About 1,200 of those are played games,
  so roughly 26k `player_game` rows a season, versus 19,428 today. At ~1 KB a row (measured,
  spec Budget) that's about 26 MB per NBA season, and similar for NFL. This is an estimate, to be
  measured in the quickstart.
- **Readers already filter to rostered players**, so storing more rows doesn't change any page:
  - Weekly Report: `WeeklyReportService.java:266–271`, "Restricting to the rostered set is what
    keeps this a league page."
  - Superlatives reads by explicit player ids: `SeasonSuperlativesService.java:803`.
  - **One reader to check**: `playersWithGames` (`PlayerGameRepository.java:152`) feeds the Embiid
    award's coverage note (`SeasonSuperlativesService.java:797, 940`). Under per-week storage it
    means "had a game", not "was walked". That's the same thing for any player who played, and
    the quickstart's parity check covers it.
- **Absences**:
  - Empty-stats entries (a DNP with an entry) come in the per-week payload itself.
  - "No entry at all while his team played" (football, spec 008 R9's `TEAM_PLAYED_NO_ENTRY`) can
    only be judged for players we know about. It's computed for **players rostered in any league
    of that sport-season**, after the week is stored:
    - his team that week comes from his nearest stored entry, as today;
    - whether that team played comes from the schedule.
- **Week finality** (decided, arbitrary): a week is `final` once it was fetched at least **48
  hours after its last scheduled game** and every game in it is `complete`. Final weeks are never
  refetched. The window covers Sleeper's stat corrections, and the 48 h figure isn't measured.
- **Acceptance gate (parity)**: rebuilding NBA 2025 and NFL 2025 with the new ingest must
  reproduce, for every rostered player, the same `player_game` rows and the same `player_absence`
  rows as the current walk. Spec 008's verified Embiid figures must not move: Jokić 16 missed games,
  Embiid 26.
  - If parity fails on a real difference, the difference gets reported and decided. It isn't
    tuned away (AGENTS.md).
- **Alternatives rejected**:
  - Keep the per-player walk and run it in parallel (bounded virtual threads). That cuts wall time
    but still costs 331 calls per league per refresh, and SC-004 (under 10% of 101 s for an
    up-to-date season) isn't reachable.
  - Keep the per-player walk and run it only daily: refresh on visit then can't show new games for
    up to a day, and the cost still multiplies by league count.

## R7 — The daily job

**Decision**: a GitHub Actions workflow, `.github/workflows/daily-refresh.yml` (the repo has no
workflows yet, read).
- **Schedule**: `cron: "0 11 * * *"`, 11:00 UTC (6 a.m. US Central). That's after the last NBA
  game ends (~1 a.m. ET) and Sleeper's overnight processing. Decided, arbitrary.
- **What it does**: one `curl` to `POST https://api.ballknowers.co/api/refresh/daily` with the
  secret header (R8).
- **What the endpoint does, per sport**:
  1. **Player list**, at most once per sport per UTC day (FR-009). It also captures suspensions.
     `PlayerIngestService.ingest` already records the suspension capture for Sleeper's current week
     (spec 008 T052).
  2. **ADP** (`FfcAdpService.ingest`). It's best-effort by design (`FfcAdpService.java:51–55`), so a
     failed FFC fetch doesn't fail the job.
  3. **Board rebuild** (`BoardService.rebuild`), because the board blends both inputs.
- **Cost**: the player list for both sports was measured at 1.8 CPU-s. **ADP and the board
  rebuild were not measured** (the backend wasn't running during research), and the quickstart
  measures them.
- **Not included**: league refreshes. Everything they fetch can be loaded later (spec Problem),
  and refresh on visit handles it. Keeping the daily call small keeps the wake short.

**Cold start** (read, Railway docs):
- A sleeping service wakes on an inbound request, but "the first request … may return a 502".
- **Retry policy**: `curl --retry 8 --retry-all-errors --retry-delay 20 --max-time 300`, so up to
  ~3 minutes of retries. Spring Boot's start time on Railway isn't measured; the quickstart
  measures the first-success delay.
- **Amended after analysis (2026-09-28), measured**: production answered 502 for about **4.7
  minutes** after being idle, before `/api/health` returned 200. Three minutes of retries would
  have failed the job.
- **New policy**: `--retry 30 --retry-all-errors --retry-delay 30 --retry-max-time 900
  --max-time 180`. That's up to 15 minutes in total, with each attempt capped at 3 minutes, and
  the job has `timeout-minutes: 20`.

**Failure notice** (read, GitHub docs): GitHub emails the author of the workflow's last commit when
a scheduled run fails. That's Allan for this repo.

**The repo is public** (measured: `api.github.com/repos/ajuarez99/draft-sim` answers an
unauthenticated request with 200).
- Actions minutes on standard runners are free for public repos, so the daily job costs nothing
  on GitHub's side.
- The secret lives in repository Secrets, never in the workflow file, which anyone can read.

**GitHub gotcha** (read, GitHub docs): scheduled workflows in a public repo are disabled after 60
days with no repository activity.
- This repo is active, but a quiet offseason could trip it, and GitHub emails a warning first.
- Recorded in HANDOFF and the quickstart rather than engineered around. A keep-alive commit bot
  would be more machinery than the risk warrants.

## R8 — The daily endpoint's secret

**Decision**: `REFRESH_SECRET` is set in Railway's backend variables and as a GitHub Actions
repository secret. `POST /api/refresh/daily` requires it in `X-Refresh-Secret`, compared in
constant time. It returns 401 without it, and **404 when the backend has no secret configured**,
so an unconfigured deployment doesn't expose an open trigger.

- **Read**: `ApiTokenFilter` gates `/api/**` on a bearer token when `API_TOKEN` is set. The web app
  ships that same token in its bundle (`web/src/api.ts:174`), so it isn't a secret (spec
  "What was checked").
  - Production answered token-less calls with 200 on 2026-09-28 (measured), so `API_TOKEN` is
    evidently unset there.
- **`/api/refresh/daily` is exempt from `ApiTokenFilter`**, alongside `/api/health`. It has its
  own, stronger secret, and asking the scheduler to hold the public token as well adds nothing.
- **Visit refreshes don't need the secret**:
  - They're triggered by the web app, go through the same gate as every other call, and can only
    refresh a league the caller can see (the membership check every league endpoint already
    applies).
  - Their abuse ceiling is the 1-hour staleness plus single-flight. A flood of calls refreshes each
    league at most once an hour.
- **Out of scope**, as the spec says: making the rest of the API require real authentication.

## R9 — Manual ingest endpoints

**Decision**: kept, unchanged in behaviour (FR-015). Local development and this feature's own
verification use them.

- The **per-game** manual endpoint (`POST /api/ingest/player-games/{id}`) switches to the new
  per-week ingest (R6), since there must be one implementation of the rule (memory:
  multi-sport landmines).
- The old per-player walk is removed, not kept as a second path. The parity gate in R6 is what
  justifies that.

## R10 — Removing developer-facing text (FR-007)

**Read**, 2026-09-28. The messages that name an ingest endpoint:

| File | Lines | Shown on |
|---|---|---|
| `api/LeagueController.java` | 560 | draft pages (a player missing from the list) |
| `engine/LeagueAnalysisService.java` | 301, 446, 476 | League analysis |
| `engine/PowerRankingService.java` | 270 | Power rankings |
| `engine/SeasonSuperlativesService.java` | 609, 699, 811, 863 | Superlatives |

Two pages were already fixed by hand: `DraftView.tsx:601` and `LeagueHistory.tsx:454`.

**Decision**:
- Replace each with plain wording that matches the refresh state: "Not loaded yet — updating" while
  a refresh can still fix it, and "Couldn't load from Sleeper" with a time once one has failed.
- **Guard test**: add `NoIngestHintsInMessagesTest`, modelled on `NoSportNameInSuperlativesTest`.
  It scans `engine/` and `api/` Java string literals, not comments, for `/api/ingest` and
  `POST /api/`, so a new hint fails the build rather than shipping.
- `LeagueAnalysisService.java:446` names **projections**, which this feature doesn't refresh.
  It becomes "Projections aren't available for these weeks" (a fact about the data, spec US2
  scenario 3), not "updating".

## R11 — Overlapping refreshes are safe (read; to be proven)

Every write these refreshes perform is an upsert on a natural key:
- transactions: `(league_id, sleeper_transaction_id)`, V19;
- `player_game`: its key;
- `player_absence`: its key;
- roster weeks, per `LeagueHistoryIngestService`.

So a visit refresh and the daily job overlapping should cost time, not correctness.

**Not proven by reading**: the absence clean-up deletes (`deleteByGame`, `deleteWeeklyBasis`) run
inside the same walk as the inserts. Two walks interleaving could, in principle, delete a row the
other just wrote. The rebuilt ingest (R6) runs per-sport single-flight (R4), so two walks of the
same sport-season can't interleave. The quickstart checks that it holds under two concurrent
triggers.

## R12 — Local development

- Refresh on visit runs locally too, since it's in-app, not a clock. It can be turned off with
  `refresh.on-visit.enabled=false` (default `true`). Tests set it off, so a test's page load never
  reaches Sleeper.
- Nothing schedules itself locally. The daily endpoint returns 404 with no `REFRESH_SECRET` set,
  which is the local default.

## R13 — Budget check against the design (measured inputs, estimated result)

| Activity | Frequency | CPU per run | Monthly |
|---|---|---|---|
| Daily job: player list, both sports | 30 | 1.8 s (measured) | 54 s |
| Daily job: ADP + board | 30 | not measured | measured in quickstart |
| Backend awake for the daily wake-up | 30 | ~10 min × ~0.5 GB (estimate) | ≈ $0.035 |
| Visit refresh, history + transactions | ≤ 1/h per active league, only when visited | 0.05 s (measured) | negligible |
| Visit refresh, per-game weeks | ≤ 1/h per sport, only when visited | ~2 week payloads (not yet measured under the new ingest) | expected < full-season 13.7 s |

The estimated total is in line with the spec's $0.05–0.10 a month. SC-005 checks it on the real
Railway bill after a month.

## R14 — Weeks stored mid-week stay partial (added after analysis, measured 2026-09-28)

**Measured**, read-only, comparing production's own API with Sleeper for NBA 2025
(`1229352720222134272`), week by week:
- **Waiver/FA adds** (production's `/transactions` adds list):
  - weeks 1–9 match (±1, where production's list also counts failed claims);
  - week 10 has 24 of 53;
  - weeks 11–21 have **0**.
- **Team points** (production's `/weekly-report/{w}` against Sleeper's `/matchups/{w}`):
  - weeks 1–7, 10–18 and 20 match;
  - week 8 is off by up to 20.5 points, and week 9 by up to 31;
  - weeks 19 and 21 list 8 of 12 teams, most likely playoff-bracket teams without a matchup. Not
    verified.

**Read**: both walks skip a week that has rows unless it's the last scored week.
- Transactions: `stored.contains(week) && week != lastScoredLeg` (`TransactionIngestService.java:62–66`).
- Weekly points: `settled && week != lastScoredLeg` (`LeagueHistoryIngestService.java:232–233`).

NBA scores during the week, so `last_scored_leg` is the *current* week while it's being played. A
walk that runs mid-week stores that week's partial transactions and points. Once the league moves
on, the week is no longer the last scored one, so it's skipped forever.

**Decision (FR-016)**: one week-finality rule for all three walks: transactions, weekly points and
per-game weeks.

**Amended 2026-10-02: the line above about `last_scored_leg` holds for NBA only (and it was reasoned,
not measured mid-week).** Measured on (Foot) Ball Knowers 2026 that day: `leg` 4,
`last_scored_leg` **3**, while week 4 already had scores (5 of 12 rosters after Thursday night).
In NFL, `last_scored_leg` is the last *completed* week. So `last_scored_leg > week` held NFL week 3
non-final until week 4 finished: a week late, while Sleeper's own W-L already counted it. Complete
seasons read `leg == last_scored_leg` (NFL 2025 17/17, NBA 2025 21/21).
Fix (`WeekFinality`):
- **final (counted):** `last_scored_leg > week`, or `leg > week && last_scored_leg >= week`;
- **refetch stops:** only once `week < last_scored_leg`, so NFL stat corrections during the next
  week still land. For NBA, if R14's reading is right, both are the old rule unchanged.
- **Verified live (local, real Sleeper):** week 3 was stored non-final; after one refresh it is
  final, and expected wins reports `weeksScored: 3`.
- **Still unmeasured:** NBA's `last_scored_leg` mid-week. Check it on the first NBA week after
  2026-10-21.
- A week is **final** once it was fetched while `last_scored_leg > week`, or while the league's
  status is `complete`. *(Amended after code review, 2026-09-28: the `complete` clause is dropped. It
  froze the championship week at the first fetch after completion, before stat corrections. A season
  stops refreshing only at `loaded_complete`, which waits for every per-game week to be final, so the
  last week keeps being refetched through the correction window.)*
- A non-final week is fetched on every refresh.
- Stored in a new `league_week_fetch` table (data-model). Per-game weeks keep their sport-level
  `sport_week_stats.final`, with the same predicate type.
- **Existing rows have no fetch record**, so every stored week is refetched once after deploy.
  That's what heals production's weeks 8–10. It costs one fetch per week per league-season, once.

**Alternatives rejected**:
- Always refetch the last *two* weeks: that fixes a gap one week deep, but not a walk that
  stopped for a month (weeks 11–21 above).
- Drop the skip entirely: every refresh would re-walk completed seasons, against FR-011.

## R15 — The daily board step reuses the manual one (added after analysis)

**Read**: `POST /api/ingest/board` (`IngestController.java:147–152`) runs FFC ADP, then
`boards.rebuild`, then `profiles.persistFitted`. The daily job's first draft called only
`rebuild`, so fitted manager profiles would drift.

**Decision**: pull the sequence into one method, `BoardRefresh.run(sport)`, called by both the
endpoint and the daily job (one implementation per rule). The daily job's ADP and BOARD steps
become a single `BOARD` step with ADP inside it. It reads `FfcAdpService.Result`'s failure flag,
not an exception (spec amendment 8).
