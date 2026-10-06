# Data model: AI weekly recap (spec 020)

> **Amended after review (2026-10-06):** rewritten in place for [plan-review.md](plan-review.md)
> F1, F2, F5, F6, F7, F11, N2 and N7. What changed from the first draft:
> - The row now keeps the **last READY recap** separate from the **last attempt**, so a failed
>   regeneration no longer erases a good recap (F11).
> - The cache key includes the model and the prompt version (F2).
> - Transient failures get a `retry_after` (F1).
> - Rows key on the **resolved** league row (F5).
> - The attempt log counts **API calls** (N7).
> - `input_json` is `text` (N2).
> - The performer's current pro team was dropped (F7).

One migration, **V29** (V28 `roster_players` is the highest on `origin/main` @ `36f77ca`, and
no branch has a V29; review-confirmed). It's append-only per AGENTS.md.

## Table `league_feature` (entitlement, R7)

| Column | Type | Notes |
|---|---|---|
| `league_id` | `bigint` FK → `league(id)` ON DELETE CASCADE | a **season** row. Checked against the **resolved** row a request lands on, not the URL's (F5) |
| `feature` | `text` CHECK (`feature in ('RECAP')`) | widens when a second feature exists |
| `granted_at` | `timestamptz not null default now()` | |
| `note` | `text` null | operator free text (later a billing ref) |

PK `(league_id, feature)`. Revoking deletes the row. Stored recaps stay, unreadable until it's
granted again.

## Table `league_recap`

One row per (resolved league row, week). The row's columns fall into two groups.

**Key**

| Column | Type | Notes |
|---|---|---|
| `id` | `bigserial` PK | |
| `league_id` | `bigint` FK → `league(id)` ON DELETE CASCADE | the **resolved** row (`WeeklyReportService.Result.season`'s league), so season is implied (F5) |
| `week` | `int not null` | unique `(league_id, week)` |

**Last READY recap.** This is what the card shows. It's never overwritten by a failure (F11).

| Column | Type | Notes |
|---|---|---|
| `ready_key` | `text` null | the cache key it was generated under (see "Cache key" below) |
| `ready_numbers_hash` | `text` null | hash of the input's numeric projection, used to label revisions (F7) |
| `ready_input_json` | `text` null | the exact canonical bytes sent; `text`, not `jsonb`, so it re-hashes (N2) |
| `headline` | `text` null | |
| `sections` | `jsonb` null | `[{title, body, cites[]}]` |
| `model` | `text` null | e.g. `claude-haiku-4-5`, which the card names |
| `prompt_version` | `text` null | SHA-256 of `system-prompt.md` + the derived output schema (F2) |
| `input_tokens` / `output_tokens` | `int` null | from `usage`; SC-003 |
| `revision` | `int not null default 0` | +1 per READY write; 0 means never READY |
| `revision_reason` | `text` null | `NUMBERS_CHANGED`, `NAMES_CHANGED` or `MODEL_OR_PROMPT_CHANGED`; null on revision 1 (F7) |
| `generated_at` | `timestamptz` null | |

**Last attempt.** This is what failed, and when to try again.

| Column | Type | Notes |
|---|---|---|
| `attempt_key` | `text` null | cache key of the most recent generation attempt |
| `attempt_status` | `text` null | CHECK in (`READY`, `FAILED`) |
| `failure_reason` | `text` null | deterministic: `UNGROUNDED`, `REFUSED`, `TRUNCATED`, `MALFORMED`; transient: `API_ERROR`, `RATE_LIMITED_UPSTREAM` (F1, F10) |
| `failure_detail` | `jsonb` null | e.g. unmatched tokens, bad cites, unbound names. Never a key or raw headers. Not sent to the page |
| `stop_reason` | `text` null | as returned |
| `retry_after` | `timestamptz` null | set only for transient reasons: now + `recap.transient-retry-minutes` (15, ARBITRARY) (F1) |
| `attempted_at` | `timestamptz` null | |

### Cache key (F2)

`SHA-256(canonical input bytes ‖ model id ‖ prompt_version)`. The canonical input is
the `RecapInput` JSON with sorted keys and no whitespace. Decimals are serialized from
`BigDecimal` at 2 dp (F6).

- A change of model or prompt changes every key. Weeks then regenerate lazily when opened, inside
  the caps. That's an operator-caused spend event, named in R2.
- The **numbers hash** is SHA-256 of the input with every name field blanked. If the full key
  changed but the numbers hash didn't, the revision reason is `NAMES_CHANGED`, or
  `MODEL_OR_PROMPT_CHANGED` when the model or prompt is what differs.

### Table `league_recap_attempt` (caps, N7)

| Column | Type | Notes |
|---|---|---|
| `league_id` | `bigint` FK → `league(id)` ON DELETE CASCADE | resolved row |
| `at` | `timestamptz not null` | |

- **One row per Anthropic API call**, so a grounding retry is two rows.
- The cap day is the **UTC** calendar day.
- Check-and-insert runs **inside the single flight**.
- Two caps: per league (`recap.max-calls-per-league-per-day`, 10) and global
  (`recap.max-calls-per-day`, 50). Both are ARBITRARY.
- Index: `(league_id, at)` and `(at)`.

**JDBC binds:** `jsonb` is bound via `?::jsonb`, the same as the existing repositories. Timestamps
follow the existing `LeagueWeekFetchRepository:59` pattern. The review notes (N2) that lessons #9
is specifically about `Types.TIMESTAMP_WITH_TIMEZONE`, so this is convention, not a trap. An IT
round-trips all three tables against real Postgres anyway.

## Java records (backend)

```
RecapInput            // canonical, hashed, sent. R6: no user ids / avatar ids / roster ids / isMe
  int season, int week, String sport, String basis (nullable; NBA ALL_GAMES_PLAYED)
  List<RecapMatchup> matchups      // home/away: teamName, points, record, scoreRank; margin (BigDecimal 2dp), winner
  RecapExtremes weekHigh, weekLow  // teamName, points (BigDecimal 2dp)
  List<RecapPerformer> topPerformers (NFL)   // playerName, position, opponent, isAway, teamName, points
  List<RecapNight> bestNights (NBA)          // + date (ISO, atomic token; R5)
  List<RecapWeek> bestWeek (NBA)             // + gamesPlayed
  List<RecapAward> awards                     // kind, teamName, detail
  List<RecapOmitted> awardsOmitted, sectionsUnavailable  // kind/section + reason code
  // dropped (F7): Performer.team, the player's CURRENT pro team, not as of the week

RecapOutput           // structured-output schema; NO array-size annotations (F10). 3..5 enforced in Java → MALFORMED
  String headline
  List<Section> sections   // Section(String title, String body, List<String> cites)

GroundingResult(boolean ok, List<String> unmatched, List<String> badCites,
                List<String> unboundNames, List<String> basisViolations)

RecapView             // API response, see contracts/api.md
```

`scoreRank` uses `WeeklyAwards.competitionRank(scores, points)`, extracted from
`deservedBetter`'s `allScores.indexOf(points) + 1` (`WeeklyReportService:459`). That gives one rank
rule for the award text and the field (F6).

## TypeScript (`web/src/api.ts`)

`RecapResponse` mirrors `RecapView` field for field, in the same change (FR-010). The `state`
union is closed (`FEATURE_OFF | NOT_ENTITLED | WEEK_NOT_FINAL | GENERATING | READY | FAILED |
RATE_LIMITED`). A state the backend adds without updating the type would be a silent failure,
the lessons class for a stale `api.ts`.
