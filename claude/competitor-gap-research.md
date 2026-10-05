# Competitor gap research: what other fantasy sites have that Ball Knowers doesn't

Status: **research + design docs, nothing built.** Written 2026-10-05. Each gap
below has its own design doc in `claude/`; this file is the index and the
evidence behind it.

> **Amended 2026-10-05, same day, after re-checking against `origin/main`.**
> The first pass was written in a checkout still on the merged `009` branch,
> about 20 commits behind main (specs 013, 014, 015 and the commissioner honour
> system were missing). That's the stale-tree trap AGENTS.md warns about. Re-checked
> against main @ `114b12d`:
> - **Draft grades:** main already has steals & reaches vs draft-time ADP and a
>   letter-grade convention. The doc now builds on both; the outcome half is
>   still the gap.
> - **My-team dashboard:** largely shipped by spec 013 (`LeagueHome`, server-side
>   `isMe`). The doc was cut down to the remainder, and the "correction to spec
>   004" below was withdrawn because spec 013 had already acted on it.
> - All file:line references and migration numbers (highest is now V26) updated.

## Sources looked at

ffwrapped, FantasyPros (My Playbook), DraftSharks, StatsGuy Fantasy's Sleeper
League Hub, StatChasers, FantasyCalc, Fantasy Navigator, Dynasty Daddy /
KeepTradeCut, League Tycoon, Hashtag Basketball, Basketball Monster, FanScout,
LeagueLogs, Fantasy Pressbox, The Front Office.

**What "looked at" means:** each site's own public feature description (landing
pages, tool indexes, app-store copy, a GitHub README for Dynasty Daddy, whose
page did not render). Nothing was signed into. FantasyPros, DraftSharks and
Hashtag's premium tiers are paywalled, so the *depth* of those features is
unverified — only that they exist.

## Baseline

`specs/004-ffwrapped-feature-parity/spec.md:22` already ran this comparison
against ffwrapped alone, in June. Since then specs 004–015 shipped Roster
Management, Expected Wins, Season Forecast, the Weekly Report, career profiles,
head-to-head, superlatives, the fan-first redesign (league home, grades, steals &
reaches) and the player spotlight. This research is the wider sweep, and this
list is what's *still* missing as of main @ `114b12d`.

## The gaps and their docs

Ranked by fit (realized data, both sports, size) — not by what competitors
emphasize.

| # | Doc | Gap | Data needed | Both sports? |
|---|---|---|---|---|
| 1 | [draft-grades.md](draft-grades.md) | How each pick actually scored (ADP steals/reaches already shipped) | Have it | Yes |
| 2 | [nba-schedule-grid-and-streaming.md](nba-schedule-grid-and-streaming.md) | Games-per-week grid, streaming FAs | Have the client, not stored | NBA only, by design |
| 3 | [trade-grades-and-trade-tree.md](trade-grades-and-trade-tree.md) | Trade verdicts by realized points; trade tree | Have it (picks excepted) | Yes |
| 4 | [schedule-swap.md](schedule-swap.md) | Your record under every other team's schedule | Have it | Yes |
| 5 | [hall-of-fame-and-rivalries.md](hall-of-fame-and-rivalries.md) | Trophy case, rivalries, nemesis | Have it | Yes |
| 6 | [nba-minutes-trends.md](nba-minutes-trends.md) | Minutes/role trends | Have it (stored, unread) | NBA only, by design |
| 7 | [my-team-dashboard.md](my-team-dashboard.md) | Cross-league summary; NBA next opponent (per-league home shipped) | Have it | Yes |
| 8 | [season-wrapped.md](season-wrapped.md) | Season-end recap per manager | Have it | Yes |
| 9 | [projection-tools.md](projection-tools.md) | Start/sit, waivers, trade analyzer, rate my team, compare | **NBA source exists** — see below | Yes, now |
| 10 | [ai-recap-and-historian.md](ai-recap-and-historian.md) | Written recaps, league Q&A | Have it; needs an LLM integration | Yes |
| 11 | [keeper-calculator.md](keeper-calculator.md) | Keeper value | No league uses keepers | — |
| 12 | [auction-drafts.md](auction-drafts.md) | Auction values, auction mock room | No league runs auctions | — |

## Things the code survey found that change the framing

Recorded here because each one corrects a claim made either in an older doc or
in this session's own first-pass summary.

1. **Sleeper serves NBA projections. Spec 004's premise is wrong.**
   `specs/004-ffwrapped-feature-parity/spec.md:268` says the projection-bound
   tools need "a projection source that does not exist" for basketball. Measured
   2026-10-05: `GET /projections/nba/2026/1?season_type=regular&position[]=PG…`
   returns 200, 961 rows, 945 with a full stat line, `company: "rotowire"`, one
   row **per player per game** (`gp: 1.0`, with `game_id`, `date`, `opponent`)
   — 2–4 rows per player for week 1. The season endpoint
   (`/projections/nba/2026`) returns per-game averages for 529 players. Nobody
   had tried it: `SleeperProjectionClient.POSITIONS`
   (`backend/.../ingest/SleeperProjectionClient.java:31`; still true on main) is football-only. What
   is *not* measured: accuracy. See projection-tools.md.
2. **NBA minutes are already stored.** This session's first summary said minutes
   trends "need a new data source." Wrong: `player_game.stats` carries `sp`
   (seconds played) on every one of 57,941 NBA rows across 2024 and 2025
   (local DB, 2026-10-05). It is read by nothing.
3. ~~**"Which team is yours" is small, not "real work".**~~ *Withdrawn on
   re-check:* true, but spec 013 had already found and built on it
   (`LeagueHome`'s server-side `isMe`). See my-team-dashboard.md.

Also measured, against the local DB (which may lag prod): every ingested league
is `settings.type = 0` (redraft) with snake drafts only. `max_keepers = 1` on
all of them is Sleeper's default, not a keeper league. That is why #11 and #12
are written as "nothing built is a legitimate outcome" docs.

## What is not in this list

DFS tools, betting props, expert-consensus rankings, news feeds, injury
predictors and depth charts. Those are content businesses (FantasyPros has 100+
experts; DraftSharks sells an injury model). Ball Knowers models *your league's
people*, and none of those make it better at that.

## Sources

- https://ffwrapped.com/
- https://www.fantasypros.com/fantasy-football-tools/
- https://www.fantasypros.com/nfl/myplaybook/intro.php
- https://www.draftsharks.com/
- https://statsguyfantasy.com/league-hub
- https://statchasers.com/sleeper-dynasty-league-analyzer/
- https://fantasycalc.com/league/analyzer
- https://www.fantasynavigator.com/username
- https://github.com/G-Sher/dynasty-daddy
- https://leaguetycoon.com/features/league-history-hall-of-fame/
- https://hashtagbasketball.com/
- https://basketballmonster.com/
- https://fanscout.pro/
- https://apps.apple.com/us/app/leaguelogs-fantasy-football-ai/id6761667938
- https://github.com/ryrykeith/fantasy-pressbox
- https://www.tfofantasy.com/
