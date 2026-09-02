import { useEffect, useMemo, useRef, useState } from 'react'
import type { AvailabilityRow } from '../api'
import { posRank } from '../posRank'
import { roundPickLabel } from '../roundPickLabel'

type Props = {
  availability: AvailabilityRow[]
  myPicks: number[]
  teams: number
  pickedPlayerIds: Set<number>
  // Whether there is a draft to have options in yet. Drives both the empty
  // copy and whether the sheet opens itself -- see the collapse note below.
  started: boolean
}

const POSITIONS = ['ALL', 'QB', 'RB', 'WR', 'TE'] as const

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
  myPicks,
  teams,
  pickedPlayerIds,
  started,
}: Props) {
  const [filter, setFilter] = useState<(typeof POSITIONS)[number]>('ALL')
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
  const maxDepth = Math.max(1, Math.min(8, myPicks.length))
  const shownDepth = Math.min(depth, maxDepth)

  // myPicks belongs in the dependency list: it feeds `picks`, which the filter
  // below reads. It only changes alongside `availability` today, so the stale
  // value was never observable -- but that is a coincidence of the call site,
  // not a property of this component.
  const picks = useMemo(() => myPicks.slice(0, shownDepth), [myPicks, shownDepth])
  const rows = useMemo(
    () =>
      availability
        .filter((r) => filter === 'ALL' || r.player.position === filter)
        .filter((r) => !pickedPlayerIds.has(r.player.id))
        .filter((r) => picks.some((p) => (r.survivalByPick[String(p)] ?? 0) > 0.01))
        .slice(0, 60),
    [availability, filter, picks, pickedPlayerIds],
  )

  return (
    <section ref={sheetRef} className={`panel avail-sheet${collapsed ? ' collapsed' : ''}`}>
      <header className="panel-head">
        {/* Collapsed, this is a single floating pill in the board's
            bottom-right corner, not a full-width bar: a collapsed bar still
            covered a whole round, which is not "giving the board back". The
            filters and the depth slider have nothing to act on while the list
            is hidden, so they go with it. */}
        {!collapsed && <h2>Availability at your picks</h2>}
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
          {!collapsed && (
            <label className="depth">
              picks shown
              <input
                type="range"
                min={1}
                max={maxDepth}
                value={shownDepth}
                disabled={myPicks.length === 0}
                onChange={(e) => setDepth(Number(e.target.value))}
              />
              {myPicks.length === 0 ? 'none left' : shownDepth}
            </label>
          )}
          {/* The point of the floating sheet: this puts the whole board back.
              A button, deliberately not an Escape binding -- PlayerCard and
              PlayerPicker already bind Escape on `window`, and a third
              listener would fire alongside them. */}
          <button
            className="chip sheet-toggle"
            onClick={() => setCollapsed((c) => !c)}
            aria-expanded={!collapsed}
            title={collapsed ? 'Show the player list' : 'Hide the player list, show the whole board'}
          >
            {collapsed ? '▾ availability' : '▴ hide'}
          </button>
        </div>
      </header>

      {!collapsed && (
        <div className="avail-scroll panel-body">
          <table className="avail">
            <thead>
              <tr>
                <th className="player-col">Player</th>
                <th>Board</th>
                {picks.map((p) => (
                  <th key={p}>{roundPickLabel(p, teams)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.player.id}>
                  <td className="player-col">
                    <span className={`pos ${r.player.position}`}>{posRank(r.player)}</span>
                    {r.player.name}
                    <span className="team">{r.player.team}</span>
                  </td>
                  <td className="num">{Math.round(r.player.adp)}</td>
                  {picks.map((p) => {
                    const v = r.survivalByPick[String(p)] ?? 0
                    return (
                      <td key={p} className="bar-cell">
                        <div className="bar" style={{ width: `${Math.round(v * 100)}%` }} />
                        <span className="bar-label">{Math.round(v * 100)}%</span>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
          {rows.length === 0 && (
            <p className="muted">
              {!started
                ? 'Your realistic options show up here once the draft starts.'
                : 'No players survive to these picks in any run.'}
            </p>
          )}
        </div>
      )}
    </section>
  )
}
