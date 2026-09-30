import { useEffect, useId, useRef } from 'react'
import Avatar from './Avatar'
import PersonName from './PersonName'
import type { Superlative } from '../api'

type Props = {
  s: Superlative
  title: string
  hue: number
  /** Built by the card from `standingFigure`, so the modal and the card can't disagree on a figure (amendment 10). */
  figure: (v: number) => string
  onClose: () => void
}

/**
 * specs/010-superlatives-full-standings: every roster's place for one award,
 * not just the winner(s). Structure follows StartMockModal (backdrop, card,
 * Escape, close button).
 *
 * Renders `s.standings` in payload order and never re-sorts: the backend owns
 * the ranking direction (LOWEST_WEEK and UNLUCKIEST run low to high) and its
 * tie ranks (1, 1, 3), and a client-side sort would quietly disagree with both.
 * A row with no value says why (`missingReason`) instead of showing a 0 that
 * would read as a measured result.
 */
export default function SuperlativeStandingsModal({ s, title, hue, figure, onClose }: Props) {
  const titleId = useId()
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  // Focus goes to the close button and comes back to whatever opened us (the
  // "See all" button, or nothing for a mouse click on the card body).
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    closeRef.current?.focus()
    return () => opener?.focus?.()
  }, [])

  // Same fix as PowerRankings' ballot: on phones the rail would otherwise sit
  // over/under the full-screen sheet.
  useEffect(() => {
    document.body.classList.add('bk-modal-fullscreen')
    return () => document.body.classList.remove('bk-modal-fullscreen')
  }, [])

  // B3: a drag that starts inside the card and ends on the backdrop fires a click on the
  // backdrop; only close when the press also began there.
  const downOnBackdrop = useRef(false)

  const isPlayers = s.kind === 'JABARI_SMITH_JR'

  return (
    // stopPropagation on the backdrop too (amendment 12): this modal renders
    // inside the card's <article>, whose own onClick opens it. Without this a
    // backdrop click would close it and then bubble up and reopen it.
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        downOnBackdrop.current = e.target === e.currentTarget
      }}
      onClick={(e) => {
        e.stopPropagation()
        if (downOnBackdrop.current) onClose()
        downOnBackdrop.current = false
      }}
    >
      <div
        className="modal-card wide sl-standings-modal"
        style={{ ['--sl-hue' as string]: hue }}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(e) => e.stopPropagation()}
      >
        <button ref={closeRef} type="button" className="modal-close" onClick={onClose} aria-label="Close">
          ✕
        </button>
        <h3 id={titleId} className="sl-standings-title">{title}</h3>

        {isPlayers && <p className="muted small sl-standings-sub">This award ranks players, not teams.</p>}
        {s.early && <p className="sl-early small sl-standings-sub">early — this is mostly noise</p>}
        {s.coverage && (
          <p className="muted small sl-standings-sub">
            {s.coverage.weeksCovered} of {s.coverage.weeksCovered + s.coverage.weeksExcluded} weeks
            {s.coverage.reasons.length > 0 ? ` — ${s.coverage.reasons.join('; ')}` : ''}
          </p>
        )}

        <ol className="sl-standings">
          {isPlayers
            ? s.playerStandings.map((p) => (
                <li className={`sl-standing${p.rank === 1 ? ' sl-standing-top' : ''}`} key={p.playerId}>
                  <span className="sl-standing-rank">{p.rank}</span>
                  <span className="sl-standing-who">
                    <span className="sl-standing-name">
                      {p.playerName}
                      {p.position ? ` (${p.position})` : ''}
                    </span>
                    {p.team && <span className="muted small sl-standing-note">{p.team}</span>}
                  </span>
                  <span className="sl-standing-figure">
                    {p.adds} {p.adds === 1 ? 'add' : 'adds'} by {p.distinctTeams}{' '}
                    {p.distinctTeams === 1 ? 'team' : 'teams'}
                  </span>
                </li>
              ))
            : s.standings.map((r) => (
                <li
                  className={`sl-standing${r.rank === 1 ? ' sl-standing-top' : ''}${r.hasValue ? '' : ' sl-standing-missing'}`}
                  key={r.team.rosterId}
                >
                  <span className="sl-standing-rank">{r.rank ?? '—'}</span>
                  <span className="sl-standing-who">
                    <span className="sl-standing-name">
                      <Avatar
                        avatarId={r.team.avatarId}
                        seed={String(r.team.managerId ?? r.team.rosterId)}
                        label={r.team.teamName}
                        hue={hue}
                        className="sl-avatar"
                      />
                      <PersonName teamName={r.team.teamName} username={r.team.username} />
                    </span>
                    {r.note && <span className="muted small sl-standing-note">{r.note}</span>}
                  </span>
                  {r.hasValue && r.value != null ? (
                    <span className="sl-standing-figure">{figure(r.value)}</span>
                  ) : (
                    <span className="sl-standing-figure muted small">{r.missingReason}</span>
                  )}
                </li>
              ))}
        </ol>
      </div>
    </div>
  )
}
