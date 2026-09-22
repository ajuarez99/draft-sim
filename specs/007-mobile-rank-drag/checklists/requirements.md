# Specification Quality Checklist: Reordering a ranking on a phone

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-22
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

Resolved during validation (iteration 1 -> 2):

- **Implementation leakage.** The Problem section originally named the mechanism
  (pointer capture, cached bounding rects, `touch-action`). Rewritten to describe
  the observable behaviour: a drag that cannot pass the visible rows, a swipe that
  moves a team instead of scrolling, a drop that lands on the wrong row after the
  list moved, and a miss that says nothing. The underlying causes belong in the
  plan, not here. The one structural fact kept -- the board is a modal capped at
  80% of screen height with an internally scrolling list -- is retained because it
  is the reason the defect exists at phone size and a stakeholder cannot judge the
  scope without it.
- **Unfalsifiable success criteria.** "Feels responsive" and "no accidental drags"
  were replaced with counted trials (SC-003, SC-004) and a bounded task time
  (SC-002).
- **Prescriptive requirements.** An early FR named press-and-hold with a specific
  duration. Split into FR-008 (a deliberate pick-up distinct from swiping) and
  FR-009 (confirm before the team follows the finger), leaving the duration and
  the gesture itself to the plan.
- **Regression guards added.** FR-017, FR-021 and SC-007, SC-008 were added after
  the first pass: the fix is a touch fix, and the failure mode worth guarding is
  paying for it on mouse or keyboard. FR-021 exists specifically because the
  obvious touch fix -- a hold delay -- would be felt as lag if applied to a mouse.

No [NEEDS CLARIFICATION] markers were raised. The three candidates considered and
resolved by reasonable default instead, recorded in Assumptions: target viewport
width (375px, matching the app's existing breakpoint), whether the drag rework
extends to the draft board (no -- the request said rankings), and whether haptics
are required (desirable, not required, since mobile browser support is uneven).
