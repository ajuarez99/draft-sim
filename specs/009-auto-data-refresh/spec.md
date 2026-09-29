# Feature Specification: Automatic data refresh

**Feature Branch**: `009-auto-data-refresh`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "i think we need something to injest this automatically. im not a big
fan of these injest./number all ove rthe site". After a cost check: "lets do github action and
refresh on visit thats a good call lets spec".

## Amended after planning (2026-09-28)

Planning ([research.md](research.md)) measured things this spec assumed. The original text below is
left in place; where they conflict, these corrections take precedence.

1. **US5 and FR-012: "fetch only new games" isn't possible the way the spec pictured it** (research
   R6).
   - Sleeper's per-player endpoint returns a player's whole season on every call, so a per-player
     walk can't fetch fewer games.
   - Sleeper also has a **per-week** endpoint, measured to return the same entries (identical game
     ids, dates, teams and points) for every player in the sport. The season schedule supplies
     home/away and byes.
   - US5 is met by rebuilding per-game ingest on those: about 23 calls per sport-season shared by
     every league, versus 331 per league, and an active season costs its latest one or two weeks.
   - **Consequence**: `player_game` stores every player in the sport, not only rostered players.
     Readers already filter to rostered players (read). A parity gate requires identical rows for
     rostered players, and spec 008's verified Embiid figures must not move.
2. **"Not loaded yet — updating now"** (US2): a backend message can't know whether a refresh is
   running. Backend strings now say only what's missing. The rail, which does know, says
   "Updating…" (contract).
3. **A failed refresh is retried after 10 minutes, not on every visit** (contract). The spec's
   "the next visit after the threshold" would have been an hour. Ten minutes keeps a brief
   Sleeper outage from hiding fresh data for an hour, without retrying on every page view. Hand-set,
   arbitrary.

### Amended after analysis (2026-09-28)

`/speckit-analyze` found a gap that would have left the motivating case unfixed. Production was
then measured, read-only, against its own API and Sleeper:

4. **The "91 rows that hadn't been there" line below is wrong.** The transaction ingest printed
   `stored: 91` for NBA 2025 on two separate runs. It refetches the last scored week every time, so
   91 is that week, not a gap being filled. The same error is in spec 008's `verification.md`
   (T071), corrected there too.
5. **What production is actually missing (NBA 2025, measured 2026-09-28):**

   | Data | Weeks that match Sleeper | Weeks that don't |
   |---|---|---|
   | Waiver/FA adds | 1–9 | 10 (24 of 53 stored), 11–21 (none stored) |
   | Weekly team points | 1–7, 10–18, 20 | 8 (off by up to 20.5), 9 (off by up to 31) |

   Weeks 19 and 21 list 8 of 12 teams in production's report. That's most likely playoff-bracket
   teams without a matchup, not missing data. It hasn't been verified, and the production check
   (T054) settles it.
6. **Why a plain re-run wouldn't fix it.** Both the transaction walk and the weekly-points walk
   skip any week that has rows, except the last scored week
   (`TransactionIngestService.java:62–66`, `LeagueHistoryIngestService.java:232–233`). A week
   stored while it was still in progress stays partial forever. That's exactly weeks 8–10 above.
   - **New requirement FR-016**: a week of transactions or weekly points counts as done only if it
     was fetched after it closed, the rule `sport_week_stats` already applies to per-game weeks.
   - **New success check**: after the fix, every regular-season week's adds and team points in
     production match Sleeper's (T054).
7. **Production's cold start is about 5 minutes**, not "a 502 or two" (measured 2026-09-28: 502s
   for about 4.7 minutes before `/api/health` answered). The daily job's retry window grows to 15
   minutes (research R7). Every visitor after an idle spell already waits that long for the
   whole site. That's outside this spec, and recorded in HANDOFF.
8. **An FFC ADP failure doesn't fail the daily run** (analysis U2, default chosen at
   implementation; Allan can override).
   - FFC is best-effort by design (`FfcAdpService.java:51–55`), and it may have nothing to serve
     in the offseason.
   - The step is recorded as `FAILED_BEST_EFFORT` in the day's result and `daily_capture`, so the
     gap is visible, but it doesn't email Allan.
   - Player-list and board failures still fail the run.

## Problem

The app only knows what someone has told it to fetch from Sleeper. Nothing runs on a clock. Data
arrives only when a person or the setup flow calls an ingest endpoint. When data is missing, pages
say so in developer terms: "run POST /api/ingest/transactions/{id}".

A league manager can't act on that message, and missing data isn't always visible. A page can
show a confident, wrong answer instead.

### The case that started this (measured 2026-09-28)

Production's NBA 2025 Superlatives page names the wrong Jabari Smith Jr. Award and the wrong
Waiver Wire Warrior:

| | Production | Correct (local, full data) |
|---|---|---|
| Jabari Smith Jr. Award | Rui Hachimura, Keldon Johnson: 8 adds | Jake LaRavia, Brice Sensabaugh: 14 adds |
| Waiver Wire Warrior | 1,310.5, (PAO)nd town | 1,587.5, KATastrophe Krew |

The code is identical in both places, and NFL 2025 matches exactly. The difference is data:
production's NBA 2025 transactions were never fully loaded. Locally, loading them again stored 91
rows that hadn't been there. The page showed a partial count as the full season's.

### What was checked, and how

Read in code and run locally on 2026-09-28, unless marked otherwise.

- **No scheduler exists.** There's no scheduled job anywhere in the backend.
- **Adding a league loads part of the data.** The setup flow (`web/src/pages/DraftPicker.tsx:38–41`)
  loads players, the league and its drafts, ADP and the draft board. It doesn't load transactions,
  per-game stats or week-by-week history. Two pages (League analysis, League history) offer a
  manual load for history.
- **Where the developer-facing messages live:**
  - Messages that name an ingest endpoint: `LeagueController.java:560`, `LeagueAnalysisService.java:301, 446, 476`,
    `PowerRankingService.java:270` and `SeasonSuperlativesService.java:609, 699, 811, 863`.
  - Two pages have already had their raw endpoint text replaced by hand
    (`DraftView.tsx:601`, `LeagueHistory.tsx:454`). That's the same fix this spec generalizes.
- **Access to the API.**
  - A shared-token gate exists (`ApiTokenFilter`, set by `API_TOKEN`). It's off when unset.
  - Production answered token-less requests with 200 on 2026-09-28, so it's evidently off there.
  - When it's on, the web app sends the same token from its public bundle (`web/src/api.ts:174`),
    so it keeps out casual callers but isn't a secret.
- **Some data can be loaded later, and some can't.** Sleeper keeps scores, matchups, transactions
  and per-game stats for past weeks, so they can be fetched at any later time. Two things it
  doesn't keep:
  - **A player's suspension tag.** Sleeper shows only today's, and the Unethical Award (spec 008)
    counts a week only if the tag was captured that week.
  - **ADP.** The snapshot the draft tools use covers roughly the last four days
    (`project_adp_at_time_wiped_by_ingest` memory).
- **Hosting.** The backend runs on Railway with serverless (cold start) on. It sleeps after 5–10
  minutes without outbound traffic and wakes on the next request. The first request after sleeping
  may fail with a 502 (Railway docs).

## Budget

Measured on 2026-09-28 unless marked as an estimate. CPU was measured on the local dev machine
against the running backend. Railway's CPUs may be slower; even at three times slower, every
cadence below stays under $1 a month.

**Current spend** (Railway dashboard, supplied by Allan): $0.73 for Sep 10–28, so about $1.20 a
month. That's inside the $5 of usage the Hobby plan includes. The compute cap is $10.

**Railway prices** (docs, 2026-09-28):
- vCPU: $0.000463 per vCPU-minute.
- Memory: $0.000231 per GB-minute.
- Egress: $0.05 per GB. Ingress is free, so Sleeper's responses cost nothing.
- Volume: $0.15 per GB-month.

**Cost of one refresh, per step:**

| Step | Wall time | Backend CPU |
|---|---|---|
| Player list, NBA + NFL | 1.8 s | 1.8 s |
| League scores and history, per league | 1.3 s | 0.03 s |
| Transactions, per league | 0.3 s | 0.02 s |
| Per-game stats, NFL 2026 at week 2 | 5.7 s | 0.9 s |
| Per-game stats, NBA 2025, full season | **101 s** | **13.7 s** |
| ADP + board, NFL (measured T003) | 1.0 s | 0.33 s |
| ADP + board, NBA (measured T003; FFC skips NBA) | 0.2 s | 0.05 s |

The per-game ingest isn't incremental. The 101 s above rewrote a completed season's 19,428 rows
that were already stored.

**Storage**: the whole local database is 66 MB. Per-game rows cost about 1 KB each, which is
about 18 MB per NBA season and 5 MB per NFL season. At $0.15/GB-month that's under $0.02 a month.

**Sleeper**: free. The limit is 1,000 calls a minute, and the per-game ingest measured about 200 a
minute. Sleeper asks that the full player list be fetched **at most once a day**. The NFL list is
14.7 MB, although the docs say 5 MB.

**Cost by design (estimates):**

| Design | Extra per month | Keeps cold start |
|---|---|---|
| Scheduler inside the backend | about $4–6 (backend never sleeps; ~0.5 GB assumed, not measured on Railway) | No |
| Daily scheduled wake-up (this spec) | about $0.05 (≈10 extra awake minutes a day) | Yes |
| Refresh on visit (this spec) | about $0 (runs while the backend is already awake for a visitor) | Yes |

This spec's design adds an estimated **$0.05–0.10 a month**. Chosen by Allan on 2026-09-28.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Pages refresh themselves when opened (Priority: P1)

A manager opens any page for their league. If that league-season's data is older than the
staleness threshold, the app fetches what's new from Sleeper in the background. While that runs,
the page says it's updating. When it finishes, the page shows the fresh figures without a manual
reload.

**Why this priority**: This is the fix for the case that started it. Most data (scores, pairings,
transactions, per-game stats) can be loaded late, so loading it when someone looks covers most
gaps at no extra hosting cost.

**Independent Test**: Delete one week of a league's stored transactions, then open its
Superlatives page. It shows an updating state, and within the target time the Waiver Wire Warrior
and the Jabari Smith Jr. Award match the full-data figures.

**Acceptance Scenarios**:

1. **Given** a league-season last refreshed longer ago than the threshold, **When** a manager opens
   one of its pages, **Then** a refresh starts. The page shows what's stored, labelled as
   updating, and replaces it with the refreshed figures when the refresh completes.
2. **Given** a league-season refreshed within the threshold, **When** a page is opened, **Then**
   no refresh starts, and the page shows when the data was last updated.
3. **Given** a refresh for a league-season is already running, **When** another manager opens one
   of its pages, **Then** no second refresh starts. That page shows the same updating state.
4. **Given** a refresh fails (Sleeper down, timeout), **When** the page is showing, **Then** it
   keeps the stored figures and says when they're from. Nothing is presented as current when it
   isn't. The next visit after the threshold tries again.
5. **Given** a completed season whose data is fully loaded, **When** one of its pages is opened,
   **Then** it isn't refreshed again, because nothing about it can change.
6. **Given** the production NBA 2025 league, **When** its Superlatives page is opened after this
   ships, **Then** the Jabari Smith Jr. Award names LaRavia and Sensabaugh at 14, and the Waiver
   Wire Warrior shows 1,587.5 for KATastrophe Krew.

---

### User Story 2 - No page tells a manager to run an endpoint (Priority: P1)

Wherever data is missing or still loading, the page says so in plain words: "Not loaded yet —
updating now", or "Couldn't reach Sleeper; showing data from 3 hours ago". No user-facing text
names an API route, an HTTP method or an ingest command.

**Why this priority**: Allan named it. It's also what makes US1's states visible to a manager.

**Independent Test**: Search every string the backend returns and every page the frontend renders
for `POST /api/` or `/api/ingest`. Nothing user-facing matches. Then open each page for a league
with no per-game stats or transactions loaded, and read what it says.

**Acceptance Scenarios**:

1. **Given** a superlative that can't be computed until data arrives, **When** its card shows,
   **Then** it reads as "updating" or "not loaded yet", never as an instruction to run something.
2. **Given** a page whose data is older than the threshold and couldn't be refreshed, **When** it
   shows, **Then** it gives the time of the last successful update.
3. **Given** a genuine absence of data, not missing data (a league that uses no FAAB, a season with
   no suspensions), **When** it shows, **Then** it's worded as a fact about the league, not as
   something still loading.

---

### User Story 3 - A daily capture of what can't be recovered later (Priority: P2)

Once a day, an external scheduled job wakes the backend and refreshes the two things Sleeper
doesn't keep:
- the player list, which also records the day's suspension tags;
- the ADP snapshot.

The backend then goes back to sleep.

**Why this priority**: Without it, the Unethical Award misses any week nobody visited, and ADP
misses its window. Both gaps are permanent. It's P2 because both already have partial coverage
today, from manual and setup runs.

**Independent Test**: Trigger the job by hand while the backend is asleep. Check that it retries
through the cold-start failure, and that the day's player refresh and suspension capture are
recorded. Then check that the backend goes back to sleep afterwards.

**Acceptance Scenarios**:

1. **Given** the backend is asleep, **When** the daily job runs, **Then** it retries until the
   backend answers (within a bounded number of attempts) and the refresh completes.
2. **Given** the job has already refreshed the player list today, **When** it runs again the same
   day (a manual re-run), **Then** the player list isn't fetched a second time (Sleeper's
   once-a-day request).
3. **Given** the backend still hasn't answered after the last retry, **When** the job gives up,
   **Then** the failure is visible to Allan (a failed run he's notified of), not silent.
4. **Given** someone who isn't the scheduler calls the daily-refresh action, **When** they don't
   hold its secret, **Then** they're refused.

---

### User Story 4 - A new league is loaded completely, once (Priority: P2)

When a league is added, every season the app shows for it gets loaded in full: scores and
pairings, transactions, and per-game stats. After that, completed seasons are never fetched again.

**Why this priority**: It closes the gap that produced the wrong production figures at the source,
instead of relying on a visit to notice it. It's P2 because US1 covers the same gap reactively.

**Independent Test**: Add a league with one completed past season on a fresh database. Once setup
finishes, the past season's Superlatives match a hand check, with no further visit or manual
ingest. Opening them again triggers no fetch for that season.

**Acceptance Scenarios**:

1. **Given** a league is added, **When** setup completes, **Then** its completed seasons are fully
   loaded, including transactions and per-game stats, and marked complete.
2. **Given** setup is still loading past seasons, **When** a manager opens one of those seasons,
   **Then** the page says it's still loading.
3. **Given** a season marked complete, **When** any later refresh runs, **Then** it skips that
   season.

---

### User Story 5 - Refreshing per-game stats costs only what's new (Priority: P2)

Refreshing a league's per-game stats fetches only games since the last refresh. A completed
season is never re-walked.

**Why this priority**: The measured cost of the current full re-walk (101 s for one NBA season)
would make refresh on visit slow for the visitor. Its share of the budget also grows with every
league added.

**Independent Test**: Refresh an up-to-date NBA league-season twice in a row. The second run
fetches nothing new and finishes in a small fraction of the measured 101 s. The stored per-game
rows are unchanged.

**Acceptance Scenarios**:

1. **Given** per-game stats are current through a date, **When** a refresh runs, **Then** it
   fetches only games after that date and stores the same rows a full walk would have.
2. **Given** a completed season, **When** a refresh runs, **Then** it doesn't walk that season.
3. **Given** a player added to a roster mid-season, **When** a refresh runs, **Then** his games
   for that season are fetched even if they're older than the last refresh date.

---

### Edge Cases

- **Offseason**: no active season. Visits don't refresh anything, and the daily job still
  refreshes the player list, since players change teams in the offseason.
- **Two leagues in one sport share players**: the player list and per-game stats are per sport,
  not per league. One league's refresh shouldn't re-fetch what another just fetched.
- **A visit during the daily job**: the two may overlap. Both write the same stored facts
  idempotently, so an overlap costs time, not correctness. The planning stage must confirm this is
  true of every ingest, not assume it.
- **Sleeper returns partial data** for the current week (games in progress): refresh takes what's
  there. The existing "fully scored week" rules already keep an in-progress week out of
  season-to-date figures.
- **The backend restarts mid-refresh** (deploy, or it goes to sleep): the next visit or the next
  daily run picks it up. A half-finished refresh never marks a season complete.
- **Sleeper rate limit**: refreshes together stay under 1,000 calls a minute, including when
  several leagues are visited at once.
- **Local development**: nothing schedules itself locally, and manual ingest endpoints keep
  working for development and verification.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Opening any league-season page MUST start a background refresh of that
  league-season when its data is older than the staleness threshold. It MUST NOT make the page
  wait for the refresh to finish before showing stored data.
- **FR-002**: The staleness threshold is hand-set and MUST be labelled arbitrary where it's
  configured. The starting value is **1 hour for an active season**. Completed, fully loaded
  seasons never go stale.
- **FR-003**: At most one refresh per league-season MUST run at a time. Concurrent visits share it.
- **FR-004**: While a refresh is running, a page MUST show that it's updating. When it completes,
  the page MUST show the refreshed data without the manager reloading.
- **FR-005**: Every league-season page MUST be able to show when its data was last successfully
  updated. It MUST show it whenever a refresh failed or is running.
- **FR-006**: A failed refresh MUST leave stored data untouched and visible, labelled with its
  age. It MUST NOT be presented as current.
- **FR-007**: No text shown to a league manager MUST name an API route, an HTTP method or an ingest
  command. The planning stage MUST produce the full list of such strings, which the verification
  stage checks against.
- **FR-008**: A daily scheduled job, run outside the app's host, MUST refresh the player list
  (with its suspension capture) and the ADP snapshot. It MUST retry through a cold-start failure
  a bounded number of times, and a final failure MUST notify Allan.
- **FR-009**: The player list MUST NOT be fetched from Sleeper more than once per sport per day by
  the scheduled job and visits combined. Manual developer runs are exempt.
- **FR-010**: The action the daily job calls MUST require a secret that's held only by the
  scheduler and the backend's configuration. It MUST NOT be the shared token the web app ships in
  its bundle.
- **FR-011**: Adding a league MUST load all of its shown seasons completely (scores and pairings,
  transactions, per-game stats). A season whose regular season and playoffs are over, and whose
  data is fully loaded, MUST be marked complete and never fetched again.
- **FR-012**: The per-game stats refresh MUST fetch only games not already stored for an active
  season, apart from a newly rostered player's earlier games. It MUST NOT walk a completed season.
- **FR-013**: The design MUST keep the backend able to sleep when unused. Nothing in the app may
  wake it on a timer (Allan's decision, 2026-09-28, to keep serverless mode).
- **FR-014**: All refresh activity combined MUST stay under Sleeper's 1,000 calls per minute.
- **FR-015**: Manual ingest endpoints MUST keep working, for local development and for
  verification.
- **FR-016** *(added after analysis)*: A week of transactions or weekly points MUST be treated as
  done only if it was fetched after that week closed. A week stored while in progress MUST be
  fetched again until it has been fetched after closing. The same rule applies to per-game weeks
  (`sport_week_stats`), in one implementation.

### Key Entities

- **Refresh state (per league-season)**:
  - when it was last successfully refreshed;
  - whether a refresh is running;
  - whether the season is complete (never refresh again);
  - the last failure, if any, and when.
- **Daily capture record (per sport, per day)**: that the player list and ADP were refreshed that
  day. It's what enforces FR-009 and shows a missed day.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After this ships, production NBA 2025's Superlatives page shows LaRavia and
  Sensabaugh at 14 adds, and KATastrophe Krew at 1,587.5, without anyone running an ingest by
  hand.
- **SC-002**: Zero user-facing strings name an API route or ingest command, checked against
  FR-007's list and by searching the rendered pages.
- **SC-003**: A stale active league page shows fresh data within **30 seconds** of being opened,
  measured on production for a league with typical data.
- **SC-004**: Refreshing an already current NBA league-season's per-game stats takes under **10%**
  of the measured 101 s.
- **SC-005**: Over the first full month, Railway compute stays within the $5 included usage, and
  the month's increase over the current ~$1.20 pace is under **$1**. Read from the Railway usage
  page.
- **SC-006**: Over the first 30 days, the daily job succeeds on at least **29**, and every failure
  produced a notification.
- **SC-007**: With no visitors, the backend still sleeps between daily runs. Checked on the
  Railway service's activity.
- **SC-008** *(added after analysis)*: After deploy and one visit, every regular-season week of
  production NBA 2025 and NFL 2025 matches Sleeper, in both waiver/FA add count and each team's
  points.

## Assumptions

- **Scheduler**: GitHub Actions' scheduled workflows, chosen by Allan on 2026-09-28. They run on
  GitHub's clock at no cost for this repo's size. Scheduled runs can start several minutes late,
  which doesn't matter for a daily capture.
- **Staleness threshold**: 1 hour for an active season is a starting guess, labelled arbitrary. It
  wasn't measured. Refresh on visit costs little enough that a shorter one would be affordable too.
- **Daily job time**: early morning US time, after the previous day's NBA games and Sleeper's
  overnight stat corrections. The planning stage picks the exact hour.
- **Which seasons count as active**: a season whose league isn't yet complete in Sleeper. Spec 006
  already stores league status (V21). Whether that's enough to mark data "fully loaded" is for
  planning to measure, not assume.
- **Per-sport sharing**: the player list and per-game stats are shared across leagues of a sport,
  as they're stored today. A refresh is triggered per league but de-duplicated per sport.
- **Out of scope**:
  - Locking down the rest of the API. The shared token in the bundle is a known, separate
    weakness, and only the new daily action gets a real secret (FR-010).
  - Push updates from Sleeper; Sleeper has no webhooks.
  - Changing the draft-day live poller, which already refreshes during a live draft.
- **Estimates in the Budget section** are labelled. The memory figure behind the in-backend
  scheduler's $4–6 wasn't measured on Railway. It's recorded to explain why that design was
  rejected, not as a finding.
