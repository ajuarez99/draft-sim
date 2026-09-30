import { useEffect, useRef, type CSSProperties, type ReactNode } from 'react'
import { adpLabel, asOfLabel, NO_PICKS_LEFT, type PickInsight } from '../pickInsight'
import { roundPickLabel } from '../roundPickLabel'
import Avatar from './Avatar'

/**
 * Rows supplied by the page rather than read off the insight (the manager's
 * on-brand read, the scarcity line). Each renders nothing at all until
 * something is passed -- a section without data is left out, never drawn empty
 * (FR-013). The model share and likely-next rows are NOT slots: they read
 * straight off `insight`, which already carries every field they show.
 */
export type PickCardExtras = {
  onBrand?: ReactNode
  scarcityLine?: ReactNode
}

/** Whole-number percent; a real but tiny share must not read as "0%". */
function pct(share: number): string {
  const n = Math.round(share * 100)
  return share > 0 && n < 1 ? '<1%' : `${n}%`
}

type Props = {
  insight: PickInsight
  teams: number
  /**
   * Move focus into the card. False on auto-open -- a card that appears
   * mid-draft must not steal focus from whatever you were doing -- and true
   * when it was opened by clicking a feed row, where you asked for it.
   */
  autoFocus: boolean
  onClose: () => void
  /** Hover or focus inside: the page pauses its auto-dismiss timer while true. */
  onPauseChange?: (paused: boolean) => void
  extras?: PickCardExtras
}

/**
 * What a pick meant for the roster that made it. Everything shown here comes
 * from picks that have already landed (see pickInsight.buildFactInsight), so
 * it is true the moment the pick is -- no projection is waited on.
 */
export default function PickInsightCard({ insight, teams, autoFocus, onClose, onPauseChange, extras }: Props) {
  const { pick, seat } = insight
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (autoFocus) rootRef.current?.focus()
  }, [autoFocus, pick.pickNo])

  // On the document, not the card: an auto-opened card never has focus, and
  // "Esc dismisses it" should still be true then.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const adp = adpLabel(insight.adpDelta)
  const ms = insight.modelShare
  const asOf = asOfLabel(ms, pick.pickNo)
  const hasTags = adp != null || ms != null
  const ln = insight.likelyNext
  const showLikely = insight.nextPickNo != null || (ln.state === 'none' && ln.reason === NO_PICKS_LEFT)
  const position = pick.player.position

  return (
    <div
      ref={rootRef}
      className="pick-card"
      role="dialog"
      aria-modal="false"
      aria-live="polite"
      aria-label={`${pick.manager} takes ${pick.player.name}`}
      tabIndex={-1}
      // Same device as PickFeed's newest row: the position's own colour, set
      // from the code because --qb/--pg/... are already named after it.
      style={{ '--lead-hue': `var(--${position.toLowerCase()})` } as CSSProperties}
      onMouseEnter={() => onPauseChange?.(true)}
      onMouseLeave={() => onPauseChange?.(false)}
      onFocus={() => onPauseChange?.(true)}
      onBlur={() => onPauseChange?.(false)}
    >
      <div className="pick-card-head">
        <Avatar avatarId={pick.avatarId} seed={String(seat?.managerId ?? pick.slot)} label={pick.manager} />
        <span className="pick-card-mgr">{pick.manager}</span>
        <span className="pick-card-no mono muted">{roundPickLabel(pick.pickNo, teams)}</span>
        <button type="button" className="pick-card-close" aria-label="Close pick card" onClick={onClose}>
          ×
        </button>
      </div>

      <div className="pick-card-player">
        <span className={`pos ${position}`}>{position}</span>
        <strong className="pick-card-name">{pick.player.name}</strong>
        {pick.player.team && <span className="muted tiny">{pick.player.team}</span>}
      </div>

      {insight.fitKnown && (
        <div className="pick-card-fit">
          <span className="pick-card-fills cond">{insight.fills ? `Fills ${insight.fills}` : 'Depth'}</span>
          <span className="muted small">
            {insight.rosterComplete ? 'Roster complete' : `Still needs: ${insight.openAfter.join(' · ')}`}
          </span>
        </div>
      )}

      {insight.fitKnown && insight.summary && <p className="pick-card-summary small">{insight.summary}</p>}

      {hasTags && (
        <div className="pick-card-tags">
          {adp && <span className="pick-card-tag">{adp}</span>}
          {ms && (
            <span className="pick-card-tag">
              {ms.bound === 'exact' ? 'Model had this at ' : 'Model had this under '}
              {pct(ms.share)}
              {asOf && <span className="muted tiny"> {asOf}</span>}
            </span>
          )}
          {insight.surprise && <span className="pick-card-tag pick-card-surprise">Surprise</span>}
        </div>
      )}

      {extras?.onBrand != null && <div className="pick-card-onbrand">{extras.onBrand}</div>}
      {showLikely && (
        <div className="pick-card-likely">
          <div className="pick-card-likely-head small">
            <strong>
              {insight.nextPickNo != null ? `Likely next @ ${roundPickLabel(insight.nextPickNo, teams)}` : 'Likely next'}
            </strong>
            {insight.provenanceLabel && <span className="muted tiny"> · {insight.provenanceLabel}</span>}
          </div>
          {ln.state === 'ready' && (
            <>
              <div className="pick-card-likely-top">
                <span className={`pos ${ln.top.player.position}`}>{ln.top.player.position}</span>
                <strong>{ln.top.player.name}</strong>
                <span className="mono">{pct(ln.top.probability)}</span>
                {ln.wideOpen && <span className="pick-card-tag">Wide open</span>}
              </div>
              {ln.rest.length > 0 && (
                <div className="pick-card-likely-rest muted small">
                  {ln.rest.map((c) => `${c.player.name} ${pct(c.probability)}`).join(' · ')}
                </div>
              )}
            </>
          )}
          {ln.state === 'updating' && <div className="muted small">Updating…</div>}
          {ln.state === 'busy' && <div className="muted small">projection server busy, will retry next pick</div>}
          {ln.state === 'none' && <div className="muted small">{ln.reason}</div>}
        </div>
      )}
      {extras?.scarcityLine != null && <div className="pick-card-scarcity">{extras.scarcityLine}</div>}
    </div>
  )
}
