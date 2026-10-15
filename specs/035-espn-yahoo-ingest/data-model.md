# Data Model: ESPN League Ingest (V34)

Everything here is additive. No existing column changes type, nullability or meaning for Sleeper rows (FR-013).

## Changed tables

### `league`
| Column | Type | Notes |
|--------|------|-------|
| `platform` | `text not null default 'SLEEPER'` with check `in ('SLEEPER','ESPN')` | FR-002. Never inferred from the key. |
| `platform_league_id` | `text null` | ESPN's own league id (stable across seasons). Null for Sleeper. |
| `scoring_untranslated` | `text[] null` | ESPN scoring items with no Sleeper key (R7). Non-empty means scoring-dependent pages are unavailable. |
| `unmodelled_slots` | `text[] null` | ESPN lineup slots with no app equivalent (e.g. `OP`, `DP`, `HC`). |

**Key**: an ESPN row's `sleeper_id` = `espn-{ffl|fba}-{leagueId}-{season}` (R14). `previous_league_id` points to the prior season's key, so the existing chain walk works unchanged.

**`settings_json`** is written in Sleeper keys only:

| Sleeper key | ESPN source |
|-------------|-------------|
| `leg` | `status.currentMatchupPeriod` |
| `playoff_teams` | `scheduleSettings.playoffTeamCount` |
| `playoff_week_start` | `matchupPeriodCount + 1` |
| `playoff_seed_type` | `0` |
| `divisions` | count of `scheduleSettings.divisions` |
| `waiver_type` | `acquisitionSettings` (FAAB → 2, else 0) |
| `waiver_budget` | `acquisitionSettings.acquisitionBudget` |
| `league_average_match` | `0` |

Every value is confirmed in T-verify. A key ESPN has no value for is **omitted**, never written as `0`, unless the Sleeper meaning of `0` is the true ESPN meaning (e.g. no median game).

**`status`**: the same value set Sleeper writes (`pre_draft`, `drafting`, `in_season`, `complete`), derived from `draftDetail` and `status.isActive`/`finalScoringPeriod`. This keeps the V21 champion gate correct.

### `manager`
| Column | Type | Notes |
|--------|------|-------|
| `platform` | `text not null default 'SLEEPER'` | |

An ESPN manager's `sleeper_user_id` = `espn:{memberGuid}` (the braces are kept as ESPN sends them). `display_name` comes from `displayName`; `avatar_id` is null.

### `draft`
| Column | Type | Notes |
|--------|------|-------|
| `platform` | `text not null default 'SLEEPER'` | |

`sleeper_draft_id` = `{leagueKey}-draft`. Exactly one draft row is written per ESPN season, even pre-draft, because the league picker enumerates leagues through drafts (`railLeague.ts` → `/api/drafts`).

`slot_to_manager` comes from `draftSettings.pickOrder` (slot index + 1 → team → manager id). `draft_type` maps `SNAKE` → `snake`, `AUCTION` → `auction`, anything else → `linear` with the raw value logged.

### `league_transaction`
`sleeper_transaction_id` = `espn:{transactionId}`. `type` is mapped into the existing V19 check set:

| ESPN type | App type |
|-----------|----------|
| `WAIVER` | `WAIVER` |
| `FREEAGENT` | `FREE_AGENT` |
| `TRADE_ACCEPT` / `TRADE` | `TRADE` |
| roster moves by the league manager | `COMMISSIONER` |

Lineup-only moves are **skipped** (not counted). `adds`/`drops` are keyed by Sleeper player id when matched, and by `espn:{playerId}` when not. Unmatched keys render by name through `platform_player_link` (see below). Exact ESPN type strings are confirmed in T-verify.

## New tables

### `manager_link`
| Column | Type | Notes |
|--------|------|-------|
| `manager_id` | `bigint not null references manager(id) on delete cascade` | the signed-in (Sleeper) manager |
| `linked_manager_id` | `bigint not null references manager(id) on delete cascade` | the claimed ESPN manager |
| `league_id` | `bigint not null references league(id) on delete cascade` | the season the claim was made in (provenance) |
| `created_at` | `timestamptz not null default now()` | |
| PK | `(manager_id, linked_manager_id)` | |

Rules:
- One ESPN manager can be claimed by **at most one** Sleeper manager (unique on `linked_manager_id`).
- A second claim attempt on the same team returns `409 ALREADY_CLAIMED`.
- `LeagueMembership`'s `me` = the header's manager ∪ its linked managers.

### `platform_player_link`
| Column | Type | Notes |
|--------|------|-------|
| `platform` | `text not null` | `ESPN` |
| `sport` | `text not null` | |
| `external_id` | `text not null` | ESPN player id |
| `player_id` | `bigint null references player(id)` | null when unmatched or ambiguous |
| `outcome` | `text not null` check `in ('MATCHED','AMBIGUOUS','UNMATCHED','ESPN_ONLY_POSITION')` | |
| `external_name` | `text not null` | ESPN `fullName`, for display when unmatched |
| `external_position` | `text null` | |
| `external_team` | `text null` | Sleeper-normalised abbreviation |
| `resolved_at` | `timestamptz not null` | |
| PK | `(platform, sport, external_id)` | |

- A `MATCHED` row is reused forever.
- `UNMATCHED`/`AMBIGUOUS` rows are re-tried on each refresh, since the player table grows. A row only moves to `MATCHED`, never back.
- A hand alias goes in the existing `player_alias` table with `source = 'ESPN'`, which `PlayerMatcher.buildStrict` already consults first.

### `league_credential`
| Column | Type | Notes |
|--------|------|-------|
| `platform_league_id` | `text not null` | ESPN league id. One credential covers all seasons of that league (R13). |
| `platform` | `text not null` | |
| `sport` | `text not null` | |
| `owner_manager_id` | `bigint not null references manager(id)` | who pasted it |
| `ciphertext` | `bytea not null` | AES-256-GCM over the JSON `{"espn_s2":…,"SWID":…}` |
| `nonce` | `bytea not null` | 12 random bytes per write |
| `key_version` | `int not null default 1` | for key rotation |
| `updated_at` | `timestamptz not null` | |
| `last_worked_at` | `timestamptz null` | |
| PK | `(platform, sport, platform_league_id)` | |

Only the `owner_manager_id` (via the header) can replace or delete it. No query selects `ciphertext` outside `LeagueCredentialRepository.forIngest`.

## Validation rules (from FRs)
- **FR-005**: all rows for one ESPN add/refresh are written inside one transaction. The credential row is written in the same transaction as the league rows.
- **FR-006**: every write is an upsert on the natural keys above (`league.sleeper_id`, `manager.sleeper_user_id`, `draft.sleeper_draft_id`, `(draft_id, pick_no)`, `(league_id, roster_id)`, `(league_id, week, roster_id)`, `(league_id, sleeper_transaction_id)`).
- **FR-012**: `scoringType` ∉ {`H2H_POINTS`, `TOTAL_POINTS`} → refuse with no writes.
- **FR-007**: a player row is never created or altered by ESPN ingest. ESPN only *links* to existing players.

## State: ESPN league season
```
(none) --add ok--> pre_draft | drafting | in_season | complete
in_season --refresh ok--> in_season | complete
any --refresh fails--> unchanged data; league_refresh.last_failure set (credential-free text)
complete --> never re-read (finished seasons are frozen)
```
