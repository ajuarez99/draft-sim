# D — Phase 0: live draft poller, tracking, incremental ingest

Design note, 2026-08-30. Scope: `LiveDraftPoller` + `POST /api/drafts/{id}/track` +
`DraftRepository.upsertPicks()`, per `claude/next-features-roadmap.md` §4 Phase 0.
Explicitly NOT this pass: Phase 4's SSE `/live-stream` endpoint or `LiveStatusBar`.

Real draft: fantasy(heart), 14 teams, draft `1391509064357273600`, league
`1391509063170293760`, `pre_draft` as of this writing, scheduled 2026-08-31 21:15 CDT.

## Decisions made

**1. `/track` keys on the Sleeper draft id, not the internal `draft.id`.**
`GET /api/drafts/{sleeperDraftId}/seats` (`LeagueController.java:37`) already uses the
Sleeper id as the path segment despite the generic `{id}` in the roadmap's prose. Two
sibling routes under the same `/api/drafts/{id}/...` prefix using two different id
spaces would be a real footgun. The caller already has the Sleeper draft id (it's
what's in Sleeper's own URL/UI) — the internal bigint requires an extra lookup they
don't otherwise need. `POST /api/drafts/{sleeperDraftId}/track`, added to
`LeagueController` next to `seats()`, not a new controller class.

**2. In-memory `ConcurrentHashMap<Long, Thread>` of active pollers. No V3 migration,
no `tracking_started_at` column.** This is a single-instance deployment for one draft
night (no clustering anywhere in this codebase, `spring.threads.virtual.enabled: true`
already set in `application.yml`, no scheduling infra exists). The realistic failure
mode is "the process restarts mid-draft," and the fix for that is "call `/track`
again" (one curl call), not a persisted-state reconciliation mechanism. Calling
`/track` twice for the same draft is idempotent via `computeIfAbsent` — the second
call finds the map entry and starts nothing new.

**3. `upsertPicks` re-upserts the FULL pick list Sleeper returns, every tick — no
diffing against local state.** Max 210 rows (14×15), `ON CONFLICT DO UPDATE` is
trivial at that volume every 10s, and diffing means tracking "which pick_nos have I
already seen" as extra poller state for no measured benefit. This also makes the
poller self-healing if Sleeper corrects a `picked_by` resolution on a later tick, or a
draft-day trade reassigns a slot mid-draft.

**4. `adp_at_time` must be `coalesce`d, not overwritten, on conflict — this is a real
bug I'd otherwise ship.** Both `LeagueIngestService.ingestDraft` (`LeagueIngestService.java:145`,
comment: "adp_at_time filled in later by BoardService") and the poller always pass
`adp_at_time = null` for freshly-observed picks. A naive
`adp_at_time = excluded.adp_at_time` on conflict would null out whatever
`/api/ingest/board`'s backfill step already wrote, on *every single poll tick* for the
rest of the draft. Fixed SQL:
```sql
insert into draft_pick (draft_id, pick_no, round, draft_slot, manager_id, player_id, adp_at_time)
values (?, ?, ?, ?, ?, ?, ?)
on conflict (draft_id, pick_no) do update set
    round = excluded.round,
    draft_slot = excluded.draft_slot,
    manager_id = excluded.manager_id,
    player_id = excluded.player_id,
    adp_at_time = coalesce(draft_pick.adp_at_time, excluded.adp_at_time)
```
`unique(draft_id, pick_no)` already exists (`V1__init.sql:82`) — confirmed, no
migration needed for the constraint itself.

**5. Shared pick-mapping logic extracted into a static `PickMapper.toPickRow(...)`.**
The exact logic already in `LeagueIngestService.ingestDraft` (lines 126–146: `picked_by`
preferred, falls back to `draft_slot` on autopick, unmatched Sleeper player id → null,
never guessed) moves into a new pure static method both `LeagueIngestService` and
`LiveDraftPoller` call. This logic currently has **zero unit test coverage** (no
`LeagueIngestServiceTest` exists) — extracting it is a coverage win as well as a
reuse win.

**6. Poller design: a pure, unit-testable `pollOnce` wrapped by a thin virtual-thread
loop.** `pollOnce` does one Sleeper fetch + (conditionally) one upsert + one status
write, and returns whether to keep polling. It runs — polling status only, no pick
ingest — while `status == pre_draft`, starts ingesting once it observes `drafting`,
and returns `false` (stop) once it observes `complete`. This means `/track` is safe to
call any time before the draft starts, which is the intended usage: call it tonight,
it no-ops harmlessly until 21:15 CDT tomorrow. `draft.status` is written
unconditionally every tick (single-row UPDATE by PK, effectively free) rather than
tracked-and-compared — same "don't build machinery you don't need at this volume"
reasoning as decision 3.

**7. New small repository methods needed, beyond `upsertPicks`:**
   - `ManagerRepository.idsBySleeperUserId(): Map<String, Long>` — the poller needs to
     resolve `picked_by` (a Sleeper user id) the same way `LeagueIngestService`'s
     `managerByUserId` does, but that map is currently built inline inside
     `upsertManagers()` and never exposed. Mirror `PlayerRepository.idsBySleeperId`
     (`PlayerRepository.java:55`).
   - `DraftRepository.updateStatus(long draftId, String status)` — a focused
     `update draft set status = ? where id = ?`. The existing `upsert(...)` requires
     league/season/rounds/teams/type/startTime/slotToManagerJson the poller doesn't
     have on hand each tick and shouldn't need to re-fetch just to flip a status.

**8. Auth and CORS: nothing new to build.** `/api/drafts/{id}/track` is a `POST`
under `/api/*`, already covered by `ApiTokenFilter` (inert with no `API_TOKEN` set,
the local-dev default). Unlike the tendencies PUT/DELETE bug (`claude/lessons.md`
#14), `WebConfig` already allows `POST` in `allowedMethods` (`WebConfig.java:30`) —
no CORS gap here.

## Implementation checklist

- [ ] `ManagerRepository.java` — add `idsBySleeperUserId(): Map<String, Long>`
      (`select sleeper_user_id, id from manager`), same shape as
      `PlayerRepository.idsBySleeperId`.
- [ ] `DraftRepository.java` — add `updateStatus(long draftId, String status)`.
- [ ] `DraftRepository.java` — add `upsertPicks(long draftId, List<PickRow> picks)`
      next to `replacePicks` (`DraftRepository.java:51`), using the coalesce SQL
      above. Early-return on an empty list (matches the existing
      `if (raw == null || raw.isEmpty()) return 0;` pattern in
      `LeagueIngestService.java:124`). The binder lambda is identical to
      `replacePicks`'s — factor it into a shared private helper if that's clean, don't
      duplicate the 7-column null-handling block verbatim.
- [ ] New `backend/src/main/java/com/ballknowers/draftsim/ingest/PickMapper.java` —
      static `toPickRow(long draftId, Map<String,Object> rawPick,
      Map<String,Long> managerByUserId, Map<Integer,Long> slotLookup,
      Map<String,Long> playerIdsBySleeperId)`, extracted verbatim from
      `LeagueIngestService.ingestDraft` lines 126–146. `adp_at_time` always null from
      this path.
- [ ] `LeagueIngestService.ingestDraft` — replace the inline mapping loop with a call
      to `PickMapper.toPickRow`. No behavior change; confirm with the differential
      replay test below.
- [ ] New `backend/src/main/java/com/ballknowers/draftsim/ingest/LiveDraftPoller.java`,
      `@Component`:
      - `ConcurrentHashMap<Long, Thread> active`.
      - `TrackResult track(DraftRepository.DraftRow draft)` — record
        `TrackResult(boolean started, String status)`, uses
        `active.computeIfAbsent(draft.id(), id -> spawn(draft))`.
      - `Thread spawn(DraftRow draft)` — `Thread.ofVirtual().name("draft-poll-" + draft.id()).start(() -> loop(draft))`.
      - `void loop(DraftRow draft)` — `while (!interrupted) { try { if (!pollOnce(draft)) break; sleep(POLL_INTERVAL); } catch (InterruptedException e) { break; } catch (Exception e) { log.warn("poll tick failed for draft {}", draft.id(), e); /* keep looping, transient */ } } active.remove(draft.id());`
      - `boolean pollOnce(DraftRow draft)` (package-private, no sleep, unit-testable):
        fetch `sleeper.draft(draft.sleeperDraftId())`, unconditionally
        `drafts.updateStatus(draft.id(), status)`; if `"complete"` → return `false`;
        if `"pre_draft"` → return `true` (no pick fetch); otherwise fetch
        `sleeper.draftPicks(...)`, build `slotLookup` from
        `draft.slotToManager()` (string-keyed slot → `Number` → `long`, same
        conversion as `LeagueController.seats()` line 46), map via `PickMapper`
        using `managers.idsBySleeperUserId()` and `players.idsBySleeperId(Sport.NFL)`,
        call `drafts.upsertPicks(draft.id(), rows)`, return `true`.
      - `POLL_INTERVAL = Duration.ofSeconds(10)` — hardcoded constant per the
        roadmap's own "~10s" suggestion; no new `@ConfigurationProperties` class for
        Phase 0.
      - `@PreDestroy` — interrupt every thread in `active`, so a `bootRun` restart
        doesn't leave dangling pollers still hitting Sleeper after shutdown began.
- [ ] `LeagueController.java` — inject `LiveDraftPoller`, add:
      ```java
      @PostMapping("/drafts/{sleeperDraftId}/track")
      public ResponseEntity<?> track(@PathVariable String sleeperDraftId) {
          Optional<DraftRepository.DraftRow> draft = drafts.bySleeperId(sleeperDraftId);
          if (draft.isEmpty()) return ResponseEntity.notFound().build();
          LiveDraftPoller.TrackResult r = poller.track(draft.get());
          return ResponseEntity.ok(Map.of(
              "draftId", sleeperDraftId, "tracking", true,
              "alreadyTracking", !r.started(), "status", r.status()));
      }
      ```
      404-when-not-ingested matches `seats()`'s existing convention
      (`LeagueController.java:39-40`); the idempotent-response shape matches
      IngestController's "safe to re-run" convention.
- [ ] Manual smoke test locally before considering this done: ingest fantasy(heart)
      (`POST /api/ingest/all/1391509063170293760`), then
      `POST /api/drafts/1391509064357273600/track`, confirm 200 and a
      `draft-poll-<id>` thread logging a tick roughly every 10s while `status` stays
      `pre_draft`, with no pick-fetch calls (check via `SleeperClient` logging or a
      breakpoint — cheap, and it's the one branch guaranteed to run tomorrow).

## Test plan

**Unit (pure, no DB — Mockito is already on the classpath via
`spring-boot-starter-test`, unused elsewhere so far but available):**
- [ ] `PickMapperTest` (new) — blank `picked_by` falls back to `draft_slot`;
      present `picked_by` wins over slot (simulate a traded pick: `picked_by` maps to
      a manager different from what `slotLookup` would give); an unmatched Sleeper
      player id resolves to `playerId == null` without throwing;
      `adp_at_time` is always `null` from this path.
- [ ] `LiveDraftPollerTest` (new), `SleeperClient`/`DraftRepository`/
      `ManagerRepository`/`PlayerRepository` mocked:
      - `pollOnce` with Sleeper status `"drafting"` calls `upsertPicks` with correctly
        mapped rows and returns `true`.
      - `pollOnce` with status `"pre_draft"` does **not** call `draftPicks`/
        `upsertPicks`, still calls `updateStatus`, returns `true`.
      - `pollOnce` with status `"complete"` calls `updateStatus` once, returns
        `false`.
      - `track()` called twice for the same draft id results in exactly one
        `computeIfAbsent` insertion (assert `active` map size stays 1 after two
        calls, or assert the spawn function is invoked once via a counting stub).

**Integration (real Postgres) — a genuine gap to flag: no Testcontainers or embedded-DB
harness exists anywhere in this repo today.** Every test currently in
`backend/src/test` is a dependency-free pure-JVM JUnit test with no Spring context;
`DraftRepository`/`PlayerRepository`/`ManagerRepository` have **zero existing
automated coverage** — all repository verification to date (HANDOFF: "Schema + every
repository query") was done manually via `psql`/`PREPARE`/`EXECUTE`, per
`claude/environment.md`'s "Recipe: real Postgres." Recommendation: don't add
Testcontainers under this deadline (new dependency, new risk, not worth it for one
feature); instead:
- [ ] New `DraftRepositoryUpsertPicksIT` (or similar), `@SpringBootTest` — reuses the
      real `DraftRepository` bean + Flyway-migrated schema against
      `application.yml`'s existing local-dev default (`localhost:5433/draftsim`), the
      same throwaway Postgres cluster HANDOFF's session already stood up. Gate with
      `Assumptions.assumeTrue(...)` in a `@BeforeAll` connectivity check so
      `./gradlew test` *skips* (not fails) on a machine with no Postgres up, keeping
      the current "test suite needs nothing external" property intact for anyone who
      doesn't have it running, while actually exercising real SQL on this machine.
      - `upsertPicks` called twice with identical rows → `picks(draftId)` returns the
        same N rows, not 2N (the core idempotency requirement).
      - `upsertPicks` with `adp_at_time = null`, then a manual `UPDATE` simulating
        `BoardService`'s backfill on one pick, then `upsertPicks` again with the same
        (still-null-adp) row → assert `adp_at_time` is **not** clobbered back to null
        (pins decision 4's fix).
      - `upsertPicks` with a changed `manager_id` for an existing `pick_no`
        (simulating a late-resolved `picked_by` or a draft-day trade) → row updates
        in place, count unchanged.
      - Regression guard, one line: a pick under a draft row with
        `status = 'drafting'` is excluded from `allCompletedPicks()` — confirmed
        already true by reading `DraftRepository.java:99-111`
        (`where d.status = 'complete' and p.manager_id is not null`), but pin it as
        an explicit test so a future refactor of that query can't silently start
        leaking in-progress picks into `ProfileService.fit()`. This is the parallel
        concern the roadmap's §2(a) contamination guard implies for D — no new
        mechanism needed, just a test that catches regression of the existing one.
- [ ] Differential replay test — the best available substitute for "verify against a
      real live draft" (HANDOFF §5: nothing has ever exercised true `drafting`-status
      behavior). Pull one of the two already-`complete` real drafts (Ball Knowers
      2026, draft `1346366555776126976`, or 2025, `1254190894563729408`) via a real
      `SleeperClient.draftPicks` call (legitimate — this machine has real internet
      per `claude/environment.md`), run it through `PickMapper.toPickRow` +
      `upsertPicks`, and assert the resulting `draft_pick` rows are identical
      (`pick_no`/`round`/`draft_slot`/`manager_id`/`player_id`) to what
      `LeagueIngestService.ingestDraft`'s `replacePicks` path already produces for the
      same draft after a real `/api/ingest/all` run. Proves the extraction in the
      checklist didn't change behavior, without needing a `drafting`-status draft to
      exist.

## Manual pre-draft-night checklist (for Allan, not code)

- [ ] Run `POST /api/ingest/board` for fantasy(heart) (via `/api/ingest/all/1391509063170293760`
      or the `/board` step alone) before the draft goes live — the poller
      deliberately never rebuilds the board itself; nothing in `LiveDraftPoller`
      calls `BoardService`, by design (roadmap §4 Phase 0).
- [ ] Benchmark actual Monte Carlo wall-clock time at current iteration counts
      (the roadmap's own separate Phase 0 item) to pick a re-simulation debounce
      threshold — not required for the poller to function, but needed before
      anything later re-simulates on every poll tick.
- [ ] Dry-run the poller against a real `drafting`-status draft before tomorrow: start
      a disposable Sleeper mock draft (a few minutes in Sleeper's own UI, doesn't
      touch fantasy(heart)'s real rows), ingest it, and `POST .../track` against it to
      actually observe a `pre_draft → drafting → complete` transition end-to-end.
      This is the one thing in this plan only a real live draft can fully verify
      (HANDOFF §5); a disposable mock draft is the closest available substitute.
- [ ] Call `/track` on fantasy(heart)'s real draft any time before 21:15 CDT tomorrow
      — per decision 6 it safely no-ops on `pre_draft`, no need to time it.
- [ ] Keep the backend process running and reachable through 21:15 CDT tomorrow (no
      restart, no laptop sleep) — decision 2 means a restart silently drops tracking.

## Open risks

- Sleeper's `drafting`-status `/draft/{id}` and `/draft/{id}/picks` shapes have never
  been observed live. The differential replay test only proves the mapping logic is
  stable for `complete` drafts — it does not prove the `status` field actually
  transitions the way assumed, or that pick objects are shaped identically mid-draft.
  Mitigated only by the manual dry-run above, not by more code.
- 10s poll interval is the roadmap's guess, not a measured value — cheap to tighten
  later if picks land faster than expected in an autopick-heavy stretch.
- No `/untrack` endpoint. If `/track` hits the wrong draft id, the only recovery in
  Phase 0 is an app restart (kills all pollers). Not in the roadmap's Phase 0 list;
  worth a one-line follow-up if time allows, not required to ship.
- In-memory-only tracking state (decision 2) means a mid-draft restart stops polling
  silently — nothing pages anyone, matches this project's current lack of alerting
  generally, but Allan should know to watch for it.
- `pollOnce`'s broad catch-and-continue means a persistent failure (bad draft id,
  Sleeper fully down) retries forever without surfacing anywhere except logs — no new
  gap relative to the rest of this project, just worth knowing going in.

### Critical files for implementation

- `backend/src/main/java/com/ballknowers/draftsim/store/DraftRepository.java`
- `backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueIngestService.java`
- `backend/src/main/java/com/ballknowers/draftsim/store/ManagerRepository.java`
- `backend/src/main/java/com/ballknowers/draftsim/api/LeagueController.java`
- `backend/src/main/java/com/ballknowers/draftsim/ingest/SleeperClient.java` (read-only
  reference — no changes needed, `draft()`/`draftPicks()` already sufficient)
