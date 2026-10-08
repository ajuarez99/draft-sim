# Ball Knowers Constitution

<!--
  Source of truth: AGENTS.md, section "Hard rules". The six principles below are
  quoted from it verbatim (spec 021, T012). Change both together, or this file
  becomes a second implementation of one rule, which is the bug class this repo
  keeps relearning. /speckit-analyze treats everything here as non-negotiable.
-->

## Core Principles

The six hard rules from `AGENTS.md`, verbatim:

- **Schema migrations are append-only.** `backend/src/main/resources/db/migration/`
  — check the highest `V<n>__*.sql` already there and do not edit it once applied;
  any change is the next `V<n+1>`.
- **`web/src/api.ts` types are hand-maintained and must mirror the Java records
  field-for-field**, in the same change that touches the backend record. TypeScript
  gives no warning about an extra field a JSON response now carries that the type
  doesn't know about — a stale type fails silently, not loudly, and has shipped a
  real bug before (a configured value rendered as if it were absent, one layer
  above where a similar bug had already been fixed once).
- **Never retune a hand-set constant (`config/weights.yml`, or similar) to make a
  freshly measured number match an earlier session's guess.** If a guess was wrong,
  say so and name it wrong — don't quietly adjust the model to agree with it. The
  same discipline runs the other way: don't report a suspected failure as real
  without running it first. Either direction, the fix is the same — run it, report
  the actual number, and be explicit about which one you did.
- **A `Map.of(...)` in a JSON response path throws on any null value.** Check for
  legitimately-nullable fields (a free agent's team, a nullable tendency) before
  using it; build the map mutably instead if one might be null.
- **Ask before committing.** Multiple docs in this repo say so explicitly, and
  concurrent sessions on this tree are a real, observed occurrence — diff what's
  staged before assuming it's only your own changes.
- **A roadmap or planning doc's stated scope/difficulty is not a verified spec.**
  `claude/next-features-roadmap.md` called one feature "the largest lift" of four —
  reading the actual engine code before building it found the mechanism it needed
  already existed, fully tested, just unexposed. Check the code before accepting a
  planning doc's framing of how hard something is.

## Governance

`AGENTS.md` governs. This file mirrors its "Hard rules" so that speckit commands
enforce them; it adds no rule of its own. If the two ever disagree, `AGENTS.md` wins
and this file is the one to fix.

**Version**: 1.0.0 | **Ratified**: 2026-10-07 | **Last Amended**: 2026-10-07
