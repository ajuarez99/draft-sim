import type { StatMode } from './statLeaderboard'

/**
 * specs/032-stat-calculator US2 (research R11, FR-016): five ready-made formulas, bundled with the
 * app. Each carries its own counting mode, so loading "Stocks per 36" switches the page to per 36.
 *
 * `expr` is the canonical spelling (a test holds that), so loading a preset writes the same `f` the
 * editor would. `mixesUnits` is a fact about the formula, not a label: a test holds that it equals
 * `inferUnits(...).length > 0`.
 */
export type Preset = {
  id: string
  name: string
  expr: string
  mode: StatMode
  /** One plain-language sentence or two; says what the number means and where it misleads. */
  description: string
  mixesUnits: boolean
}

/** N6: usage measures how much of a team's possessions a player uses, i.e. his role. */
const USAGE_NOTE = 'Usage measures a player’s role, not his quality, so a high number here can mean a big role as much as a good player.'

export const PRESETS: Preset[] = [
  {
    id: 'usage-value',
    name: 'Usage value',
    expr: '(usg + vor) / tov',
    mode: 'perGame',
    description:
      'Usage rate plus fantasy value over replacement, per turnover. Brackets were added to the request “usage rate + vorp / turnovers”, which by precedence would read USG + (VOR ÷ TO). ' +
      USAGE_NOTE,
    mixesUnits: true,
  },
  {
    id: 'ast-to',
    name: 'Assist-to-turnover',
    expr: 'ast / tov',
    mode: 'perGame',
    description: 'Assists for every turnover: how well a ball handler creates without giving it away.',
    mixesUnits: false,
  },
  {
    id: 'stocks-36',
    name: 'Stocks per 36',
    expr: 'stl + blk',
    mode: 'per36',
    description: 'Steals plus blocks per 36 minutes, so a bench player and a starter compare on the same playing time.',
    mixesUnits: false,
  },
  {
    id: 'efficient-volume',
    name: 'Efficient volume',
    expr: 'pts * ts / 100',
    mode: 'perGame',
    description: 'Points per game scaled by true shooting: scoring volume that is discounted when it comes inefficiently.',
    mixesUnits: false,
  },
  {
    id: 'fp-per-usage',
    name: 'Fantasy points per usage point',
    expr: 'fp / usg',
    mode: 'perGame',
    description: 'Fantasy points per game for each point of usage rate: who turns a smaller role into more fantasy production. ' + USAGE_NOTE,
    mixesUnits: false,
  },
]
