# Handoff: features Ball Knowers doesn't have yet (2026-10-21)

**Status: idea list, nothing built.** Written at the end of spec 042, while the FantasyPros comparison was
fresh. It doesn't replace [competitor-gap-roadmap.md](competitor-gap-roadmap.md); where an item is already on
that roadmap, it says so. Like every planning doc here, the sizes are **guesses**. Check the code before
trusting one (AGENTS.md, "a roadmap's stated scope is not a verified spec").

**Evidence level.** FantasyPros was looked at **signed out** on 2026-10-20. Anything behind its paywall
(Start/Sit Assistant, Waiver Assistant, Trade Analyzer, League Analyzer, Auto-Pilot) is known by **name
only**. "Have it" below means the data is already stored or fetched, checked in code during 042, not just
assumed.

## 1. Left over from spec 042 (small, start here)

| # | Item | Why | Size |
|---|---|---|---|
| 1.1 | Player page tabs for a player with **no games this season** | Today he gets the old "hasn't played" page, so the Schedule tab (his upcoming games) doesn't render. That's every NBA player on opening week. `verification.md` gap 1. | S |
| 1.2 | Show the injury-status as-of line whenever **any** tag shows (bench options too) | It's missing on the NFL page when only bench options carry tags. `verification.md` gap 2. | S |
| 1.3 | **Second injury refresh before tip-off** | Status refreshes once a day, at 11:00 UTC. A 6 PM "Out" isn't seen until the next morning. `POST /api/refresh/players` exists, but `DailyRefreshService.players` skips if players already ran that UTC day, so a second cron does nothing without changing that gate. It adds a ~5 MB Sleeper pull per sport per run. **Your call.** | S |
| 1.4 | Check the layout at 375 px | SC-003 wasn't checked (the pane was hidden). | S |
| 1.5 | Lineup check reads a **stale league week** | It uses stored `settings_json.leg`, which lags Sleeper until a refresh (West Coast read 4, live was 6). The next-matchup block has the same issue. It could read `leg` from the live league call it's already near. | S |

## 2. Written as spec 042 stories, deferred by your scope call

| # | Item | Notes | Size |
|---|---|---|---|
| 2.1 | **Compare two players** (FantasyPros' "Who Should I Start?") | Spec 042 US4. Side by side, best value per row marked, **no verdict**. Projections stay out until spec 036 measures accuracy (FR-012). | M |
| 2.2 | **All my teams on one screen** (Multi-League Assistant) | Spec 042 US5, roadmap 4.2. One row per league: record, standing, this week's score, lineup-check flag count. Measure the client fan-out first. | S–M |

## 3. Advice tools: projection-bound, roadmap Phase 3, wait on spec 036

These are FantasyPros' paid core. Each one issues a verdict, so each needs a *measured* projection accuracy
first, or it's confident-looking guesswork (AGENTS.md).

| # | Item | Notes |
|---|---|---|
| 3.1 | Start/sit with a verdict | Roadmap 3.3. Wording should come with its uncertainty ("RotoWire projects +4, typical miss ±9"). |
| 3.2 | Waiver assistant | Roadmap 3.4. Rank free agents by **lineup gain** for *your* roster, not raw points. Streaming (spec 019) is the realized-data half that already exists. |
| 3.3 | Trade analyzer → trade finder | Roadmap 3.4 / 3.5. The finder is last: it's the most likely to produce confident nonsense. Trade verdicts (spec 033) cover the realized half. |
| 3.4 | Rest-of-season rankings with a spread | FantasyPros shows Best / Worst / Avg / Std.Dev across experts. We have no experts, but the Monte Carlo engine could give an honest spread from its own distribution. Don't invent a consensus. |

## 4. New ideas, buildable on data we already have

| # | Item | Data | Both sports? | Size |
|---|---|---|---|---|
| 4.1 | **Defense vs position / matchup ratings** (FantasyPros "Defense vs. Position", "Matchup Ratings") | `player_game` has the opponent on every row, plus league scoring. Points allowed per position, by team, **under this league's scoring**, which FantasyPros can't do. Their own NBA matchup page 404'd on 10-20. | Yes | M |
| 4.2 | **Injury / status alerts for your starters** | Pairs with the lineup check. Needs a notification channel the app doesn't have (email or web push), so it's an infra decision first. | Yes | M–L |
| 4.3 | **"Rostered in N of your leagues"** on player pages | Rosters for every league you're in are stored (V28). FantasyPros shows a site-wide %. Ours would be personal, which is more honest. | Yes | S |
| 4.4 | **Printable / exportable draft cheat sheet** (Draft Wizard "Cheat Sheet Creator") | The board, tiers and availability curve exist. Add a print stylesheet or CSV. Worth most just before a draft. | Yes | S |
| 4.5 | **Your-players filter on Trends risers/fallers** | Trends splits free agents vs rostered, but has no "mine" filter. That was spec 042 FR-003's narrowed scope. | NBA | S |
| 4.6 | **Weekly lineup audit** ("you left X points on your bench") | `roster_week_points` stores starters and `players_points` per scored week. Bench points (Roster Management) has the season total; this would be the per-week, per-decision view. | Yes | S–M |
| 4.7 | **Per-category view for points leagues** | The player page breakdown already has points by stat category. A league-wide "who gets their points from where" view (rebounds-heavy vs scoring-heavy) helps trades. | NBA | S |

## 5. Needs a source or capability we don't have (listed so nobody re-discovers it)

| Item | Why not now |
|---|---|
| Player news + "fantasy impact" notes | Sleeper has no news feed. It needs a licensed or scraped source, which is a content business. Same conclusion as the 10-05 research. |
| Auto-Pilot (set my lineup for me) | Sleeper's public API is **read-only**, so there's no way to write a lineup. |
| Expert consensus rankings / "ECR vs ADP" | No experts. A fake consensus would look more certain than the data is. |
| Feed polls and reactions | Needs multi-user social features; see the "league suite" idea in memory. Polls are the cheapest piece. |
| Category-league player rater (z-scores) | No ingested league is a category league today. Park it until one is. |
| DFS, props, betting | Out of scope by product decision. |
| "Coach" AI assistant | Spec 020 (AI recap) is built and flagged off, with no live model run yet. A Q&A assistant is its "historian" follow-on. Spend decision first. |

## Suggested order

1. Group 1, the small 042 follow-ups (half a session).
2. 4.1 Defense vs position: the strongest new idea, both sports, realized data, and something FantasyPros can't personalise.
3. 2.2 then 2.1.
4. 4.3 / 4.4 / 4.6 as filler.
5. Group 3 only after spec 036 reports a projection accuracy number.
