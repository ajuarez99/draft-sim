# Data model: Draft room UX (spec 024)

This feature adds one new table and widens one constraint, both in `V30__draft_target.sql`. Everything else it shows is computed from data that already exists.

## draft_target (new)

| Column | Type | Rules |
|---|---|---|
| `id` | bigserial PK | |
| `owner_sleeper_user_id` | text, not null | The `X-Sleeper-User` identity, the same token V8 uses for mocks. A target is private to this owner. |
| `sleeper_draft_id` | text, null | Set for a real Sleeper draft (shared by its live room and its projection room). It is deliberately **not** a foreign key to `draft`, so a re-ingest or rebuild can never cascade-delete a person's choices (R4). |
| `mock_session_id` | bigint, null, FK → `mock_draft_session(id)` **on delete cascade** | Set for a mock. |
| `player_id` | bigint, not null, FK → `player(id)` | Player ids are per sport, so this is unambiguous. |
| `rank` | int, not null | 0-based order within the list. |
| `created_at` | timestamptz, default now() | |

**Constraints:**
- `check ((sleeper_draft_id is null) <> (mock_session_id is null))`: exactly one scope is set.
- Unique `(owner_sleeper_user_id, sleeper_draft_id, player_id)` where `sleeper_draft_id` is not null, and `(owner_sleeper_user_id, mock_session_id, player_id)` where `mock_session_id` is not null. Two partial unique indexes, so a player appears at most once per list.
- Unique on `rank` within the same two scopes. This is enforced by the replace-whole-list write rather than by an index, because the write deletes and re-inserts the list inside one transaction.

**Index:** `(owner_sleeper_user_id, sleeper_draft_id)` and `(owner_sleeper_user_id, mock_session_id)`, both partial. These are the only access paths.

**Lifecycle:**
- **Replaced as a whole** by `PUT` (contracts/api.md): delete the owner+scope rows, then insert the new ordered list, in one transaction.
- **Copied** at fork time: `createSessionFromDraft` copies the owner's `sleeper_draft_id` list to the new `mock_session_id`.
- **Deleted** with the mock by cascade. A real draft's targets are never deleted by ingest.

**Derived, never stored** (spec Key Entities):
- **`taken`**: whether the player appears in the room's picks. Landed picks in the live room, the revealed board in the projection room, and `state.picks` in a mock.
- **`survival`**: the projection's `survivalByPick[nextOwnPick]`, only when the seat is known. Always absent in a mock.

**Concurrency (amended after plan review #10):** `replace` starts with `pg_advisory_xact_lock(hashtext(owner || ':' || scope))`. Without it, two simultaneous PUTs would both delete and then both insert, which trips the partial unique index and gives a 500.

## draft.pick_timer_seconds (new column; added after plan review #2)

`alter table draft add column pick_timer_seconds int` (nullable). It is written by `LeagueIngestService.ingestDraft` from Sleeper `settings.pick_timer`, and refreshed by `LiveDraftPoller.pollOnce`. Null means Sleeper didn't send one. `DraftRepository.upsert` also gains `draft_type = excluded.draft_type`, which was first-ingest-only before. Both values are read through a new `DraftRepository.format(draftId)` query; `DraftRow` is not widened (review #12).

## mock_draft_pick.source (widened)

`mock_draft_pick_source_check` goes from `('USER','BOT','LIVE')` to `('USER','BOT','LIVE','AUTO')`. AUTO means the user's own seat, decided by auto-pick (R5). It is dropped and re-added under the same name, as V4 did.

## Wire additions (mirror in `web/src/api.ts` in the same change)

| Record | Field | Type | Source |
|---|---|---|---|
| `SeatsResponse` | `draftType?` | `string \| null` | The `draft.draft_type` column, already stored (V1). It is optional because the frontend and backend deploy separately. |
| `SeatsResponse` | `pickTimerSeconds?` | `number \| null` | `draft.pick_timer_seconds` (V30). *Amended after plan review #2: originally planned on `LiveState`, which never reaches a pre-draft room.* |
| `MockPick` (TS) / `MockSessionState.PickView` (Java) | `source` | adds `'AUTO'` | V30. |
| new `DraftTargets` | `players: PlayerRef[]` (ordered), `missing: {sleeperId, name}[]` | from `draft_target`. On-board players come back as full `PlayerRef`s. A target no longer on the board goes in `missing`, and no ADP is invented (review #20). |
| `FeedPick` (TS only) | `auto?` | `boolean` | Set when `source === 'AUTO'` (review #9). |

**Client-only state:**
- The divider position: `localStorage['draftRoom.split']`, a fraction from 0 to 1, per device, as clarified.
- The "Hide drafted" toggle, per session.
