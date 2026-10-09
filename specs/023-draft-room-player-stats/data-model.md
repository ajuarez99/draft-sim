# Data model: Player stats in the draft room

There are no database changes. Everything below is client-side state, plus three fields added to
existing responses (contracts/api.md).

## StatChoice (stored per device)

| Field | Type | Rule |
|---|---|---|
| `v` | `1` | Schema version. Any other value reads as "never chose", so the default is used. |
| `columns` | `string[]` | Ordered column ids from `PICKABLE`. Duplicates are dropped on read. Unknown ids are skipped on read, without error (spec US2 scenario 6). |

- **Key**: `bk.draftStats.v1` in `localStorage`, shared by live and mock rooms (FR-008).
- **Absent, unreadable or blocked storage**: use `DEFAULT_COLUMNS` (research R6). Writes are
  wrapped in try/catch, like `pickCardsPref.ts`. If storage is blocked, the in-memory choice
  still works for this page load.
- **Empty list**: allowed. It's a real choice (spec US2 scenario 4), and is stored as
  `columns: []`. It is not treated as absent.
- **Reset**: writes `DEFAULT_COLUMNS` explicitly, so that is what's remembered from then on.

## DraftStatRow (derived, never stored)

There is one per undrafted pool player. It's produced by `joinPoolStats(pool, leaderboardRows)`.

| Field | Type | Source |
|---|---|---|
| `player` | `PlayerRef` | The room's pool: position, ADP, `sleeperId` |
| `stats` | `LeaderboardRow \| null` | The leaderboard row with the same `sleeperPlayerId`, or null |
| `reason` | `'NO_SEASON_GAMES' \| null` | Set exactly when `stats` is null |
| `survivalNext` | `number \| null` | Survival to the member's next pick. Null when the seat is unknown or in a mock |
| `fillsSlot` | `string \| null` | The sheet's existing open-slot tag |

**Invariants** (unit-tested):
- `rows.length === pool.length`, and every pool player appears once.
- A drafted player is never in the input pool. The pool is the room's undrafted list, re-derived
  on every landed pick, so FR-004 holds by construction.
- Sorting: rows with `stats === null`, or with a null value in the sort column, go last in both
  directions. Otherwise the order is `statLeaderboard.ts`'s comparator (games, name, id
  tie-breaks).

## ShownSeason (derived from the leaderboard response)

| Field | Source | Shown as |
|---|---|---|
| `season` | `StatLeaderboard.season` | "2025–26 stats" (Sleeper start year → "YYYY–YY") |
| `fellBack` | `requestedSeason != null` | "Last season's play, not a projection" |
| `scoringSeason` | new field | "Fantasy points under 2025–26 Ball Knowers scoring" |
| `scoringMatchesRequested` | new field | When `false`: "This league's scoring has changed since then." |
| `window` | `StatLeaderboard.window` | "Season", "Last 10" or "Last 5" |

## View state (in memory only, kept across pick updates)

`view: 'tiers' | 'stats'`, `sort: SortState`, `window`, `mode`, `position filter`,
`likelyOnly: boolean` and the modal's open state, including unsaved toggles. None of these reset
when a pick lands (FR-004, US2 scenario 8).
