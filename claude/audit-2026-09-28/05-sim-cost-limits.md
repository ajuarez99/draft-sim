# 05 — Simulation cost has a ceiling per request but none across requests

**Severity: Medium. One timing measured locally; production capacity is a guess.** Nothing
was load-tested.

## What's there now

- `backend/.../engine/SimulationRequest.java:36-37` clamps `iterations` to 1..20,000
  (default 1,000 when <= 0).
- `backend/.../api/SimulationController.java:52` (`POST /api/sims`) and `:63`
  (`POST /api/sims/stream`) check draft visibility and nothing else: no concurrency cap, no
  per-caller limit. The stream starts its own virtual thread per request (`:72`).
- `backend/.../engine/MonteCarloRunner.java:49` submits **one virtual thread per
  iteration**, so a single request already fans out across every core. A second request
  doesn't wait; it competes. It also holds every `RunResult` in memory until `aggregate()`.
- The only `Semaphore` in `backend/src/main` is `refresh/SingleFlight.java:29`, for spec
  009's refresh. That refresh is stale-gated and deduplicated per chain, so it's out of scope.
- **Measured once**, locally on 12 cores, 8 teams × 15 rounds, pre-draft: 500 iterations
  0.19s, 20,000 iterations 5.97s. Larger leagues were not measured.
- **Guess, not measured:** production runs on Railway Hobby with compute capped at about $10
  (`DEPLOY.md:35-38`). Its core count and how it behaves under a burst are unknown. A
  handful of 20,000-iteration calls could plausibly pin it and slow every other request.
- **What the UI sends:** at most **2,000**. The `runs` select offers 500/1000/2000
  (`web/src/pages/DraftView.tsx:690`). Default and resim are both 500 (`DraftView.tsx:45,50`;
  `LiveDraftView.tsx:32`).

## Fix

1. **Lower the ceiling** in `SimulationRequest` from 20,000 to 5,000, 2.5× the UI's
   maximum. Keep clamping rather than rejecting, which is the current behaviour. Put the
   value in config beside the other engine settings, labelled as a hand-set number.
2. **Shared limit:** a `Semaphore` in `SimulationController`, sized
   `max(1, availableProcessors / 4)`. That's a guess, and should be labelled as one.
   Since each run already uses every core, the right size is small. Call `tryAcquire()`
   (no waiting); on failure return **429** with `Retry-After: 2` and a JSON `error` body.
   `streamSimulation` (`web/src/api.ts:1452-1454`) already reads `body.error`.
   - Avoid unbounded queueing: virtual threads make a queued request cheap to start and
     expensive to finish.
   - For `/stream`, acquire **before** creating the emitter, so a 429 is a real HTTP status
     and not an `error` event. Release in a `finally` inside the virtual thread.
3. **Abandoned runs hold permits.** A client disconnect doesn't stop the run
   (`SimulationController.java:84`: "the run will finish and be discarded").
   `LiveDraftView.tsx:131` aborts and restarts resims. So stop the run when the stream
   dies: when `emitter.send` fails, set a cancelled flag that the progress callback
   checks, then cancel the futures. Without that, step 4 would reject the user's own
   replacement resim.
4. **Optional, only after step 3 works:** one run in flight per `X-Sleeper-User`. A new
   request from the same identity cancels the old run rather than getting a 429.
5. Add a way to map the new exception to 429, next to `api/ErrorHandler.java`'s existing
   handlers.

## Not in scope

- Rate limiting by IP, or anything else at the edge or in Railway.
- Spec 009's on-visit refresh, which `SingleFlight` already caps.
- Mock drafts (`mock/MockDraftService`), which don't go through `MonteCarloRunner`.

## Acceptance criteria

- [ ] Against a real `bootRun`, time the UI's normal sim (500 runs, and 2,000) before and
      after the change. Report both numbers; wall-clock should be unchanged.
- [ ] Fire more than the permit count of concurrent `/stream` requests locally. The extras
      get 429 with `Retry-After`, and none hang.
- [ ] `iterations: 20000` is clamped to the new ceiling. The `started` event reports the
      clamped value.
- [ ] In the browser, draft-room resim still works. That includes fast repeated picks in
      live mode, which exercise abort-and-restart, with no 429s shown to a single user.
- [ ] After a mid-run tab close, the server log shows the run stopped early and the permit
      came back.
