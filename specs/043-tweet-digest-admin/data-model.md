# Data model: Morning Tweet Digest (spec 043)

**No migration. No new table. Nothing is written.** Every value is read from tables the daily
refresh already fills.

| Read | From | Note |
|------|------|------|
| Night's games + stat lines | `player_game` (`forDate`, `datesForSeason`) | sport-wide, every player (spec 009) |
| Completeness | `sport_week_stats.fetched_at` via `choosePeriod` | spec 014's rule, reused (R2) |
| Points | `player_game.stats->>'pts_std'` (NBA) / `'pts_ppr'` (NFL) | Sleeper's own numbers (R1) |
| Trending | `sport_trending` + `sport_trending_fetch` | latest snapshot only (R3) |
| Key players | `adp_snapshot` newest `captured_on`, `source='sleeper_search_rank'` | top 15 (R4) |
| Expectations | `player_game` this season / last season for the 15 | mean of Sleeper points |
| Names, positions, teams | `player` | `PlayerRepository.byIds` |
| Next game | `sport_schedule.game_date` | first date after the reference day |
| Current season | `SportLeagueSeasonSource.leagueSeason(sport)` | none known ⇒ `NO_SEASON` |

## In-memory shapes (Java records, mirrored field-for-field in `web/src/api.ts`)

- `AdminDigest(generatedAt, yesterday, sports)`
- `SportDigest(sport, season, pointsBasis, status, date, week, gamesCount, lastSlateDate,
  inProgressDate, nextGameDate, unscoredRows, trending, topPerformers, keyPlayers, positionLeaders)`
- `DigestEntry(playerId, name, position, team, opponent, isAway, points, line)`
- `TrendingCard(status, lookbackHours, fetchedAt, stale, omittedUnknownPlayers, entries)`
  / `TrendingItem(rank, addCount, outcome, entry)`
- `KeyEntry(rank, entry, played, expectation, expectationBasis, verdict)`

Frontend-only: `PostCard(kind, text, weightedLength, shownCount, totalCount)` built by
`tweetText.ts`; edits live in component state only (FR-013, never saved).

## Hand-set constants (labelled arbitrary in code)

`KEY_PLAYER_COUNT = 15`, `KEY_SEASON_MIN_GAMES = 5`, `KEY_LAST_SEASON_MIN_GAMES = 10`,
`TOP_LIMIT = 10`, `NBA_POINTS_KEY = "pts_std"`, `NFL_POINTS_KEY = "pts_ppr"`,
`DIGEST_ZONE = America/New_York`, frontend `CARD_ENTRIES = 5`, `TWEET_LIMIT = 280`.
