import { useEffect } from 'react'
import type { DraftGrades, PlayerRef, PredictedPick } from '../api'
import { ordinal, productionLabel, signedPoints, weekWord } from '../draftGrades'
import { posRank } from '../posRank'
import { roundPickLabel } from '../roundPickLabel'

type Props = {
  pick: PredictedPick
  teams: number
  yourPick?: PlayerRef
  /** A completed draft's grades (spec 018). Only passed by CompletedDraftBoard once they are on and available. */
  grades?: DraftGrades | null
  onClose: () => void
}

export default function PlayerCard({ pick, teams, yourPick, grades, onClose }: Props) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const grade = grades?.available ? grades.picks.find((g) => g.pickNo === pick.pickNo) : undefined
  const unknown = 'unknown'
  const pos = grade?.position ?? null
  const minPicks = grades?.minPicksPerPosition ?? null
  const num = (v: number | null | undefined) => (v != null ? v.toFixed(1) : unknown)
  const nba = grades?.productionBasis === 'WEEKLY_AVERAGE_GAME'
  const forName = pick.manager || 'the drafting team'

  return (
    <div className="modal-backdrop" onClick={onClose}>
      {/* stopPropagation so a click inside the card doesn't bubble to the backdrop and close it */}
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose} aria-label="Close">
          ✕
        </button>

        {/* When present, your actual pick is shown first and distinctly from
            the model's own projection below -- neither one silently
            overwrites the other. */}
        {yourPick && (
          <div className="modal-your-pick">
            <h3 className="section-title tiny muted">You picked</h3>
            <div className="modal-head">
              <span className={`pos ${yourPick.position}`}>{posRank(yourPick)}</span>
              <h2 className="modal-name">{yourPick.name}</h2>
            </div>
            <p className="muted small">{yourPick.team ?? '—'}</p>
          </div>
        )}

        <div className={yourPick ? 'modal-model-pick' : undefined}>
          {yourPick && <h3 className="section-title tiny muted">Model's own pick here</h3>}
          <div className="modal-head">
            <span className={`pos ${pick.player.position}`}>{posRank(pick.player)}</span>
            <h2 className="modal-name">{pick.player.name}</h2>
          </div>
          <p className="muted small">{pick.player.team ?? '—'}</p>
          <p className="muted small">
            Round {roundPickLabel(pick.pickNo, teams)} — pick #{pick.pickNo} — {Math.round(pick.probability * 100)}%
            of runs
            {pick.isModal ? '' : ' (not the most likely player here)'}
          </p>
        </div>

        {grades && grade && (
          <div className="played-out">
            <h3 className="section-title">How he played out</h3>
            <dl>
              <dt>Production</dt>
              <dd>
                {grade.production.toFixed(1)} {productionLabel(grades.productionBasis)}
              </dd>
              <dt>Weeks played</dt>
              <dd>
                {grade.weeksPlayed} of {grades.weeksCounted}
              </dd>
              <dt>Baseline</dt>
              <dd>
                {grade.slotBaseline != null
                  ? `${grade.slotBaseline.toFixed(1)} points, vs. what a ${nba || pos == null ? 'player' : pos} taken at pick ${pick.pickNo} scored in this draft (fitted)`
                  : !nba && pos != null && minPicks != null
                    ? `no baseline: fewer than ${minPicks} ${pos}s drafted`
                    : unknown}
              </dd>
              <dt>Value over slot</dt>
              <dd>{grade.valueOverSlot != null ? `${signedPoints(grade.valueOverSlot)} points` : unknown}</dd>
              <dt>Positional rank</dt>
              <dd>
                {grade.positionDrafted == null || grade.positionFinish == null
                  ? unknown
                  : nba
                    ? `drafted ${ordinal(grade.positionDrafted)}, finished ${ordinal(grade.positionFinish)} among this draft's picks`
                    : pos != null
                      ? `${ordinal(grade.positionDrafted)} ${pos} drafted, finished ${ordinal(grade.positionFinish)} among drafted ${pos}s`
                      : unknown}
              </dd>
              <dt>Counted for {forName}</dt>
              <dd>
                {num(grade.countedForYou)} ({grade.weeksStartedForYou != null ? `${grade.weeksStartedForYou} ${weekWord(grade.weeksStartedForYou)}` : `${unknown} weeks`} started)
              </dd>
              {nba && (
                <>
                  <dt>Credited by Sleeper</dt>
                  <dd>
                    {num(grade.creditedForYou)}{' '}
                    <span className="muted small">a different scale: Sleeper counts one game a week</span>
                  </dd>
                </>
              )}
              {grade.weeksUnknownForYou != null && grade.weeksUnknownForYou > 0 && (
                <>
                  <dt>Unknown</dt>
                  <dd>{grade.weeksUnknownForYou} {weekWord(grade.weeksUnknownForYou)} unknown</dd>
                </>
              )}
            </dl>
          </div>
        )}

        {pick.alternatives.length > 0 && (
          <div className="modal-alts">
            <h3 className="section-title">Alternatives</h3>
            {pick.alternatives.map((a) => (
              <div key={a.player.id} className="modal-alt-row">
                <span className={`pos ${a.player.position}`}>{posRank(a.player)}</span>
                <span className="name">{a.player.name}</span>
                <span className="mono muted">{Math.round(a.probability * 100)}%</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
