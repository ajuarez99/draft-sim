# Verification: AI weekly recap (spec 020)

**Status: verified with no key. Not verified with a live model.** No Anthropic API call has ever
been made by this code. V2–V6 (T036) wait for Allan's key.

Run 2026-10-06 on branch `020-ai-weekly-recap` (worktree, uncommitted, based on `origin/main` @
`36f77ca`).

## Suites (T032)

| Suite | Result | How |
|---|---|---|
| Backend `./gradlew test` | **1,316 tests, 0 failed, 0 skipped** | measured by the Phase 5–6 build agent; Postgres 5433 up, so the ITs ran |
| Web `npx tsc -b`, `npm run build`, `npx vitest run` | tsc clean; build OK; **1,070 tests / 85 files passing** | measured by the same agent |

**Re-run after the code review's fixes, by the parent session (2026-10-06):**
- Backend: **1,338 tests, 0 failed, 0 errors, 0 skipped**, counted from the JUnit XML.
- Web: `tsc -b` clean, build OK, vitest **1,073 / 85 files** passing.
- The system prompt gained a title and headline rule to match the R1 fix. That changes
  `prompt_version`, which is harmless because no recaps are stored.

V1 below ran **before** the fixes. The fixes touch the grounding check, revision labels, caps, the
card's stale copy and a new admin `reroll` route. They don't touch the gates V1 exercised, but V1
wasn't re-run live after them.

## V1: no key, live (T033), measured

**Setup:**
- Backend: `bootRun` from **this worktree** on port 8093. The startup classpath was
  `worktrees\020-ai-weekly-recap\backend\build\classes`, checked because a preview from a worktree
  serves main's code (memory).
- Port 8086 was already in use by another session's spec 019 server, so it was left alone.
- Frontend: `vite` from this worktree on 5190, proxied to 8093.
- Caller: (Foot) Ball Knowers NFL 2026 (`1346366555759341568`), a member identity.

**Flag off, no key**
- Startup log: `recap: disabled (flag off)`.

| Check | Expected | Got |
|---|---|---|
| member `GET /recap/3` | `FEATURE_OFF`, all keys present | `{"state":"FEATURE_OFF","season":null,"week":3,"model":null,"generatedAt":null,"revision":null,"revisionReason":null,"stale":false,"headline":null,"sections":null,"failureReason":null}` ✅ |
| stranger (`X-Sleeper-User: 999`) | 404 | 404 ✅ |
| no identity | 404 | 404 ✅ |
| week 0 | 400 | 400 ✅ |
| `POST …/features/RECAP` without a token | 403 `admin_token_required` | 403 `{"code":"admin_token_required",…}` ✅ |
| `POST …/recap/3/preview` without a token | 403 | 403 ✅ |
| grant with a token | 204, row on league 4 | 204, `league_feature` = `4\|RECAP` ✅ |
| member GET after the grant | still `FEATURE_OFF` (the global gate comes first) | `FEATURE_OFF` ✅ |
| preview with a token, flag off | 409 `recap_disabled` | 409 `{"error":"recap_disabled"}` ✅ |
| revoke | 204; feature, recap and attempt row counts all 0 | 204; `0\|0\|0` ✅ |

**`RECAP_ENABLED=true`, `ANTHROPIC_API_KEY=""`**
- Startup log: `recap: disabled (key blank)` ✅.
- Granted league, member `GET /recap/3` → `FEATURE_OFF` ✅. A blank key fails closed (F12).

**Browser (SC-002).** Weekly Report, week 3, flag off:
- The page asked `/api/leagues/…/recap/3` once and rendered **no recap markup** (0 elements
  matching `[class*="recap"]` after 2.5 s).
- Matchups and awards rendered as before.
- "Byte-identical to main" is asserted by `WeeklyReport.test.tsx`, not by a live DOM diff.

**Card appearance (stub, not a model).** In the browser tab only, `fetch` was overridden to return a
hand-written READY response: the plan's illustrative Haiku excerpt plus one more section,
`revision 2`, `NUMBERS_CHANGED`. The card renders above the matchups, in the dark card style with a
teal edge. It shows the headline, small-caps section titles, "Revised after a scoring correction"
and "Written by AI (claude-haiku-4-5) · numbers checked against this report". This checks layout
only. **No model wrote that text.**

**Cleanup:** the test grant was revoked, and all three new tables were left empty. Servers on 8093
and 5190 were stopped. The other session's 8086 server was not touched.

## Not verified (owed: T036, needs a key)

- V2: real generation, latency, real token counts (SC-003); the second report build (N9); virtual
  thread contention (N10).
- V3: 5 real weeks, the human read, the injection probe (SC-001's real bar: wrong-but-passed = 0).
- V4: the model bake-off through `preview`, which replaces the plan's hand-written excerpts.
- V5: the revision labels on a live stat correction and rename.
- V6: transient-failure retry against a live error.
- The real HTTP path in `AnthropicRecapClient.call()` (retries, a real 429, a real refusal). Only
  request construction and canned-response interpretation are tested.
