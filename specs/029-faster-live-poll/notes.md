# Spec 029: poll Sleeper every 2 s while a draft is drafting

**2026-10-10.** Allan watched a test-league draft live on prod and reported that picks reached the live room slowly.

## Measured (before the change)

- **Sleeper:** `GET /v1/draft/{id}` and `/picks` answered in 70–250 ms (3 samples each, 16:35 UTC).
- **Prod resim:** the logs for test-league draft 10 show 500-iteration resims at 30–1,070 ms, mostly 100–250 ms.
- **The poller** slept a **fixed 10 s** between ticks (`LiveDraftPoller.POLL_INTERVAL`). A pick waited 0–10 s before the server asked Sleeper about it.
- **The browser** renders landed picks as soon as a frame arrives (`LiveDraftView`), then resims after a 1.5 s debounce.

**Not measured:** per-tick server time on prod. Nothing logged it. That's why the new SLOW_TICK warning exists.

**Conclusion (inferred from the above, not timed end to end):** the 10 s sleep was the dominant wait.

## Change

- **Sleep 2 s instead of 10 s** after a successful tick that saw `drafting` (`sleepFor`).
- **Unchanged:**
  - `pre_draft`, `paused`, complete;
  - every failure path, which keeps the 10 s interval × backoff (up to 60 s), so a rate-limited Sleeper is never polled harder;
  - the test-seam constructors, which keep one interval for every status.
- **A tick slower than 1 s** logs WARN with its duration.
- **Sleeper load:** about 60 calls/min per drafting draft.

## Not built

- **Skipping the picks fetch when `last_picked` is unchanged.** It's cheaper, but how `last_picked` behaves on autopicks hasn't been verified. Not worth the risk on draft day.
- **No frontend change.** The 1.5 s resim debounce stays.

## Tests

- **`LiveDraftPollerIntervalTest` (4):** the rule's truth table, including failure-backoff mid-draft.
- **`LiveDraftPollerTest` (+2, loop wiring):**
  - five drafting ticks within 3 s at a 20 ms drafting / 5 s slow interval;
  - pre_draft ticks once in 500 ms.
- **Fail check:** the drafting test **fails** with the loop's status input forced to null, which is the old behaviour. Shown before restoring.
- **Full backend suite:** 1,574 tests, 0 failed, 0 skipped.

## Not verified

There was no live Sleeper draft in `drafting` to watch locally. Draft night (or another test-league draft) is the real check. Look for "slow poll tick" WARNs in the prod log.
