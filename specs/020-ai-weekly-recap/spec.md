# Feature Specification: AI weekly recap (premium, behind a flag)

**Feature Branch**: `020-ai-weekly-recap`

**Created**: 2026-10-06

**Status**: Draft, amended after adversarial review 2026-10-06 ([plan-review.md](plan-review.md);
dispositions in [plan.md](plan.md#amended-after-review-2026-10-06)). Nothing built.

**Input**: Allan: "/speckit-plan for phase 5". That's roadmap Phase 5
([claude/competitor-gap-roadmap.md](../../claude/competitor-gap-roadmap.md)), "AI weekly recap,
then historian", from [claude/ai-recap-and-historian.md](../../claude/ai-recap-and-historian.md).

Decisions Allan made on 2026-10-06, before planning:

| Question | Answer |
|---|---|
| Model | **Claude Haiku 4.5** (`claude-haiku-4-5`) |
| Scope | **Recap only.** The historian (tool-use Q&A) is a later spec. |
| API key | **None yet.** Build against a fake client. The live grounding run stays owed until a key exists. |
| Gating | "Feature flag anything behind the Claude dependency and have it as a premium feature for later." |

The roadmap's spend decision (Phase 5 "needs a spend decision") is taken as made by the model
choice. Measured input sizes put the per-league-week cost around a cent on Haiku (research R2). That's
an estimate, not a measurement.

## Background

Measured 2026-10-06 against the local DB and a local `bootRun` on `origin/main` @ `36f77ca`:

- **The recap's whole input already exists.** `GET /api/leagues/{id}/weekly-report/{week}` returns
  matchups, top performers (NFL) or best nights and best weeks (NBA), awards, and awards omitted with
  reasons. (Foot) Ball Knowers 2026 week 3 is **5,176 bytes** of JSON. Ball Knowers NBA 2025 week 10
  is **4,054 bytes**.
- **The design doc's "~20K input tokens" is about 10× too high.** 5 KB of JSON is roughly 1.5–2K
  tokens (bytes ÷ 3 to ÷ 4, an estimate until `count_tokens` runs). The cost estimate is redone
  in research R2.
- **"Final" has two meanings** (`refresh/WeekFinality.java`). `isFinal` is "counted as decided".
  `mayStopRefetching` is "frozen", which comes a week later in NFL so stat corrections still land. A
  recap written at `isFinal` can therefore cite a number that later changes. Research R4 handles
  that.
- **The weekly report is viewer-dependent.** `Side.isMe` comes from `X-Sleeper-User`. A recap is
  shared by the whole league, so its input must be built with no viewer.
- **No LLM dependency, key, or flag exists today.** `draftsim.admin.token` (fail closed when blank)
  is the existing gate pattern to copy.

## User Scenarios & Testing *(mandatory)*

### User Story 1: Read a recap of a finished week (P1)

A member of an entitled league opens the Weekly Report for a final week. A recap card at the top
gives a headline and 3–5 short sections in plain English. Every number in it is one the report
below already shows. The card says who wrote it (AI, the model's name) and that its numbers were
checked against the report.

**Acceptance scenarios**
1. **Given** recap is on globally, the league is entitled, and week 3 is final, **when** a member
   opens week 3, **then** a recap appears. The first open starts generation in the background,
   and the card shows a "writing" state and polls (amended, F9: no blocking request). Later opens
   read the stored copy.
2. **Given** a recap was generated, **when** a refresh lands a stat correction that changes week 3's
   report, **then** the next open regenerates it. Every later viewer sees "Revised after a scoring
   correction", not just the one whose visit triggered it (F11). A team rename regenerates too,
   and is labelled as a name change, not a scoring correction (F7).
3. **Given** week 4 isn't final, **when** a member opens week 4, **then** no recap is generated and
   the card says the recap is written once the week is final.

### User Story 2: The recap never states a number the data doesn't (P1)

**Acceptance scenarios**
1. **Given** a generated recap whose body contains a number that isn't in the input fields its
   section cites, **then** the system retries once. If the retry also fails, it stores a failure
   with reason `UNGROUNDED` and shows "Recap unavailable this week" with that reason, never the text.
2. **Given** the model refuses (`stop_reason: refusal`) or is cut off (`max_tokens`), **then** a
   failure is stored with that reason and the page shows it rather than a blank.
3. *(Amended, F1, F11.)* **Given** a transient failure (API error, upstream rate limit), **then**
   the week is retried on a later open after 15 minutes (ARBITRARY). It isn't marked unavailable
   for good. **Given** a regeneration fails, **then** the previous good recap stays visible,
   marked as older, with the failure reason beside it. An operator can clear any failure.

### User Story 3: Off unless switched on, and per league (P1)

**Acceptance scenarios**
1. **Given** `RECAP_ENABLED` is unset or false, or `ANTHROPIC_API_KEY` is blank, **then** no
   Anthropic call is ever made, the recap endpoint answers `FEATURE_OFF`, and the page renders
   exactly as it does today.
2. **Given** recap is on but the league isn't entitled, **then** the endpoint answers
   `NOT_ENTITLED`, no call is made, and the page shows nothing. There's no paywall UI in v1.
3. **Given** an operator with `X-Admin-Token`, **when** they grant `RECAP` to a league, **then**
   that league becomes entitled. Revoking it hides stored recaps without deleting them.
4. *(Amended, F5.)* Entitlement belongs to the **season the page actually shows**: the newest
   played season, which is what the Weekly Report resolves to. A grant on a season that hasn't
   been played yet (NBA 2026 before 10-20) does nothing until it has scores. The card names the
   season it describes.

### Edge cases

- **Two members open the same week at once:** one generation runs (single flight), and both get its
  result.
- **A spend cap** *(amended, N7)*:
  - at most 10 Anthropic **calls** per league per UTC day, and 50 across all leagues (both
    ARBITRARY, labelled so); a grounding retry is 2 calls;
  - past a cap, the endpoint returns the last good recap marked stale, or `RATE_LIMITED`;
  - stepping through weeks doesn't spend it, because the card asks only after a week has been on
    screen for 1 s (F9).
- **Mid-refresh** *(amended, F7)*: no generation while the league's refresh is running, so a
  half-written week is never hashed.
- **Crude team names** ("Master Bates" is a real one in the NFL league) may trip a safety
  classifier. That's the refusal path, which is shown, not hidden.
- **NBA weeks** credit one game per starter but report every game played (`basis:
  ALL_GAMES_PLAYED`). The recap must not say a week total "counted". The basis goes into the input,
  and the prompt names it.
- **A week with omitted awards or unavailable sections:** the reasons go into the input, and the
  recap may mention them but must not fill them in.

## Requirements *(mandatory)*

- **FR-001** The input is the weekly report for a final week, built with no viewer, reduced to team
  names, player names, positions, opponents, points, records, awards and reasons. It carries no
  Sleeper user ids, avatars, roster ids, `isMe`, or a player's current pro team. *Amended (F8):* a
  team without its own name is shown, and sent, under its owner's Sleeper display name, exactly as
  on the page. The first draft's "no usernames" was false for 21 real rosters.
- **FR-002** Derived numbers the recap is allowed to say (matchup margin, week high and low, each
  team's rank in weekly scoring) are computed in Java and put in the input. The model isn't asked to
  do arithmetic.
- **FR-003** One Messages API call per generation, with a structured output schema
  `{headline, sections[{title, body, cites[]}]}`.
- **FR-004** A server-side grounding check runs on every generation before anything is stored as
  READY (research R5). The tests carry the same check.
- **FR-005** Stored per (league season, week): input hash, input JSON, output, model id, token usage,
  stop reason, status, failure reason.
- **FR-006** Regenerate when the cache key differs from the stored row's, within the caps. *Amended
  (F2):* the key covers the input, the model id and the prompt version, so a model or prompt
  change also regenerates.
- **FR-007** A global kill switch (`draftsim.recap.enabled`, default false) and a per-league
  entitlement (`league_feature`, feature `RECAP`). Both must hold, and a blank key fails closed.
- **FR-008** The API key is backend-only. It never appears in any response, log line or the frontend
  bundle.
- **FR-009** The card names the model and says the text is AI-written and its numbers were checked
  against the report.
- **FR-010** The `web/src/api.ts` types mirror the Java response records field for field, in the
  same change.

## Not building (v1)

- The historian (Q&A with tools). It's its own spec.
- **A roast tone.** v1 is straight only. A roast is the likeliest refusal and the likeliest place
  for the model to "embellish". It's a follow-up once v1's grounding numbers are in. See the plan's
  example excerpts for what each tone would read like.
- Billing or a paywall UI. "Premium" means an admin-granted flag per league for now.
- Posting recaps anywhere (Sleeper chat, email).
- Prompt caching. At ~2–3K tokens the prefix is under Haiku 4.5's 4,096-token cache minimum, so it
  wouldn't cache anyway (research R3).

## Success criteria

- **SC-001** *(Amended, N6: the first wording, "zero unmatched numbers in READY recaps", was true
  by construction.)* Grounding has two parts:
  - the R5 table test passes row for row;
  - across 5 real final weeks (3 NFL 2026, 2 NBA 2025), V3 reports the UNGROUNDED count and the
    **wrong-but-passed count from a human read**. The bar is zero wrong-but-passed. A non-zero
    UNGROUNDED count is reported, not hidden.
- **SC-002** With the flag off, zero outbound calls (asserted with the fake client) and the Weekly
  Report renders byte-identically to main.
- **SC-003** Measured `usage.input_tokens` / `output_tokens` on real weeks replace R2's estimates in
  verification.md.
