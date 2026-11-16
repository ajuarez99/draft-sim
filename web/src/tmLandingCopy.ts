import { NEUTRAL_SCORING, howToPlaySteps, type HowToStep } from './historicalCopy'

// Spec 073: the one source of the Time Machine front door's words. TimeMachineSignIn.tsx renders
// from it and landingHtml.ts (run in Node by vite.config.ts) writes the same words into the HTML a
// crawler or link-preview bot receives. Pure data: no React, no browser global, and no league
// wording (leagues, mock drafts, managers) anywhere -- tmLandingCopy.test.ts guards that.

export const title = 'Ball Knowers Time Machine: Redraft a Real NBA or NFL Season'

export const description =
  'Draft a team from a real NBA or NFL season, then watch that season play out. Free, with just a Sleeper username.'

export const headline = 'Draft a real season. See how it played out.'

export const subheadline =
  'Pick a team from a past NBA or NFL season using only what the players did the year before, then watch the year unfold.'

export const sports = ['NBA', 'NFL'] as const

export const imageAlt = 'Ball Knowers Time Machine: draft a team from a real NBA or NFL season and watch it play out.'

export const footerLine = 'Free. No password.'

export const signInNote =
  'Enter your Sleeper username (or paste your Sleeper profile link) to play. No password -- this app never asks Sleeper for anything private.'

// Spec 052 FR-012: what the site counts, said where people sign in. This site has no leagues, so it
// says pages and actions, not "which league".
export const analyticsLine =
  'We count page visits and a few actions (like signing in) anonymously: which pages, never your username, and no cookies.'

export const howHeading = 'How to play'

export const steps: HowToStep[] = howToPlaySteps(NEUTRAL_SCORING)

/** Every string the front door and its head tags can say, for the banned-words guard. */
export function allCopyStrings(): string[] {
  return [
    title, description, headline, subheadline, imageAlt, footerLine, signInNote, analyticsLine, howHeading,
    ...sports,
    ...steps.flatMap((s) => [s.title, s.body]),
  ]
}
