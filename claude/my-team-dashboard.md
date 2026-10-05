# My team, across leagues

Status: **mostly shipped already; a small remainder is designed here.**
2026-10-05. Gap #7 in [competitor-gap-research.md](competitor-gap-research.md).

> **Amended 2026-10-05, after re-checking against `origin/main`.** The first
> draft was written against a stale `009` checkout and proposed a personal
> dashboard as if none existed, plus a "correction" to spec 004 saying the
> user-to-roster binding was a small join. Spec 013 US3/US4 had already built
> it: `LeagueHome.tsx` (`/leagues/:id`) "starts with you", and "you" is a
> server-side `isMe` flag on standings rows, weekly sides and analysis sides
> (`LeagueHome.tsx:40-56`). Spec 015 added a "Your leagues" spotlight on the
> home page. So this doc now covers only what's left.

Competitors: FantasyPros "Multi-League Assistant" and "Personalized Team
Dashboard", DraftSharks "Team Dashboard", StatsGuy multi-league browsing.

## What's there now (main @ 114b12d)

- **Per league:** `LeagueHome` composes standings + your record and position,
  your latest matchup, your next opponent, the power-rankings headline, the top
  award and the player spotlight. Each block comes from an existing endpoint,
  and each fails independently (`useBlock`).
- **Identity:** `web/src/user.ts` holds `sleeperUserId`, which `apiFetch` sends
  as `X-Sleeper-User` (`web/src/api.ts:212`). The server resolves `isMe`.
  This is identity, not auth: anyone can type any username. Everything shown
  is already public league data.
- **Across leagues:** the home page lists "Your leagues" and the spec 015
  spotlight, one tab per league.

## What's left

1. **One glance across all your leagues.** On the home page, a row per league
   with your record, position, playoff odds and this week's opponent and score.
   Today you open each league's home to see that. The data is the same blocks
   `LeagueHome` already fetches. Either fetch them per league client-side (N×5
   requests, with N ≤ ~6 today) or add a `GET /api/me/summary` that composes
   them server-side. Measure the client-side version first. It needs no backend
   change and may be fine.
2. **Next opponent for NBA.** `LeagueHome` takes "next opponent" from
   `getLeagueAnalysis`, which is football-only. It's also where the false "no
   nba equivalent" projection message lives
   (`LeagueAnalysisService.java:448-450`, see
   [projection-tools.md](projection-tools.md)). The *opponent* doesn't need
   projections, only next week's `league_matchup` pairing. Split it from the
   projection payload so basketball gets it too.
3. **NBA games this week** for your roster, once
   [nba-schedule-grid-and-streaming.md](nba-schedule-grid-and-streaming.md)
   stores the schedule.

## Not building

- Real auth or private data (`claude/league-suite.md`).
- A cross-league free-agent finder. It goes with the waiver assistant in
  [projection-tools.md](projection-tools.md).
- Notifications/push.

## Acceptance criteria

1. The cross-league row's record and position equal each league's own
   `LeagueHome` values for a user in both an NFL and an NBA league. Assert it in
   a test, from the same responses.
2. NBA league home shows the next opponent, matching Sleeper's matchups endpoint
   for next week.
3. Signed out or not in a league: no "you" rows and no error. That's the
   existing `LeagueHome` contract, so keep it.
4. Live browser check through the real header path (bug class #6).
