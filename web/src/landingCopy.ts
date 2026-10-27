import type { Sport } from './api'
import {
  DESTINATION_GROUPS,
  LEAGUE_DESTINATIONS,
  type DestinationGroup,
  type DestinationKey,
} from './destinations'

// Spec 050: the one source of the signed-out landing page's words. SignIn.tsx renders
// from it, and landingHtml.ts (run by vite.config.ts in Node, at build time) writes the
// same words into the HTML a crawler or link-preview bot receives. So this module may
// import types and destinations.ts (pure data) but never React or a browser global.

/** No trailing slash. Absolute because link-preview bots will not resolve a relative og:image. */
export const SITE_ORIGIN = 'https://www.ballknowers.co'

export const title = 'Ball Knowers: Fantasy Football & Basketball for Sleeper Leagues'

export const description =
  'NFL and NBA fantasy tools for Sleeper leagues: power rankings, playoff odds, awards, rivalries, draft grades and mock drafts against your league-mates.'

export const headline = 'Your fantasy league, all season long.'

export const subheadline =
  'Power rankings, playoff odds, awards, rivalries and draft prep for your Sleeper football and basketball leagues.'

export const sports = ['NFL', 'NBA'] as const

// Sleeper is the only place you sign in. ESPN is deliberately a small footnote: only
// some season pages read an ESPN league, and the draft room is Sleeper-only (FR-009).
export const platformsNote =
  'Built for Sleeper leagues. Sign in with your Sleeper username. Some season pages also work for ESPN leagues.'

export const imageAlt =
  'Ball Knowers: fantasy football and basketball tools for your Sleeper league, for the NFL and NBA.'

export const footerLine = 'Free. No password.'

/** `managers` is the one rail entry that is not a league destination (the tendencies page). */
export type LandingDestination = DestinationKey | 'managers'

export interface LandingFeature {
  name: string
  line: string
  destination: LandingDestination
}

export interface LandingGroup {
  key: Exclude<DestinationGroup, 'home'>
  features: LandingFeature[]
}

// Groups follow the rail's own order and names (DESTINATION_GROUPS); the headings are
// read from there rather than retyped.
export const groups: LandingGroup[] = [
  {
    key: 'thisWeek',
    features: [
      { name: 'Power rankings', line: 'Who is actually the best team in your league right now, week by week.', destination: 'power' },
      { name: 'Matchups & awards', line: 'This week’s head-to-heads and weekly awards, from the one-player carry to the self-inflicted wound.', destination: 'weeklyReport' },
      { name: 'Schedule grid', line: 'How many games every NBA team plays in each week of your league’s schedule.', destination: 'schedule' },
      { name: 'Trends', line: 'See whose minutes are climbing or sliding lately.', destination: 'trends' },
      { name: 'Player pages', line: 'Game logs and stat lines for any player on your league’s rosters.', destination: 'players' },
      { name: 'Compare players', line: 'Put two players side by side before you make the trade or the pickup.', destination: 'compare' },
    ],
  },
  {
    key: 'season',
    features: [
      { name: 'Playoff odds', line: 'Your chances of making the playoffs, updated as the weeks get scored.', destination: 'forecast' },
      { name: 'Team strength', line: 'How every roster stacks up on paper, and where yours is thin.', destination: 'analysis' },
      { name: 'Luck', line: 'What your record would be against everyone, every week.', destination: 'expectedWins' },
      { name: 'Bench points', line: 'Points you scored versus the points you could have scored with a perfect lineup.', destination: 'rosterManagement' },
      { name: 'Awards', line: 'Season superlatives for the best, the unluckiest and the worst lineup calls.', destination: 'superlatives' },
      { name: 'Weekly pot', line: 'Track the high-score pot for leagues that play for one.', destination: 'weeklyPot' },
      { name: 'Player stats', line: 'Season stats for every player in your league, with a calculator for your scoring.', destination: 'stats' },
    ],
  },
  {
    key: 'draft',
    features: [
      { name: 'Draft room', line: 'See who is likely gone before your pick, based on each league-mate’s draft history.', destination: 'board' },
      { name: 'Follow live', line: 'Watch a draft as it happens, with a read on what each pick means for you.', destination: 'live' },
      { name: 'Mock drafts', line: 'Practice against seats modelled on your league-mates’ past drafts, not generic bots.', destination: 'mock' },
      { name: 'Draft grades', line: 'See how every manager’s draft held up once the season is underway.', destination: 'board' },
      { name: 'ADP & prices', line: 'Compare average draft position and auction prices across sources.', destination: 'adp' },
      { name: 'Managers', line: 'Each manager’s draft habits: the positions they load up on and, in football, who reaches.', destination: 'managers' },
    ],
  },
  {
    key: 'history',
    features: [
      { name: 'Standings', line: 'Every season of your league on Sleeper, in one place.', destination: 'history' },
      { name: 'Rivalries', line: 'Head-to-head records between every pair of managers, and suggested pairings for a rivalry week.', destination: 'rivalries' },
    ],
  },
]

/** The group's heading, read from the rail's own table. */
export function headingOf(key: LandingGroup['key']): string {
  return DESTINATION_GROUPS.find((g) => g.key === key)?.heading ?? ''
}

/**
 * Which sports a feature applies to, read from the destination's own `sports` so a
 * hand-typed tag can never drift from the rail (FR-009a). `managers` is not a league
 * destination and serves both sports.
 */
export function featureSports(f: Pick<LandingFeature, 'destination'>): Sport[] {
  if (f.destination === 'managers') return ['nfl', 'nba']
  const d = LEAGUE_DESTINATIONS.find((x) => x.key === f.destination)
  return d ? [...d.sports] : []
}

/** Every string the page and its head tags can say, for the banned-words guard. */
export function allCopyStrings(): string[] {
  return [
    title, description, headline, subheadline, platformsNote, imageAlt, footerLine,
    ...sports,
    ...groups.flatMap((g) => [headingOf(g.key), ...g.features.flatMap((f) => [f.name, f.line])]),
  ]
}
