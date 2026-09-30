# 01 — Callers with no identity see and write more than signed-in ones

**Severity: High. Verified** on production (reads) and locally (reads and writes).

## What's there now

Each scoping check treats a blank `X-Sleeper-User` as "allowed":

- `backend/.../store/LeagueMembership.java:134`: `canSee()` returns `true` when
  `anonymous(sleeperUserId)` (line 227). `visibleLeague` and `visibleDraft` inherit this, and
  so does every route built on them.
- `backend/.../mock/MockDraftService.java:369`: `mayUse()` returns `true` for a blank header,
  so an anonymous caller can read **and pick in** anyone's mock. Mock ids are a sequence
  (`mock_draft_session_id_seq`), so they're easy to enumerate.
- `backend/.../engine/OwnerSlot.java`, `mayActAsSlot`: "No X-Sleeper-User at all: allowed."
- Production runs with `API_TOKEN` blank (DEPLOY.md, "The security posture, stated plainly").

DEPLOY.md already says the identity header is "scoping, not security", and a *spoofed* header
is covered in plan 04. The problem here is different, and not documented: **you don't even
need to spoof anyone. Leave the header off and you get more than a signed-in user does.**

## Measured

Production, 2026-09-28, GET only:

| Route | No header | `X-Sleeper-User: 999999999999` |
|---|---|---|
| `/api/leagues/1346366555759341568/analysis` | 200 | 404 |
| `/api/leagues/1346366555759341568/history` | 200 | 404 |
| `/api/drafts` | 200, every league's drafts | 200 `[]` |

Local:

| Route | No header | Stranger header |
|---|---|---|
| `GET /api/mocks/653` (owned by `1122386008709910528`) | 200 | 404 |

The same fail-open rule is what makes plans 02 and 03 writable by anyone.

> **Amended 2026-09-29: spec 009 merged (`d90c2f9`) and added refresh routes.** Read against
> the tree, not executed.
>
> **(a) The per-league refresh routes inherit this fail-open, and this fix covers them.**
> `POST` and `GET /api/leagues/{sleeperId}/refresh` gate only on
> `membership.visibleLeague(sleeperId, sleeperUserId)` (`refresh/RefreshController.java:98`
> and `:108`). So today a header-less caller can start a background refresh of any known
> league and poll its status. `POST` is bounded by staleness, single-flight and
> `MAX_CONCURRENT_REFRESHES = 2` (`refresh/RefreshProperties.java:36`, `:49`), so the harm is
> limited: Sleeper traffic on someone else's league, plus that league's refresh state. Once
> `visibleLeague` denies a blank identity, both routes return 404 with no changes of their
> own. Add them to the acceptance curls: with no header, each returns 404.
>
> **(b) 009 already ships a server-side secret. Reuse its pattern, don't build a second one.**
> `POST /api/refresh/daily` takes an `X-Refresh-Secret` header. It is checked against
> `RefreshProperties.secret` (`refresh.secret` ← `REFRESH_SECRET`) with
> `MessageDigest.isEqual` (`RefreshController.java:59-65`). It returns 404 when no secret is
> configured (`RefreshProperties.java:31-33`), and it is exempt from `ApiTokenFilter`
> (`api/ApiTokenFilter.java:43`). That is the shape this plan's `ADMIN_TOKEN` needs:
> - a server-only env var, bound through a `@ConfigurationProperties` record;
> - a constant-time compare (which also exists as `ApiSecurityProperties.matches`,
>   `config/ApiSecurityProperties.java:27-31`);
> - a route that's absent when the secret is blank.
>
> Before you add `ADMIN_TOKEN`, decide one thing and write it down: should the admin token
> and `REFRESH_SECRET` be one secret or two? Two is defensible, because the GitHub Actions
> cron shouldn't hold a secret that can rewrite picks. But then the compare-and-404 logic
> should be one shared helper, not a copy.
>
> **(c) `POST /api/refresh/players` has no secret on purpose. Its once-a-day bound only
> partly holds.** The controller says "its worst case is one fetch a day"
> (`RefreshController.java:75`). It is not in `ApiTokenFilter`'s exempt list, so it sits
> behind `API_TOKEN`, which is blank on production. `DailyRefreshService.players`
> (`refresh/DailyRefreshService.java:76-89`) checks for today's `daily_capture` row, runs the
> ingest, then records the row. Reading that code shows:
> - **After one success, the bound holds.** Every later call that UTC day returns
>   `SKIPPED_ALREADY_TODAY` without fetching (`:79-81`).
> - **Concurrent calls are not bounded.** The check (`:79`) and the record (`:84`) are not
>   atomic, and unlike `LeagueRefreshService` (`:67`) this path has no `SingleFlight`. So N
>   concurrent callers before the first row lands each run a full `playerIngest.ingest`.
> - **Failures are not bounded.** A failed ingest writes no row (`:86-87`, by design, so a
>   re-run can retry). While Sleeper's player endpoint is failing, every call fetches again.
>
> Neither case is a data-integrity problem, because the upsert is idempotent
> (`store/DailyCaptureRepository.java:34-44`). But "one fetch per sport per day" is the
> steady state, not a guarantee. If this plan keeps the route open, either wrap `players()` in
> a `SingleFlight` per sport, or reword the comment to state the real bound. Not measured.

## Fix

Split the two things that header-less access is currently used for:

1. **Browser traffic always carries an identity.** The frontend already sends
   `X-Sleeper-User` once someone is signed in, and the sign-in gate blocks everything before
   that. So for the browser, "anonymous" can safely mean "sees nothing league-scoped".
2. **Operator escape hatches** (DEPLOY.md's curl ingest, the draft-night manual pick) move
   behind a server-side **admin token**: a new env var, e.g. `ADMIN_TOKEN`, sent as
   `Authorization: Bearer`. It must **never be a `VITE_*` variable**, so it never reaches a
   browser bundle. `ApiSecurityProperties` already has a constant-time compare to reuse.

Concretely:

- `LeagueMembership.canSee`, `visibleLeague`, `visibleDraft` and `canSeeManager`: a blank
  identity returns **false**, unless the request carries a valid admin token. Pass the "is
  admin" fact in explicitly, e.g. a request attribute set by a filter, rather than reading
  headers inside the repository.
- `MockDraftService.mayUse`: blank identity → false. The one exception is an **unowned**
  legacy session (`owner_sleeper_user_id is null`); decide explicitly whether those stay
  open, and write the decision down.
- `/api/ingest/**`: require the admin token. The frontend's "Add a league" and "Refresh"
  flows call ingest today, so check each caller in `web/src` (`grep -rn "/api/ingest" web/src`)
  first. Either keep a **member-scoped** refresh (the caller must be a member of the league
  being refreshed), or keep one "add a league by id" route that's rate-limited per identity.
  Don't break onboarding: `claude/user-identity-and-onboarding.md` describes that flow.
- `LeagueAnalysis.tsx:1067`: the "Load this league" button shows for any unknown id,
  including `999999`. Show it only when the Sleeper lookup confirms the league exists and
  the signed-in user is in it.

## Not in scope

- Real authentication. That's plan 04's decision.
- `/api/health` stays open, and so do `/api/sleeper/user/*` (needed before sign-in) and
  OPTIONS preflights.

## Watch for

- **Integration tests assume header-less access works.** Expect `LeagueMembershipIT` and the
  controller tests to change. That's intended, so update each test's comment to say why
  rather than just flipping the assertion.
- **The live SSE stream takes identity as `?user=`** (`useLiveDraft.ts`), because
  `EventSource` can't set headers. Apply the same blank → deny rule there.
- **`APP_OWNER_SLEEPER_USER_ID`** is documented as the fallback identity for header-less
  calls. After this change it's only a *seat-preselect* default, never an authorization.
  Update DEPLOY.md's env table.
- Update DEPLOY.md's "security posture" section in the same change, and add a lessons.md
  entry: "fail-open on a missing identity is worse than no scoping, because it rewards
  opting out".

## Acceptance criteria

Run each check against a real `bootRun`, not only tests:

- [ ] `curl /api/leagues/{id}/analysis` with no header → 404 (or 401). With a member's
      header → 200.
- [ ] `curl /api/drafts` with no header → `[]`.
- [ ] `curl /api/mocks/{someone else's id}` with no header → 404.
- [ ] `curl -X POST /api/ingest/league/{id}` with no admin token → 401/403. With
      `Authorization: Bearer $ADMIN_TOKEN` → 200.
- [ ] In the browser: sign in, open Home, add a league, Refresh a league, open every page in
      the league rail, start and resume a mock. All still work, with no console errors.
- [ ] A CORS preflight from `http://localhost:5173` still passes.
- [ ] Backend suite: **0 skipped** (see memory "backend suite skips ITs silently").

## Implemented 2026-09-29

Built as amended, with these differences from the text above. Verified = run in the test suite
(`AccessControlMvcIT` and friends); the live `bootRun` and browser checks are the parent's.

- **The admin token is `X-Admin-Token`, not `Authorization: Bearer`.** `Authorization` is already
  the `API_TOKEN` bearer channel, and reusing it would have made turning that gate on collide with
  admin. Blank `ADMIN_TOKEN` means admin is disabled (fail closed). It is a separate secret from
  `REFRESH_SECRET`, sharing one constant-time helper (`config/SecretCompare`).
- **The plan said five leagues routes were scoped; five were not.** `roster-management`,
  `transactions`, `expected-wins`, `forecast` and `weekly-report/{week}` never went through
  `visibleLeague`, so they answered any caller, signed in or not. They are scoped now.
- **Ingest callers.** `/api/ingest/**` needs the token; the browser's add-a-league, setup stages
  and "Load past seasons" go to new membership-checked `/api/setup/**` routes
  (`MemberSetupController`). `POST /api/refresh/players` now needs an identity.
- **The "Load this league" button at `LeagueAnalysis.tsx:1067` no longer exists in this tree**
  (wave 1's not-found work replaced it), so that bullet had nothing to change.
- **Unowned legacy mock sessions stay usable by any signed-in identity** (documented in
  `MockDraftService.mayUse`); creating a mock now needs an identity so none can be minted.
- **Test infrastructure:** the test JVM sets a known admin token and a small Hikari pool
  (see claude/lessons.md #21).
