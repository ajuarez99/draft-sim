import { NEUTRAL_SCORING, howToPlaySteps, type HowToStep } from './historicalCopy'
import * as redraft from './redraftCopy'

// Spec 073: the one source of the Redraft front door's words (renamed in spec 074; the head and the words that
// design.md specifies are read from redraftCopy.ts, so the static home page and this one cannot disagree). TimeMachineSignIn.tsx renders
// from it and landingHtml.ts (run in Node by vite.config.ts) writes the same words into the HTML a
// crawler or link-preview bot receives. Pure data: no React, no browser global, and no league
// wording (leagues, mock drafts, managers) anywhere -- tmLandingCopy.test.ts guards that.

export const title = redraft.homeTitle

export const description = redraft.homeDescription

export const headline = redraft.homeH1

export const subheadline = redraft.homeTagline

export const sports = ['NBA', 'NFL'] as const

export const imageAlt = redraft.imageAlt

export const footerLine = redraft.footNote

// SignInForm.usernameFrom accepts a pasted profile link (sleeper.com/user/<name>), so saying so is true.
export const signInNote =
  'Enter your Sleeper username or profile link to keep your games. No password. This site never asks Sleeper for anything private.'

// Spec 052 FR-012: what the site counts, said where people sign in. This site has no leagues, so it
// says pages and actions, not "which league".
// One source with the static home (redraftCopy): the mounted sign-in form must say the random-id sentence too.
export const analyticsLine = redraft.analyticsLine

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
