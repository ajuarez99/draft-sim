# Specification Quality Checklist: Best nights, beside best weeks

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-19
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

### Validation pass 1 — issues found and fixed

- **Implementation leak**: FR-006 and the Problem section originally named the upstream per-player
  stats endpoint and its query parameters. Rewritten to "per-game detail" with the dependency recorded
  in Assumptions instead. The measured evidence in the Problem section names stored fields and figures
  because it is the justification for the feature's cost, not a design instruction.
- **Sport branching**: FR-004 originally read "must work for basketball and football". Restated as a
  rule about whether a player can play more than once per scoring period, so it is testable and cannot
  be satisfied by a hardcoded sport name.
- **Untestable success criterion**: an earlier SC read "the two lists feel different". Replaced with
  SC-002, which requires a measurable difference in the top three for at least one week.

### Two decisions put to the user rather than guessed

Both materially change scope or what a reader sees, and neither had a safe default, so they were asked
rather than assumed. Answered 2026-09-19:

1. **Football behaviour** → *"just basketball, football can stay the same."* The pair is basketball-only;
   football's existing Top performers list is untouched. This changed US3 from "render one ranking and
   explain why" to "change nothing," and narrowed the feature's blast radius considerably — the sport
   with the most data and the most users is now out of the change entirely.
2. **Week totals including games the league's scoring did not count** → *show it, labelled clearly.*
   FR-005 requires the page to state that the figure is real-world production, not points that decided a
   matchup.

An earlier draft of this checklist wrongly recorded these as already decided by the user before they
had been asked. Corrected here and in the spec's Assumptions.

### Open for planning, not blocking

- Per-game retrieval cost scales with roster size (~150+ players per league). FR-008 forbids doing it
  during a page load, so the plan must choose a storage and refresh path. Flagged in Assumptions.
- The mechanism by which the league's scoring selects one game per player per week is not understood —
  it is not the first, last or highest. This does not block the feature, which reads games directly,
  but it is worth recording if it ever needs to be reconciled.
