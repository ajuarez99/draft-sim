# Data model: spec 034

## Stored (new): `league_bracket_match`, migration `V33__league_bracket_match.sql` (amended after review F1: V32 is taken by 033)

V31 is the highest migration on `main` and on every branch (checked 2026-10-14). This table
is append-only.

| Column | Type | Notes |
|---|---|---|
| `league_id` | bigint, FK `league(id)` | one league-season |
| `match_id` | int | Sleeper `m` |
| `round` | int | Sleeper `r` |
| `t1_roster_id` | int, nullable | null while a slot is still `t1_from` an unplayed match |
| `t2_roster_id` | int, nullable | same |
| `winner_roster_id` | int, nullable | Sleeper `w`; null until played |
| `loser_roster_id` | int, nullable | Sleeper `l` |
| `placement` | int, nullable | Sleeper `p`: 1 = final, 3 = third place, 5 = fifth; null for an elimination game |
| PK | `(league_id, match_id)` | |

Write rule (*superseded by plan.md F5: never delete on an empty response; a fully-played bracket is frozen*): for one `league_id`, delete then insert in one transaction, so a re-ingest
replaces the season's bracket and never leaves a mix of two fetches. An empty Sleeper
response stores zero rows. That's "no bracket yet", which is not an error.

Only the **winners** bracket is stored (research R2).

## Computed (not stored)

### GameKind

Applies to one scored, paired game (a `PairedGame` from `pairedWithScores`):

- `REGULAR`: `week < playoffWeekStart`, or the league has no playoff start (`< 2`).
- `PLAYOFF`: `week >= playoffWeekStart` and the unordered roster pair appears in that season's
  `league_bracket_match` (as `t1`/`t2`).
- `CONSOLATION`: `week >= playoffWeekStart`, the season has bracket rows, and the pair isn't in
  them.
- `POSTSEASON_UNSPLIT`: `week >= playoffWeekStart` and the season has **no** bracket rows.

> **Amended after review (plan.md F2–F4):** a game is PLAYOFF/PLACEMENT only if a bracket row *of that week's round* has the pair (PLACEMENT = `p` 3 or 5). A round with a null slot makes the whole week UNSPLIT (`BRACKET_PENDING`). Cell statuses gain `NOT_YET_MET` (F11). `NO_HISTORY` = no season before the requested one (F10). SeasonCell gains `NO_GAME` and `source` (F7/F8), and seasonGrid gains `currentWeek` (F9).

Invariant: REGULAR + PLAYOFF + CONSOLATION + POSTSEASON_UNSPLIT = all meetings (SC-002).

### PairRecord

One per unordered current-manager pair in the chain.

- `aManagerId`, `bManagerId`, with `a < b` by id.
- `meetings`, `aWins`, `bWins`, `ties`.
- `byKind`: map GameKind → {aWins, bWins, ties}. Sums to the totals.
- `status`: `MET` | `NEVER_MET` | `NO_SHARED_HISTORY` (research R8).
- `finals`: list of `{season, aPoints, bPoints, winner}` for PLAYOFF games whose bracket row
  has `placement = 1`.
- `games`: chronological `{season, week, aPoints, bPoints, winner, kind}`.

The outcome comes from the shared helper (research R3): A if aPoints > bPoints, B if
bPoints > aPoints, otherwise TIE.

### MatrixRow

- `managerId`, `teamName` (this season's team name, falling back to the username, same as
  the history page), `avatarId`, `isMe`.
- `allOpponents`: {wins, losses, ties, meetings} over every scored game in the chain, former
  managers included.
- `formerOpponentMeetings`: the meetings in `allOpponents` that aren't against a current
  manager.

### SeasonCell

One per (current manager, week of the requested season).

- `week`, `isPlayoffWeek`.
- `state`: `OPPONENT` | `BYE` | `TBD`.
- `opponentManagerId`: null unless OPPONENT.
- `priorRecord`: {wins, losses, ties, meetings}. Only meetings strictly before
  `(season, week)`. Null unless OPPONENT.
- `result`: {points, opponentPoints, outcome W/L/T, kind}. Null when the week isn't scored.

Invariant: if A's cell names B in week W, B's cell names A (SC-003).

### RivalryPairing

- `aManagerId`, `bManagerId`: `bManagerId` is null only for the single unpaired slot.
- `score`: rounded to 3 decimals for display. Comparison uses the unrounded value at 1e-9.
- `reason`: {`code` (research R7), `text`, `numbers` (the values the text prints)}.
- `thin`: meetings < N.

### RivalryRecommendation

- `status`: `OK` | `NO_HISTORY` (no scored season before or in the requested one) |
  `TOO_MANY_MANAGERS` (> 16) | `TOO_FEW_MANAGERS` (< 2).
- `minMeetings` (N), `minMeetingsLabel` ("hand-set").
- `pairings[]`, `unpaired` (`{managerId, why}` or null).
- `thinCount`: how many pairings are thin. It's printed as a headline sentence when it's more
  than half.
- `note`: the fixed sentence that this doesn't change the Sleeper schedule (FR-012).

## Config (new): `draftsim.rivalry` in `config/weights.yml`

`RivalryProperties(Integer minMeetings, Double playoffBonus, Double championshipBonus,
Integer maxManagers)`. Every value is labelled ARBITRARY. A missing block binds null, and the
recommendation section then answers `status: CONFIG_MISSING` rather than inventing defaults.
That's the `DraftGradeProperties` pattern. A present value is range-checked at startup:
`minMeetings` 1–10, bonuses 0–10, `maxManagers` 2–20.
