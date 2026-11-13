import { useMemo, useState } from 'react'
import type { HistoricalPlayerCard, HistoricalSport } from '../api'
import { posPill } from '../positions'
import { cardToPlayerRef, priorLine, pts1 } from '../historicalCopy'
import { isRookie, rookieFlagKnown } from '../rookie'
import RookieTag, { RookieChip } from './RookieTag'

type Props = {
  players: HistoricalPlayerCard[]
  rankingLabel: string
  /** Which sport's filters and copy to show (spec 056). Defaults to NBA, which is all a 053 backend has. */
  sport?: HistoricalSport
  canPick: boolean
  busy: boolean
  onPick: (c: HistoricalPlayerCard) => void
  /**
   * Spec 063: the fan's queue. Both optional and inert when absent: a list without them renders exactly as before.
   * With them, each row gets a star toggle with an accessible name.
   */
  queued?: ReadonlySet<number>
  onToggleQueue?: (c: HistoricalPlayerCard) => void
}

const FILTERS: Record<HistoricalSport, string[]> = {
  NBA: ['ALL', 'PG', 'SG', 'SF', 'PF', 'C'],
  NFL: ['ALL', 'QB', 'RB', 'WR', 'TE'],
}
const PAGE = 80

/**
 * The historical room's available-players list. The shared AvailabilityPanel is not used here on
 * purpose: it tiers and labels by ADP and shows survival odds, and a historical game has neither
 * (the order is last season's points, and says so in the header via the API's own label).
 */
export default function HistoricalPlayerList({ players, rankingLabel, sport = 'NBA', canPick, busy, onPick, queued, onToggleQueue }: Props) {
  const [filter, setFilter] = useState('ALL')
  const [q, setQ] = useState('')
  const [shown, setShown] = useState(PAGE)
  // Rookies only narrows the position filter; offered only when this game's board knows who its rookies are.
  const [rookiesWanted, setRookiesWanted] = useState(false)
  const rookiesOffered = useMemo(() => rookieFlagKnown(players), [players])
  const rookiesOnly = rookiesWanted && rookiesOffered

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return players.filter(
      (p) =>
        (filter === 'ALL' || p.positions.includes(filter)) &&
        (!rookiesOnly || isRookie(p)) &&
        (needle === '' || p.name.toLowerCase().includes(needle)),
    )
  }, [players, filter, rookiesOnly, q])

  return (
    <section className="panel hist-list" aria-label="Available players">
      <div className="panel-head">
        <h2>Available players</h2>
        <span className="muted small">
          {rankingLabel}
          {sport === 'NBA' ? ' · fantasy points exclude bonuses' : ''}
        </span>
      </div>
      <div className="hist-list-tools">
        <div className="hist-filters" role="group" aria-label="Position filter">
          {FILTERS[sport].map((f) => (
            <button
              key={f}
              type="button"
              className={`chip${f === filter ? ' on' : ''}`}
              aria-pressed={f === filter}
              onClick={() => {
                setFilter(f)
                setShown(PAGE)
              }}
            >
              {f === 'ALL' ? 'All' : f}
            </button>
          ))}
          {rookiesOffered && (
            <RookieChip
              on={rookiesOnly}
              onChange={(on) => {
                setRookiesWanted(on)
                setShown(PAGE)
              }}
            />
          )}
        </div>
        <input
          type="search"
          className="hist-search"
          placeholder="Search players"
          aria-label="Search players"
          value={q}
          onChange={(e) => {
            setQ(e.target.value)
            setShown(PAGE)
          }}
        />
      </div>
      {rows.length === 0 ? (
        <p className="muted">{rookiesOnly ? 'No rookies match.' : 'No players match.'}</p>
      ) : (
        <ul className="hist-rows">
          {rows.slice(0, shown).map((p) => {
            const pill = posPill(cardToPlayerRef(p, sport), sport === 'NFL' ? 'nfl' : 'nba')
            return (
              <li key={p.id} className="hist-row">
                <span className="hist-rank" title={rankingLabel}>
                  {p.rank}
                </span>
                <div className="hist-main">
                  <div className="hist-name">
                    <strong>{p.name}</strong>
                    <RookieTag player={p} />
                    {p.positions.length > 0 ? (
                      p.positions.map((pos) => (
                        <span key={pos} className={`pos ${pos}`}>
                          {pos}
                        </span>
                      ))
                    ) : (
                      <span className={pill.className} style={pill.style}>
                        --
                      </span>
                    )}
                    <span className="muted small">
                      {p.team ?? '—'}
                      {p.age != null ? ` · ${p.age}` : ''}
                    </span>
                  </div>
                  <div className={`hist-line small${p.prior ? '' : ' muted'}`}>
                    {priorLine(p.prior, sport, p.rookiePick)}
                    {p.prior && (
                      <span className="muted">
                        {' '}
                        · {pts1(p.prior.fantasyPoints)} fantasy pts{sport === 'NBA' ? ' (excludes bonuses)' : ''}
                        {p.prior.bestOfTwo ? ` · ranked on ${p.prior.bestOfTwo}` : ''}
                      </span>
                    )}
                  </div>
                </div>
                {onToggleQueue ? (
                  <div className="hist-row-actions">
                    <button
                      type="button"
                      className={`hist-star${queued?.has(p.id) ? ' on' : ''}`}
                      aria-pressed={queued?.has(p.id) ?? false}
                      aria-label={`Queue ${p.name}`}
                      title={queued?.has(p.id) ? 'In your queue. Press to remove.' : 'Add to your queue'}
                      onClick={() => onToggleQueue(p)}
                    >
                      <span aria-hidden="true">{queued?.has(p.id) ? '★' : '☆'}</span>
                    </button>
                    {canPick && (
                      <button type="button" className="chip hist-draft" disabled={busy} onClick={() => onPick(p)}>
                        Draft
                      </button>
                    )}
                  </div>
                ) : (
                  canPick && (
                    <button type="button" className="chip hist-draft" disabled={busy} onClick={() => onPick(p)}>
                      Draft
                    </button>
                  )
                )}
              </li>
            )
          })}
        </ul>
      )}
      {rows.length > shown && (
        <button type="button" className="chip" onClick={() => setShown((s) => s + PAGE)}>
          Show more ({rows.length - shown} left)
        </button>
      )}
    </section>
  )
}
