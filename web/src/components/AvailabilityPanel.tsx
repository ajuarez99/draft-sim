import { useEffect, useMemo, useRef, useState } from 'react'
import type { AvailabilityRow, PlayerRef, Sport } from '../api'
import { filterPositions } from '../positions'
import { positionRun } from '../pickRun'
import { tierPlayers } from '../tiers'
import PlayerFace from './PlayerFace'
import { needLabel } from '../teamNeeds'
import { posRank } from '../posRank'
import { roundPickLabel } from '../roundPickLabel'

type Props = {
  /**
   * The simulation's per-player survival curves. Optional: a mock draft runs no
   * simulation, so it has none, and passes `players` + `noAvailabilityReason`
   * instead and still gets the tiered list.
   */
  availability?: AvailabilityRow[]
  /** The undrafted pool, for rooms with no `availability`. Ignored when `availability` is given. */
  players?: PlayerRef[]
  /**
   * Why there are no survival numbers here, shown in their place. When set, the
   * survival strip and verdict are hidden even if `availability` is present
   * (the live room before the seat is known: a curve computed for an assumed
   * seat would answer a question the reader didn't ask).
   */
  noAvailabilityReason?: string
  /** Recent picks, newest last, for the position-run callout. Omit to show none. */
  recentPicks?: { position: string }[]
  myPicks: number[]
  teams: number
  pickedPlayerIds?: Set<number>
  // Whether there is a draft to have options in yet. Drives both the empty
  // copy and whether the sheet opens itself -- see the collapse note below.
  started: boolean
  // Which sport's positions to filter by (multi-sport-and-rebrand.md Phase 6)
  // -- the caller's own board, never a union of both sports'.
  sport: Sport
  /**
   * The user's own open starting slots, from teamNeeds.openPositions. When
   * given, each row picks up the same "Fills RB" tag PlayerPicker already puts
   * on its rows -- the two answers come from one function (openSlotFor), so
   * the sheet and the picker cannot disagree about what you need.
   *
   * Optional and undefined by default: the rooms that don't know whose seat is
   * whose (or aren't showing your roster) should show no tag rather than a tag
   * computed against somebody else's team.
   */
  openSlots?: Set<string>
}

// Survival at a given pick, defaulting missing entries to 0 (gone in every
// run) rather than undefined -- every consumer below (the row filter, the
// sort key, the strip, the verdict) needs the same fallback, so it lives in
// one place instead of five `?? 0`s that could drift.
// "ADP 12" or "ADP 12–15", from the tier's own members.
function adpSpan(adps: number[]): string {
  const lo = Math.round(Math.min(...adps))
  const hi = Math.round(Math.max(...adps))
  return lo === hi ? `ADP ${lo}` : `ADP ${lo}–${hi}`
}

const NO_PICKED: Set<number> = new Set()

function survivalAt(row: AvailabilityRow, pick: number): number {
  return row.survivalByPick[String(pick)] ?? 0
}

// Verdict thresholds for "survival at your very next pick", named because
// 0.35 and 0.65 mean nothing on their own. Below one-in-three, the player is
// gone more often than not by a wide margin -- if you want him, this is
// probably your last look, hence "act now". At or above two-in-three he
// survives more often than not, comfortably -- "safe" to wait on. The 30-point
// band between is deliberately wide rather than split at 50%: anything in it
// is close enough to call that treating it as a coin flip is more honest than
// implying either side of it means something.
const RISK_MAX = 0.35
const SAFE_MIN = 0.65
function verdict(survivalAtNextPick: number): { label: string; cls: 'risk' | 'even' | 'safe' } {
  if (survivalAtNextPick < RISK_MAX) return { label: 'Act now', cls: 'risk' }
  if (survivalAtNextPick >= SAFE_MIN) return { label: 'Safe', cls: 'safe' }
  return { label: 'Coin flip', cls: 'even' }
}

/**
 * The headline output. For each player, the probability he is still on the
 * board when each of your picks comes up.
 *
 * Renders as a sheet floating over the board (`.avail-sheet`, positioned by
 * `.board-stage`) rather than as a band below it: the board gets the whole
 * page, and this lies over its deepest rounds -- the ones still empty for
 * almost the whole draft. Collapsing it puts the whole board back. See
 * claude/pill-board-and-player-list-on-top.md section E.
 *
 * Only your first few picks are shown by default -- past about four picks out
 * the numbers are compounding a lot of model uncertainty and are worth much
 * less than they look.
 */
export default function AvailabilityPanel({
  availability,
  players,
  noAvailabilityReason,
  recentPicks,
  myPicks,
  teams,
  pickedPlayerIds = NO_PICKED,
  started,
  sport,
  openSlots,
}: Props) {
  const POSITIONS = useMemo(() => filterPositions(sport), [sport])
  // Undefined openSlots means "don't tag", not "nothing is open" -- an empty
  // set is the legitimate reading for a team whose starters are all filled,
  // and the two must not collapse into the same render.
  const need = (position: string) => (openSlots ? needLabel(sport, position, openSlots) : null)
  const [filter, setFilter] = useState<string>('ALL')
  const [depth, setDepth] = useState(4)
  // Collapsed until there is something to look at, so the sheet never covers
  // the "Ready when you are" CTA that `.start-overlay` puts in the middle of
  // the same stage. It opens itself once -- on the transition into `started`,
  // not on every render while started -- so a deliberate collapse mid-draft
  // stays collapsed.
  const [collapsed, setCollapsed] = useState(!started)
  const wasStarted = useRef(started)
  useEffect(() => {
    if (started && !wasStarted.current) setCollapsed(false)
    wasStarted.current = started
  }, [started])

  // The board scrolls *behind* this sheet, which means at maximum scroll the
  // deepest rounds sit underneath it and cannot be brought into the clear at
  // all -- by round 7 that is the half of the board you actually care about.
  // Publishing our own height to the stage lets the grid reserve that much
  // space after its last row (`.board { padding-bottom }`), so every round can
  // be scrolled up above the sheet. Measured rather than assumed: the sheet is
  // capped at a share of the stage but is shorter when the table is short, and
  // both change with the window.
  //
  // Writing to `parentElement` is the ugly part. The alternative is lifting
  // this to both pages and duplicating the observer in each; the sheet is the
  // thing that knows its own height, so it publishes it.
  const sheetRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const el = sheetRef.current
    const stage = el?.parentElement
    if (!el || !stage) return
    // Collapsed, the sheet is a small corner pill -- it occludes one cell, not
    // a band, and reserving a row of empty space for it would be worse.
    if (collapsed) {
      stage.style.removeProperty('--avail-sheet-reserve')
      return
    }
    const publish = () =>
      stage.style.setProperty('--avail-sheet-reserve', `${Math.round(el.getBoundingClientRect().height) + 26}px`)
    publish()
    const ro = new ResizeObserver(publish)
    ro.observe(el)
    return () => {
      ro.disconnect()
      stage.style.removeProperty('--avail-sheet-reserve')
    }
  }, [collapsed])

  // myPicks now shrinks as reactive resimulation locks in each of your picks
  // (DraftView passes only undecided ones), so `depth`'s own state can end up
  // larger than the range input's current max -- clamp what's actually shown/
  // sliced to the live max rather than the raw depth state, or the slider's
  // value/max invert (browsers clamp display silently, `depth` itself would
  // never visibly catch up without this).
  // Capped at 6, not 8: this is a row of chips now rather than a slider, so
  // every extra step is visible chrome, and past ~4 picks out the numbers are
  // compounding enough model uncertainty to be worth less than they look
  // (the panel's own doc comment above). Six columns is also about what the
  // table has room for before the names start clipping.
  const maxDepth = Math.max(1, Math.min(6, myPicks.length))
  const shownDepth = Math.min(depth, maxDepth)

  // myPicks belongs in the dependency list: it feeds `picks`, which the filter
  // below reads. It only changes alongside `availability` today, so the stale
  // value was never observable -- but that is a coincidence of the call site,
  // not a property of this component.
  const picks = useMemo(() => myPicks.slice(0, shownDepth), [myPicks, shownDepth])
  // Survival numbers exist only when there are curves AND nothing says to hold
  // them back. Everything survival-shaped below keys off this one flag.
  const showSurvival = availability != null && !noAvailabilityReason
  const rows = useMemo(() => {
    const source: { player: PlayerRef; row: AvailabilityRow | null }[] = availability
      ? availability.map((row) => ({ player: row.player, row }))
      : (players ?? []).map((player) => ({ player, row: null }))
    const candidates = source
      .filter((r) => filter === 'ALL' || r.player.position === filter)
      .filter((r) => !pickedPlayerIds.has(r.player.id))
      .filter((r) => !showSurvival || picks.some((p) => survivalAt(r.row!, p) > 0.01))
      // Board rank still caps *which* players are worth showing at all --
      // top 60 by consensus is a reasonable "in range" pool.
      .slice(0, 60)
    return candidates
  }, [availability, players, filter, picks, pickedPlayerIds, showSurvival])

  // Tiers group by consensus ADP (tiers.ts). Inside a tier the sheet's old
  // job survives: when there are survival numbers, the player you are most at
  // risk of losing at your very next pick comes first.
  const tiers = useMemo(() => {
    const t = tierPlayers(rows, (r) => r.player.adp)
    if (!showSurvival || picks.length === 0) return t
    const nextPick = picks[0]
    return t.map((tier) => ({
      ...tier,
      players: [...tier.players].sort((a, b) => survivalAt(a.row!, nextPick) - survivalAt(b.row!, nextPick)),
    }))
  }, [rows, showSurvival, picks])

  const run = useMemo(
    () => (recentPicks ? positionRun(recentPicks, 6, 4, sport) : null),
    [recentPicks, sport],
  )

  // Early in a draft, a column for your third or fourth pick out is ~0% for
  // nearly every row shown -- everyone still in range of the board is
  // expected to be long gone by then, so the column carries no information,
  // just width. Trim trailing pick-columns where no currently-*displayed*
  // row clears the same rounding floor the percentages themselves use
  // (anything under 0.5% already rounds to "0%" on screen) -- trailing only,
  // because survival only falls as picks get further away, so a later column
  // is never the one worth keeping if an earlier one wasn't. Recomputed off
  // `rows`, not `availability`, so switching the position filter to a
  // thinner position can un-trim a column that a fuller list had dropped.
  const visiblePicks = useMemo(() => {
    if (rows.length === 0 || !showSurvival) return picks
    let end = picks.length
    while (end > 1 && rows.every((r) => survivalAt(r.row!, picks[end - 1]) < 0.005)) end--
    return picks.slice(0, end)
  }, [picks, rows, showSurvival])

  return (
    <section ref={sheetRef} className={`panel avail-sheet${collapsed ? ' collapsed' : ''}`}>
      <header className="panel-head">
        {/* Collapsed, this is a single floating pill in the board's
            bottom-right corner, not a full-width bar: a collapsed bar still
            covered a whole round, which is not "giving the board back". The
            filters and the depth slider have nothing to act on while the list
            is hidden, so they go with it. */}
        {!collapsed && <h2>{showSurvival ? "Who's still there when you pick" : 'Best available'}</h2>}
        <div className="controls-inline">
          {!collapsed &&
            POSITIONS.map((p) => (
              <button
                key={p}
                // Position chips carry the same six colors as the board cells
                // and the `.pos` badges, so the filter row reads as part of the
                // board it is lying on. "ALL" has no position, so it stays
                // neutral.
                className={`chip${p === 'ALL' ? '' : ` pos-chip ${p}`}${filter === p ? ' on' : ''}`}
                onClick={() => setFilter(p)}
              >
                {p}
              </button>
            ))}
          {/* A labelled set of chips, not a range input. The slider was the
              one control in the app rendering in the browser's default blue
              -- `input[type=range]` has no style here -- and it paired a
              tuning knob with a loose integer where the question is just "how
              many of my picks ahead". Same values, same clamping. */}
          {!collapsed && showSurvival && (
            <span className="depth">
              {myPicks.length === 0 ? (
                <span className="muted">No picks left</span>
              ) : (
                <>
                  <span className="depth-label">Next</span>
                  {Array.from({ length: maxDepth }, (_, i) => i + 1).map((n) => (
                    <button
                      key={n}
                      className={`chip depth-chip${n === shownDepth ? ' on' : ''}`}
                      onClick={() => setDepth(n)}
                      aria-pressed={n === shownDepth}
                      title={`Show the next ${n} of your picks`}
                    >
                      {n}
                    </button>
                  ))}
                </>
              )}
            </span>
          )}
          {/* The point of the floating sheet: this puts the whole board back.
              A button, deliberately not an Escape binding -- PlayerCard and
              PlayerPicker already bind Escape on `window`, and a third
              listener would fire alongside them. */}
          <button
            className="chip sheet-toggle"
            onClick={() => setCollapsed((c) => !c)}
            aria-expanded={!collapsed}
            title={collapsed ? 'Show the player list' : 'Hide the player list and show the whole board'}
          >
            {collapsed ? '▾ Players' : '▴ Hide'}
          </button>
        </div>
      </header>

      {!collapsed && (
        <div className="avail-scroll panel-body">
          {run && (
            <p className="avail-run" role="note">
              <strong>{run.position} run:</strong> {run.count} of the last {run.window} picks
            </p>
          )}
          {noAvailabilityReason && <p className="muted small avail-reason">{noAvailabilityReason}</p>}
          <table className="avail">
            <thead>
              <tr>
                <th className="player-col">Player</th>
                <th>Board</th>
                {/* One header for the whole decay curve, not one per pick --
                    the individual pick labels ("2.03", "2.11", ...) that used
                    to head their own column move onto each strip cell's own
                    `title` instead. The header's own title lists them all, for
                    anyone who wants the full run without hovering cell by
                    cell. */}
                {showSurvival && (
                  <th
                    className="strip-col"
                    title={visiblePicks.map((p) => roundPickLabel(p, teams)).join(' · ')}
                  >
                    Next picks
                  </th>
                )}
                {showSurvival && <th>Verdict</th>}
              </tr>
            </thead>
            {tiers.map((tier) => (
              <tbody key={tier.label}>
                <tr className="tier-head">
                  <th colSpan={showSurvival ? 4 : 2} scope="colgroup">
                    {tier.label}
                    {tier.tier != null && <span className="tier-range"> {adpSpan(tier.players.map((r) => r.player.adp))}</span>}
                  </th>
                </tr>
                {tier.players.map((r) => {
                  // "Next" always means the user's very next pick (picks[0]),
                  // never the last *visible* column -- trimming trailing zero
                  // columns changes what's drawn, not what "next" means, and a
                  // verdict that silently repointed itself when a column
                  // dropped would be a worse bug than the dead width it fixes.
                  const v = showSurvival ? verdict(picks.length > 0 ? survivalAt(r.row!, picks[0]) : 0) : null
                  return (
                    <tr key={r.player.id}>
                      <td className="player-col">
                        <span className={`pos ${r.player.position}`}>{posRank(r.player)}</span>
                        <PlayerFace
                          sport={sport}
                          sleeperId={r.player.sleeperId}
                          team={r.player.team}
                          position={r.player.position}
                          name={r.player.name}
                          size={20}
                        />
                        {r.player.name}
                        <span className="team">{r.player.team}</span>
                        {need(r.player.position) && <span className="need-tag">{need(r.player.position)}</span>}
                      </td>
                      <td className="num">{Math.round(r.player.adp)}</td>
                      {showSurvival && (
                        <td className="strip-cell">
                          <div className="survival-strip">
                            {visiblePicks.map((p) => {
                              const pv = survivalAt(r.row!, p)
                              const pct = Math.round(pv * 100)
                              return (
                                <span
                                  key={p}
                                  className="survival-block"
                                  // Teal mixed into the panel color by survival --
                                  // full teal at 100%, fading to plain --panel as
                                  // a player's odds of still being there drop to
                                  // zero. Reusing --teal (generic interaction, per
                                  // the house style) rather than inventing a risk
                                  // hue: this is a decay reading, not an identity
                                  // one, and --crimson is reserved for "you"
                                  // alone. A near-zero cell still reads as a tile
                                  // rather than a gap because `.survival-block`
                                  // carries its own hairline border -- the fill is
                                  // the only thing that goes to nothing.
                                  style={{ background: `color-mix(in oklch, var(--teal) ${Math.round(pv * 92)}%, var(--panel))` }}
                                  title={`${roundPickLabel(p, teams)}: ${pct}% likely still there`}
                                />
                              )
                            })}
                          </div>
                        </td>
                      )}
                      {v && <td className={`verdict verdict-${v.cls}`}>{v.label}</td>}
                    </tr>
                  )
                })}
              </tbody>
            ))}
          </table>
          {rows.length === 0 && (
            <p className="muted">
              {!started
                ? 'Your realistic options show up here once the draft starts.'
                : showSurvival
                  ? 'No players survive to these picks in any run.'
                  : 'No players to list.'}
            </p>
          )}
        </div>
      )}
    </section>
  )
}
