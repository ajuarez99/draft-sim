# Data model: Automatic data refresh

One migration, **`V24__data_refresh.sql`**. V23 is the highest on `main` as of 2026-09-28; re-check
before writing, since migrations are append-only and sessions run concurrently. It adds three
tables. No existing table is altered, except that `player_game` gains more rows (R6).

---

## `league_refresh` — refresh state per league-season (R3, R5)

| Column | Type | Notes |
|---|---|---|
| `league_id` | bigint pk → `league(id)` on delete cascade | one row per league-season |
| `last_success_at` | timestamptz null | end of the last refresh that finished without error |
| `last_failure_at` | timestamptz null | |
| `last_failure` | text null | short reason, for logs and the page's "couldn't load" state; never shown raw |
| `loaded_complete` | boolean not null default false | true only when a successful refresh finished while `league.status = 'complete'` (R3). Once true, the season is never fetched again |

- **Stale** means `loaded_complete = false` and (`last_success_at` is null or older than the
  threshold). The threshold is 1 hour, hand-set and arbitrary (FR-002).
- **"Running" isn't stored**. It's in-memory single-flight (R4), so a restart can't leave a row
  stuck.
- **Written by** the refresh service, at the end of each attempt only.

## `sport_week_stats` — which per-game weeks are fetched, and final (R6)

| Column | Type | Notes |
|---|---|---|
| `sport` | text not null | |
| `season` | int not null | |
| `week` | int not null | |
| `fetched_at` | timestamptz not null | last successful fetch of this week's payload |
| `final` | boolean not null default false | true once fetched ≥ 48 h after the week's last scheduled game, with every game in it `complete` (arbitrary, R6) |

- **Key**: `(sport, season, week)`.
- **Shared across leagues**, like `player_game`: it describes a sport's week, not one league.
- A refresh fetches every week of the season, up to Sleeper's current week, that isn't `final`.

## `league_week_fetch` — when each league week was fetched, and whether it's final (FR-016, R14)

*Added after analysis.*

| Column | Type | Notes |
|---|---|---|
| `league_id` | bigint not null → `league(id)` on delete cascade | |
| `kind` | text not null | `TRANSACTIONS` / `POINTS` |
| `week` | int not null | |
| `fetched_at` | timestamptz not null | |
| `final` | boolean not null default false | true once fetched while `last_scored_leg > week`. Never reverts. *(Amended after code review, 2026-09-28: the `league.status = 'complete'` clause was dropped; it froze the last scored week before stat corrections, see research R14.)* |

- **Key**: `(league_id, kind, week)`.
- **Replaces** the "has rows" skip in both walks. A week with no row here is fetched, which is what
  heals weeks stored before this feature existed.

## `daily_capture` — the daily job's once-a-day record (FR-009)

| Column | Type | Notes |
|---|---|---|
| `sport` | text not null | |
| `capture_date` | date not null | UTC date |
| `kind` | text not null | `PLAYERS` / `BOARD` (ADP runs inside BOARD, R15) |
| `completed_at` | timestamptz not null | |
| `detail` | text null | the ingest's own result summary, e.g. `playersWritten=2091 suspendedCount=0` |

- **Key**: `(sport, capture_date, kind)`.
- Before fetching the player list, the daily endpoint checks for today's `PLAYERS` row and skips
  if it exists. That enforces Sleeper's once-a-day request even when the job is re-run by hand.
- It also makes a missed day visible (SC-006): a gap in `capture_date` is a day nothing ran.

## Changed: `player_game` and `player_absence` rows (R6)

No column changes.
- **Row source**: the per-week stats endpoint instead of the per-player walk.
- **Which players**: `player_game` rows for **every player in the sport** with a played game, not
  just rostered players.
- **`is_away`**: from the season schedule (`home`/`away` by `game_id`) instead of the entry's
  `is_away_team`.
- **Absences**:
  - `ENTRY_WITHOUT_PLAY`: written for every empty-stats entry in the payload.
  - `TEAM_PLAYED_NO_ENTRY` / `UNCLASSIFIED`: computed only for players rostered in some league of
    that sport-season, with byes read from the schedule.
- **Parity gate**: for rostered players, the rows must equal what the per-player walk wrote
  (research R6's acceptance gate).

## Computed, not stored: the refresh status (contract)

| Field | Notes |
|---|---|
| `state` | `FRESH` / `RUNNING` / `FAILED` / `COMPLETE` for the league-season the page shows |
| `lastSuccessAt` | from `league_refresh` |
| `lastFailureAt` | from `league_refresh` |
| `seasons[]` | per chain season still loading: `{ leagueSleeperId, season, state }` (US4 scenario 2) |

## State transitions: one league-season

```
(no row) ──visit──▶ RUNNING ──ok, status≠complete──▶ FRESH ──1 h passes──▶ stale ──visit──▶ RUNNING
                        │                                                               
                        ├──ok, status=complete──▶ COMPLETE (terminal: never fetched again)
                        └──error──▶ FAILED (stored data untouched) ──visit ≥ 10 min after the failure──▶ RUNNING
```
