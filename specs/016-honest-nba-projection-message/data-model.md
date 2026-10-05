# Data model: Stop saying basketball has no projection source

**No data model change.** There's no migration (the highest stays V26), no new or
changed Java record, no `web/src/api.ts` type change, and no new table or column.

The only thing that changes is the *value* of two existing string fields:

| Field | Type | Owner | Change |
|---|---|---|---|
| `LeagueAnalysisService.Projections.reason` and `Matchups.reason` | `String` (nullable; null when available) | `engine/LeagueAnalysisService.java:118` | Text for non-NFL leagues: see [contracts/messages.md](contracts/messages.md) M1 |
| `ErrorHandler` 400 body `error` for `/api/ingest/projections` | `String` | `api/IngestController.java:142-147` | Text for non-NFL sports: M2 |

There are no state transitions. When each message is produced is unchanged.
