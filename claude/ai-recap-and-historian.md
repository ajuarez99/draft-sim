# AI recap and league historian

Status: **design, not built.** 2026-10-05. Gap #10 in
[competitor-gap-research.md](competitor-gap-research.md).

> **Amended 2026-10-06 (spec 020):** the **weekly recap** half is built on branch
> `020-ai-weekly-recap` behind a flag, not yet verified with a live model
> ([specs/020-ai-weekly-recap](../specs/020-ai-weekly-recap/)). The historian is deferred to a
> later spec. Three things below were wrong or changed:
> - **Cost:** "~20K input tokens … ≈ $0.11 per league-week" was about 10× high. A real week's
>   report measures 5.2 KB, and the reduced input sent to the model is 3.6 KB (NFL) / 3.0 KB
>   (NBA), roughly 1K tokens (estimated from bytes; `count_tokens` not yet run).
> - **Model:** Allan chose **Claude Haiku 4.5** (≈ $0.007 per league-week, estimated), not the
>   Opus 5.5 default this doc assumed.
> - **Off by default, premium per league:** it's off unless `RECAP_ENABLED` and a key are set, plus
>   a per-league entitlement. Acceptance #1's grounding check also runs in production on every
>   generation, not only as a test.

Competitors: this is the most crowded category in the sweep. LeagueLogs
(Tuesday recaps, Thursday previews, game-day briefings, "AI analyst"), Fantasy
Pressbox (recaps with an "autopsy", awards, trash talk), The Front Office
(GPT newsletters, tone from PG to unhinged), SmackScript, ffwrapped's weekly
recap generator and "Historian".

## Why it's ranked low despite that

Everything they write *about* is computed here already, and computed
carefully. An LLM layer adds voice, not information. It also adds the one thing
this project is built to avoid: confident-sounding statements nobody verified.
So the design question is how to stop the model saying anything the data didn't.

## What's there now

- `WeeklyReportService.Result` (`engine/WeeklyReportService.java:133`):
  matchups, top performers, best nights/weeks (NBA), awards, and
  `awardsOmitted` with reasons. This is the recap's whole input.
- Season-scoped: superlatives, Expected Wins, playoff odds, H2H, record book,
  careers. These are the historian's data.
- **No LLM integration exists**, so no SDK dependency, no key, no cost line.

## Proposed design

### Weekly recap (single call, no tools)

- Input: the week's `WeeklyReportService.Result` serialized as JSON, plus the
  league's standings and the record-book entries broken that week.
- One Messages API call. Output via structured outputs
  (`output_config.format`): `{ headline, sections: [{ title, body,
  cites: [field paths] }] }`. Every section names the input fields it rests on,
  so a test can check that the cited fields exist and the numbers in `body`
  appear in them.
- Generated once when a week becomes final. That's the existing spec 009 week
  finality, the same gate everything else uses. Stored, not regenerated per view.
  It's a new `league_recap` table (V27+ — V26 is the highest on main as of 2026-10-05; append-only).
- Tone: one setting per league ("straight" / "roast"). The roast version may
  mock managers; it may not invent facts. The system prompt says so, and
  acceptance #1 checks it.

### Historian (Q&A, tool use)

- The model gets read-only tools that are thin wrappers over **existing
  services**: `standings(season)`, `headToHead(a, b)`, `recordBook()`,
  `career(manager, sport)`, `superlatives(season)`, `weeklyReport(season, week)`.
  It doesn't get SQL access. Each tool returns what the existing endpoint
  returns, so the historian can't compute a number differently from the page
  that shows it (the two-implementations rule).
- `strict: true` on every tool. `tool_choice: auto` (forced tool choice 400s on
  current models).
- Answers must cite the tool results they used. If the tools can't answer, it
  says so. The prompt rules out "probably" answers about league history.

### API details (from the claude-api skill, cached 2026-09-25)

- Java SDK: `com.anthropic:anthropic-java` (2.34.0 at time of writing).
- Model: `claude-opus-5-5` ($4 / $20 per MTok in/out, cache reads $0.20).
  Thinking can't be disabled. Set `output_config.effort` explicitly; its default
  is `medium`, and `low` is likely enough for the recap. Cheaper options if Allan
  chooses: `claude-sonnet-5-5` ($2 / $10), `claude-haiku-4-5` ($1 / $5).
  The model is Allan's call, not a default to quietly downgrade.
- Refusals: check `stop_reason == "refusal"` before reading content. A roast
  recap is the likeliest place to trip a classifier. Use the server-side
  `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`).
- Prompt caching: system prompt + tool definitions are stable, so put a
  breakpoint after them. The historian's league context (rosters, managers)
  goes next, then the question. Verify `cache_read_input_tokens > 0` on the
  second call.
- Key: `ANTHROPIC_API_KEY` as a Railway variable on the backend service only.
  Never sent to the frontend.

### Cost (estimated, not measured)

Recap: ~20K input tokens of report JSON plus ~1.5K output ≈ 20K×$4/M + 1.5K×$20/M
≈ **$0.11 per league-week** on Opus 5.5. With ~6 leagues × ~20 weeks ≈ $13 per
season. That's a guess from token *estimates*. Measure with `count_tokens` on a
real week's JSON before quoting it anywhere else.

## Not building

- Image or meme generation.
- Posting recaps to Sleeper chat or email. That's outward-facing and needs its
  own decision.
- Free-text tools (web search). The historian only knows this league.

## Acceptance criteria

1. **Grounding test, the main one:** for 5 real final weeks across both sports,
   every number in every recap body appears in the input JSON. Write it as an
   automated check: extract the numbers and look them up. Report the count of
   unmatched numbers; the bar is zero.
2. Historian: 10 questions with known answers from the existing pages ("who has
   the most titles", "A vs B all-time"). Answers match the pages, and each cites
   a tool call.
3. An unanswerable question ("who would have won with Mahomes") gets a
   "can't tell from the data" answer, not a guess.
4. Refusal path exercised (mock `stop_reason: refusal`) and the page shows a
   reason, not a blank.
