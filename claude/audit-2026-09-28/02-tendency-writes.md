# 02 — Manager tendency writes have no scoping, and contradict the page copy

**Severity: High. Verified locally,** and the state was restored.

## What's there now

- `backend/.../api/ManagerController.java:53` (`PUT /api/managers/{id}/tendencies`) and `:67`
  (`DELETE`) take no identity and run no membership check.
- The body accepts `reachBias`, `unpredictability` and `note`. The first two blend into
  `effectiveReachBias` (provenance `BLENDED`), so a write changes **every user's**
  simulations for that manager.
- Out-of-range values are silently clamped rather than rejected.
- The `/managers` page copy says stated tendencies "aren't something you type in", and that
  a note is "a reminder for yourself" that "never changes how a mock or live sim drafts".
  - The API does let you type tendencies in; only the UI hides them.
  - Notes are stored per manager, not per author, so every signed-in viewer sees every
    note. That includes profanity currently in the local DB.

## Fix

1. **Allan decides one thing first:** are stated tendencies a feature or not?
   - *Not a feature* (matches the copy): the API rejects `reachBias` and `unpredictability`
     with a 400. Only `note` is writable.
   - *A feature:* keep them, but make them member-only, and rewrite the page copy to say
     they change sims for everyone.
2. **Scope every write:** require an identity plus `membership.canSeeManager(caller,
   managerId)` (already used at `LeagueHistoryController.java:324`). A non-member gets 404,
   matching reads.
3. **Notes:** make them private per author, as the copy promises. That's a new migration:
   check the highest `V<n>__*.sql` and add the next one; never edit an applied one. Or, if
   Allan prefers shared notes, change the copy to "visible to everyone in your leagues".
   Existing notes have no author recorded; ask Allan whether to drop them or assign them to
   the configured owner.
4. **Validate:** a 400 for out-of-range numbers, and a length cap on `note` (140, matching
   the conduct list).
5. Mirror any response-shape change in `web/src/api.ts` in the same commit.

## Not in scope

- Moderating note content.

## Acceptance criteria

- [ ] Against a real `bootRun`: no identity → rejected; a non-member → 404; a member → 200.
- [ ] A note write leaves `effectiveReachBias` byte-identical. Check it before and after.
- [ ] If notes go private, a second signed-in member doesn't see the first member's note.
- [ ] The page copy and API behaviour agree. Quote both in the verification write-up.
- [ ] Tests cover no identity, a non-member, a member, and an out-of-range value.

## Decided 2026-09-29 (Allan)

- **Notes only.** The API rejects `reachBias` and `unpredictability` with a 400; `note` is the only writable field.
- **Notes are private per author.** Key on (author, manager, sport) with a new migration, and the GET returns only the caller's own notes.
