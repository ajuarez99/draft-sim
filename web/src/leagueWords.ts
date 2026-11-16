// Spec 073 FR-004 / SC-001: the words that must not appear anywhere a Time Machine-site visitor can read,
// except the FR-019 "your leagues moved" notice and the redirect fallback line. Used by the wording tests.
export const LEAGUE_WORDS = /\b(leagues?|mock(?:s| drafts?)?|managers?)\b/i
