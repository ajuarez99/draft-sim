// Spec 073 FR-004 / SC-001: the words that must not appear anywhere a Time Machine-site visitor can read.
// (The old "your leagues moved" notice is gone; the wording tests say what is still allowed.) Used by the wording tests.
export const LEAGUE_WORDS = /\b(leagues?|mock(?:s| drafts?)?|managers?)\b/i
