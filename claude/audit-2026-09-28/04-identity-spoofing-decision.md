# 04 — Commissioner gate falls to one public Sleeper lookup

**Severity: Medium. Needs a decision, not a build. Verified** locally (the probe conduct entry,
id 35, was deleted and confirmed gone). Nothing was written to production.

## What's there now

Identity is whatever `X-Sleeper-User` says. Three write paths trust it:

- **Commissioner-only:** `POST`/`DELETE /leagues/{id}/conduct-list`
  (`backend/.../api/SuperlativesController.java:83`, `:113`) and
  `POST /leagues/{id}/power/commissioner` (`api/LeagueHistoryController.java:871`). Both go
  through `LeagueMembership.canCommission` (`store/LeagueMembership.java:172`), which checks the
  header against `league_member.is_commissioner`.
- **Ballots:** `POST /leagues/{id}/ballot` (`api/LeagueHistoryController.java:704`) needs a
  non-blank header (`:708`) that maps to a league member (`:718`).

DEPLOY.md ("The security posture, stated plainly", line 178) already says this is "scoping,
not security". **The new point:** the commissioner flag is ingested from Sleeper's public
`GET /v1/league/{id}/users`, where the commissioner is the row with `is_owner: true`
(`ingest/LeagueIngestService.java:161`). Anyone can call that endpoint without signing in.
So one unauthenticated request tells anyone which header value passes the commissioner gate.
The gate stops honest mistakes. It does not stop anyone who wants in. The same goes for
ballots: every member's id is in that same response.

**Sleeper has no OAuth.** There's no "Sign in with Sleeper" to delegate to, so any real
proof of identity has to be something this app issues itself.

## Options

| | Option | Cost | What it protects |
|---|---|---|---|
| A | **Accept it.** Say so plainly in the UI next to commissioner controls and ballots, and in DEPLOY.md. | ~1 hour, copy only | Nothing. Makes the tradeoff honest and visible. |
| B | **Commissioner-issued invite codes.** The commissioner (admin-bootstrapped) mints one code per member. Redeeming a code sets an httpOnly, `Secure`, `SameSite` session cookie bound to that Sleeper id. Writes read identity from the session, not the header. | Medium–large: V-next migration (sessions, codes), a filter, a redeem page, CORS `allowCredentials` on a split-origin deploy, SSE `?user=` rework | Commissioner actions **and** ballots. |
| C | **Emailed magic link → the same session.** | Large: B's session work plus an email provider, deliverability, and storing email addresses. Sleeper doesn't expose emails, so users would type them in, unverified against Sleeper. | Same as B. Adds PII the app doesn't hold today. |
| D | **Admin secret for commissioner actions only.** Reuse plan 01's server-side `ADMIN_TOKEN` (`Authorization: Bearer`, constant-time compare from `config/ApiSecurityProperties.java`). `canCommission` requires it. The commissioner UI asks for the token once and keeps it for the session only, never in a `VITE_*` variable. | Small once plan 01 lands | Commissioner actions. **Not ballots.** Only works while the operator is the commissioner or can hand them the token. |

## Recommendation

**D now**, because it rides on plan 01's token and closes the one gate whose name claims a
restriction. **Then B, if ballots start to matter** (a public tally people argue about, or
anything more than a friends' league). Skip C: it adds email PII for no gain over B. Whatever
is picked, do A's copy for everything left uncovered (under D, that's ballots).

## Acceptance criteria (generic, for whichever option)

- [ ] Against a real `bootRun`: the probe that worked in this audit (a header copied from
      Sleeper's `/league/{id}/users` `is_owner` row, posting a conduct entry) now fails with
      401/403, and the real path still succeeds.
- [ ] If ballots are covered: a spoofed member header can't submit or overwrite a ballot.
- [ ] Anything left uncovered is named in DEPLOY.md's security-posture section and next to
      that control in the UI. "Verified" and "assumed" are kept separate there.
- [ ] No secret in the browser bundle (`grep` the built `web/dist` for it).
- [ ] CORS preflight from `http://localhost:5173` still passes, and live mode's SSE still
      connects.
- [ ] Backend suite: **0 skipped**.

## Decided 2026-09-29 (Allan)

**Option D.** Commissioner-only actions require the server-side admin secret from plan 01. That covers the conduct list, the commissioner ranking, and the "Recompute week N (commissioner)" route (`POST /power/compute`), which plan 10 found only checks membership. Ballots stay on the honour system, and the page should say so.
