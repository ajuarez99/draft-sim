import { ordinal, type Voice } from './historicalCopy'

// Spec 074 T042: the words of a result page that depend on who is reading it. The owner's page ("you") and a result
// somebody else sent a link to ("shared": this team, never "you") share every component; each string that names the
// drafter is a pair here, one per voice. redraftVoice.test.ts renders the whole shared page and asserts no
// `\byou(r|rs)?\b` survives, which is what catches a string added to a component and not to this table.
//
// PURE: no React, no browser global. The owner's strings are the ones that were inline in the components before
// this table existed; HistoricalResults.test.tsx pins them.

export type { Voice }

export type VoiceWords = {
  finishAria: string
  oldFinish: (place: number, teams: number) => string
  /** The draft-score sentence under the number: both totals when the server sent them. */
  scoreOutscored: (mine: string, hindsight: string) => string
  scoreShare: (mine: string, score: number, hindsight: string) => string
  /** A reduced public game withholds the hindsight total (today's answer): the same sentences without it. */
  scoreOutscoredReduced: (mine: string) => string
  scoreShareReduced: (mine: string, score: number) => string
  markWords: { ahead: string; same: string; behind: string }
  picksGridAria: string
  gridTap: string
  gridTapReduced: string
  momentsHeading: string
  gotRightHeading: string
  /** [the bold lead, the rest of the sentence up to "The biggest jump:"] */
  gotRightLead: (ahead: number, total: number) => [string, string]
  gotRightNone: (total: number) => string
  bustNone: string
  stealHeading: string
  stealLead: (pickNo: number) => string
  stealNone: string
  pickFinishesAria: string
  hindsightSeat: string
  hindsightRosterNote: string
  hindsightYouTook: string
  gotHimLater: (pickNo: number, round: number) => string
  againstHeading: string
  againstAria: string
  mineWhole: (old: boolean) => string
  beatIt: string
  standingsHint: (place: string, record: string) => string
  teamCell: (slot: number, userSlot: number) => string
  /** A team's name inside a sentence ("Team 4 advanced as the better seed"). */
  teamInline: (slot: number, userSlot: number) => string
  lineupHeading: (week: number) => string
  weeksHint: (n: number) => string
  lineupNoteNfl: string
  byeLine: string
  missedPlayoffsLine: string
  eliminatedLine: string
  bracketWon: (teamName: string, isDrafter: boolean) => string
}

const you: VoiceWords = {
  finishAria: 'Your finish',
  oldFinish: (place, teams) => `You finished ${ordinal(place)} of ${teams}`,
  scoreOutscored: (m, h) => `You outscored the hindsight draft, ${m} points to ${h}.`,
  scoreShare: (m, score, h) => `Your ${m} points are ${score}% of the hindsight draft's ${h}.`,
  scoreOutscoredReduced: (m) => `You outscored the hindsight draft with ${m} points.`,
  scoreShareReduced: (m, score) => `Your ${m} points are ${score}% of the hindsight draft's.`,
  markWords: {
    ahead: 'finished ahead of where you drafted him',
    same: 'finished where you drafted him',
    behind: 'finished behind where you drafted him',
  },
  picksGridAria: 'Your picks, one square each',
  gridTap: 'Tap one to see the player.',
  gridTapReduced: 'Tap one to see how it went.',
  momentsHeading: 'The story of your draft',
  gotRightHeading: 'What you got right',
  gotRightLead: (a, t) => [`${a} of your ${t} picks`, ' finished ahead of where you drafted them. The biggest jump:'],
  gotRightNone: (t) => `None of your ${t} picks finished ahead of where you drafted them.`,
  bustNone: 'None: every player you took finished at or above where you took him.',
  stealHeading: 'Best steal you passed on',
  stealLead: (n) => `At pick ${n} you took`,
  stealNone: 'Nobody left on the board outscored the player you took.',
  pickFinishesAria: 'Your picks, drafted and finished',
  hindsightSeat: 'The same schedule with this roster in your seat.',
  hindsightRosterNote:
    "The best roster we found among players still on the board at your picks. A player you took later than the pick shown was the better deal, and the row says so. Points are each player's season total as scored here.",
  hindsightYouTook: 'You took',
  gotHimLater: (pick, round) => `You got him later: pick ${pick}, round ${round}`,
  againstHeading: 'You against hindsight',
  againstAria: 'Your draft against hindsight',
  mineWhole: (old) => (old ? 'You' : 'You, whole real season'),
  beatIt: 'You beat the hindsight draft.',
  standingsHint: (place, record) => `You: ${place}, ${record}`,
  teamCell: (slot, user) => (slot === user ? 'You' : `Team ${slot}`),
  teamInline: (slot, user) => (slot === user ? 'You' : `Team ${slot}`),
  lineupHeading: (w) => `Your lineup, week ${w}`,
  weeksHint: (n) => `${n} ${n === 1 ? 'week' : 'weeks'}, every score and your lineup`,
  lineupNoteNfl: 'Each starter counts his score for the week; your best legal lineup is set for you.',
  byeLine: 'You had a bye this week.',
  missedPlayoffsLine: 'Your team did not make the playoffs, so there is no lineup this week.',
  eliminatedLine: 'You were eliminated before this week.',
  bracketWon: (name, drafter) => (drafter ? 'You won it all' : `${name} won it all`),
}

const shared: VoiceWords = {
  finishAria: "This team's finish",
  oldFinish: (place, teams) => `This team finished ${ordinal(place)} of ${teams}`,
  scoreOutscored: (m, h) => `This team outscored the hindsight draft, ${m} points to ${h}.`,
  scoreShare: (m, score, h) => `This team's ${m} points are ${score}% of the hindsight draft's ${h}.`,
  scoreOutscoredReduced: (m) => `This team outscored the hindsight draft with ${m} points.`,
  scoreShareReduced: (m, score) => `This team's ${m} points are ${score}% of the hindsight draft's.`,
  markWords: {
    ahead: 'finished ahead of where he was drafted',
    same: 'finished where he was drafted',
    behind: 'finished behind where he was drafted',
  },
  picksGridAria: "This team's picks, one square each",
  gridTap: 'Tap one to see the player.',
  gridTapReduced: 'Tap one to see how it went.',
  momentsHeading: 'The story of this draft',
  gotRightHeading: 'What this draft got right',
  gotRightLead: (a, t) => [`${a} of ${t} picks`, ' finished ahead of where they were drafted. The biggest jump:'],
  gotRightNone: (t) => `None of ${t} picks finished ahead of where they were drafted.`,
  bustNone: 'None: every player this team took finished at or above where this team took him.',
  stealHeading: 'Best steal passed on',
  stealLead: (n) => `At pick ${n} this team took`,
  stealNone: 'Nobody left on the board outscored the player this team took.',
  pickFinishesAria: "This team's picks, drafted and finished",
  hindsightSeat: "The same schedule with this roster in this team's seat.",
  hindsightRosterNote:
    "The best roster we found among players still on the board at this team's picks. A player this team took later than the pick shown was the better deal, and the row says so. Points are each player's season total as scored here.",
  hindsightYouTook: 'This team took',
  gotHimLater: (pick, round) => `Taken later by this team: pick ${pick}, round ${round}`,
  againstHeading: 'This draft against hindsight',
  againstAria: 'This draft against hindsight',
  mineWhole: (old) => (old ? 'This team' : 'This team, whole real season'),
  beatIt: 'This team beat the hindsight draft.',
  standingsHint: (place, record) => `This team: ${place}, ${record}`,
  teamCell: (slot) => `Team ${slot}`,
  teamInline: (slot, user) => (slot === user ? 'This team' : `Team ${slot}`),
  lineupHeading: (w) => `This team's lineup, week ${w}`,
  weeksHint: (n) => `${n} ${n === 1 ? 'week' : 'weeks'}, every score and this team's lineup`,
  lineupNoteNfl: 'Each starter counts his score for the week; the best legal lineup is set each week.',
  byeLine: 'This team had a bye this week.',
  missedPlayoffsLine: 'This team did not make the playoffs, so there is no lineup this week.',
  eliminatedLine: 'This team was eliminated before this week.',
  bracketWon: (name, drafter) => (drafter ? 'This team won it all' : `${name} won it all`),
}

export const voiceWords = (v: Voice): VoiceWords => (v === 'you' ? you : shared)

/** Text after the drafter's name in a shared result's tables; empty in the owner's, where the name is "You". */
export const drafterSuffix = (v: Voice): string => (v === 'shared' ? ' · this team' : '')

/** The class that marks the drafter's row: `me` (the --you tint) for the owner, `tm-drafter` for anybody else's page. */
export const drafterClass = (v: Voice, isDrafter: boolean, ownerClass: string = 'hist-me'): string | undefined =>
  !isDrafter ? undefined : v === 'you' ? ownerClass : 'tm-drafter'
