# Contract: GET /api/leagues/{leagueKey}/availability

Read once per league route by `useLeagueAvailability`. It answers the question "which
pages can this league honestly show?" (FR-008).

```json
{ "leagueKey": "espn-fba-12345-2026", "platform": "ESPN",
  "unavailable": {
    "draftRoom":   { "code": "PLATFORM_NOT_SUPPORTED", "message": "Live and simulated drafts follow Sleeper drafts only." },
    "stats":       { "code": "SCORING_UNTRANSLATED",   "message": "This league's ESPN scoring has rules Ball Knowers can't translate yet: …" },
    "lineups":     { "code": "NO_PLAYER_POINTS",       "message": "…" } },
  "unmatchedPlayers": 2,
  "lastRefresh": { "succeededAt": "…", "failedAt": null, "failure": null },
  "canReplaceCookies": false }
```

- **Sleeper leagues**: `platform: "SLEEPER"`, `unavailable: {}`. This is the FR-013 path,
  and nothing else changes for them.
- **Page keys**: `draftRoom` (board, live, simulate, mock seed), `draftGrades`, `stats`
  (stat leaderboard, calculator, player spotlight, trends), `schedule`, `superlatives`,
  `weeklyReport`, `expectedWins`, `forecast`, `lineups`, `recap`.
  - **Amended after build (2026-10-14):** the full set is `draftRoom, draftGrades, stats, schedule,
    superlatives, weeklyReport, expectedWins, forecast, recap` (fixed for every ESPN league) plus the
    data-driven `analysis` (`MULTI_WEEK_PERIODS`), `powerBaseline` (`SCORING_UNTRANSLATED`), `lineups`
    (`NO_PLAYER_POINTS`) and `playoffOdds` (`PLAYOFF_FORMAT_UNSUPPORTED`). `stats` carries
    `SCORING_UNTRANSLATED` instead of `PLATFORM_NOT_SUPPORTED` while any scoring item is unconfirmed.
  - **The server refuses by the same object.** `LeagueAvailability.ROUTES` maps each key to the
    `/api/leagues/{key}/<segment>` routes it governs (`stats`: stats, players, player-spotlight,
    player-trends; `schedule`: schedule, next-matchup; `superlatives`: superlatives, conduct-list;
    `weeklyReport`: weekly-report; `expectedWins`: expected-wins; `forecast`: forecast; `recap`: recap;
    `analysis`: analysis). `PlatformGuard` answers 422 `{error: <that key's code>, message: <its sentence>}`
    for an ESPN league the caller can see, and the usual 404 for a stranger. Draft pages are guarded under
    `/api/drafts/**`. `lineups`, `playoffOdds` and `powerBaseline` govern no route: the page hides the part, or
    the computation refuses (the preseason baseline writes nothing and says why).
- **ESPN's in-scope pages** never appear in `unavailable` unless a measured data gap
  puts them there: home/standings, history, power, analysis, roster-management,
  transactions.
- **`failure`**: a credential-free CODE from `league_refresh.last_failure` (`ESPN_ACCESS_DENIED`,
  `ESPN_NOT_FOUND`, `ESPN_HTTP_<n>`, `ESPN_SHAPE_CHANGED`, `UNSUPPORTED_SCORING`, `ESPN_REFRESH_ERROR`), never
  message text; the client turns it into a sentence. Shown only while it is the latest word.
- **`canReplaceCookies`** (added after build): true only for the manager who stored this league's cookies.
  The page offers "Replace cookies" from it; the PUT still enforces ownership (403 `NOT_OWNER`).
