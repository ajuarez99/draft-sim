import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  getDraftGrades,
  getRealDraftBoard,
  getSeats,
  type DraftGrades,
  type ProductionBasis,
  type PickGrade,
  type PredictedPick,
  type RealDraftBoard,
  type RealPick,
  type SeatsResponse,
} from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import DraftBoard from '../components/DraftBoard'
import PlayerCard from '../components/PlayerCard'
import { LoadingScreen } from '../components/Skeleton'
import DraftGradeStrip from '../components/DraftGradeStrip'
import { GradesEarlyBadge, gradesEarlySentence } from '../components/GradeChip'
import { hasAnyAdpAtDraft } from '../stealsReaches'
import { legendText, reasonSentence, signedPoints } from '../draftGrades'

/** Exactly one value view at a time: a cell never carries two signed numbers or two tints. */
type ValueMode = 'off' | 'adp' | 'played'

/**
 * The picks that actually happened in this draft -- never runs the engine,
 * never lets you take a pick. DraftView (the simulator) is for a draft that
 * hasn't happened yet, or for asking "what would this room do"; this is for
 * "what did this room do." See LeagueController.realBoard's own comment for
 * why the two had to become separate endpoints.
 */
export default function CompletedDraftBoard() {
  const { draftId = '' } = useParams<{ draftId: string }>()
  const [seats, setSeats] = useState<SeatsResponse | null>(null)
  const [board, setBoard] = useState<RealDraftBoard | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  const [openPick, setOpenPick] = useState<PredictedPick | null>(null)
  const [mode, setMode] = useState<ValueMode>('off')
  const [grades, setGrades] = useState<DraftGrades | null>(null)
  const [gradesFailed, setGradesFailed] = useState(false)
  // Grades are fetched on first selection and then kept: switching views back
  // and forth never refetches.
  const gradesRequested = useRef(false)

  useEffect(() => {
    setSeats(null)
    setBoard(null)
    setError(null)
    setMode('off')
    setGrades(null)
    setGradesFailed(false)
    gradesRequested.current = false
    Promise.all([getSeats(draftId), getRealDraftBoard(draftId)])
      .then(([s, b]) => {
        setSeats(s)
        setBoard(b)
      })
      .catch((e) => fail(e))
  }, [draftId])

  function choose(next: ValueMode) {
    setMode(next)
    if (next === 'played' && !gradesRequested.current) {
      gradesRequested.current = true
      setGradesFailed(false)
      getDraftGrades(draftId)
        .then(setGrades)
        .catch(() => {
          gradesRequested.current = false
          setGradesFailed(true)
        })
    }
  }

  if (notFound) return <NotFound what="draft" />
  if (error) {
    return (
      <div className="content">
        <div className="error">{error}</div>
      </div>
    )
  }
  if (!seats || !board) {
    return <LoadingScreen label="Loading this draft's board…" />
  }

  // probability:1/isModal:true is what makes DraftBoard render every cell as a
  // pick that actually happened rather than a guess -- same convention
  // MockDraftView uses for its own committed picks.
  const picks: PredictedPick[] = board.picks.map((p) => ({
    pickNo: p.pickNo,
    round: p.round,
    slot: p.slot,
    manager: p.manager,
    avatarId: p.avatarId,
    player: p.player,
    probability: 1,
    isModal: true,
    alternatives: [],
  }))

  // pickNo -> ADP when the pick was made. Only this, never player.adp (today's).
  const adpAtDraft: Record<number, number | null | undefined> = {}
  for (const p of board.picks) adpAtDraft[p.pickNo] = p.adpAtDraft
  const canShowValue = hasAnyAdpAtDraft(board.picks.map((p) => p.adpAtDraft))

  const gradesOffered = board.status === 'complete'
  const showAdp = canShowValue && mode === 'adp'
  const showPlayed = gradesOffered && mode === 'played'
  const gradesByPick: Record<number, PickGrade> | undefined =
    showPlayed && grades?.available ? Object.fromEntries(grades.picks.map((g) => [g.pickNo, g])) : undefined

  const myPicks = seats.mySlot != null ? picks.filter((p) => p.slot === seats.mySlot).map((p) => p.pickNo) : []

  return (
    <div className="content">
      {/* No `PageHeader` here, deliberately -- claude/site-wide-shell-
          propagation.md Phase 4 applies to content-shaped pages, and this is
          board-first: `.board-panel` is `flex: 1 1 0` and the grid takes the
          whole pane. An eyebrow, a 28px title and a sub would cost the board
          about 70px of height, which is the same trade §B already refused
          when it gave draft rooms a 56px rail instead of a 200px one. The
          panel head names the page, and the rail names the league. Same
          reasoning in DraftView, LiveDraftView and MockDraftView. */}
      <div className="board-panel">
        <section className="panel">
          <div className="panel-head">
            <h2>Draft board</h2>
            <span className="muted small">What actually happened</span>
            {/* Hidden outright when no pick carries a draft-time ADP: a toggle
                that can only ever say "no ADP" on every cell is not a feature.
                A finished draft gets the exclusive control (spec 018 F8); an
                unfinished one keeps the lone ADP chip, since there is nothing
                to grade yet. */}
            {gradesOffered && canShowValue && (
              <div className="segmented sm" role="group" aria-label="Value view">
                {(
                  [
                    ['off', 'Off'],
                    ['adp', 'Steals & reaches'],
                    ['played', 'How it played out'],
                  ] as const
                ).map(([m, label]) => (
                  <button
                    key={m}
                    type="button"
                    className={`segment${mode === m ? ' on' : ''}`}
                    aria-pressed={mode === m}
                    onClick={() => choose(m)}
                  >
                    {label}
                  </button>
                ))}
              </div>
            )}
            {gradesOffered && !canShowValue && (
              <button
                type="button"
                className={`chip${mode === 'played' ? ' on' : ''}`}
                aria-pressed={mode === 'played'}
                onClick={() => choose(mode === 'played' ? 'off' : 'played')}
              >
                How it played out
              </button>
            )}
            {!gradesOffered && canShowValue && (
              <button
                type="button"
                className={`chip${mode === 'adp' ? ' on' : ''}`}
                aria-pressed={mode === 'adp'}
                onClick={() => choose(mode === 'adp' ? 'off' : 'adp')}
              >
                Steals &amp; reaches
              </button>
            )}
          </div>
          {showAdp && (
            <p className="muted small value-legend">
              Each pick against his ADP when it was made.{' '}
              <span className="value-delta steal">+</span> means taken after his ADP (a steal),{' '}
              <span className="value-delta reach">&minus;</span> means taken before it (a reach), counted in picks.
            </p>
          )}
          {showPlayed && !grades && !gradesFailed && <p className="muted small grades-legend">Loading grades…</p>}
          {showPlayed && gradesFailed && <p className="muted small grades-legend">
              Couldn't load grades.{' '}
              <button type="button" className="chip" onClick={() => choose('played')}>Try again</button>
            </p>}
          {showPlayed && grades && !grades.available && (
            <p className="muted small grades-legend">{reasonSentence(grades.reason, grades.weeksMissingGameData)}</p>
          )}
          {showPlayed && grades?.available && (
            <>
              <p className="muted small grades-legend">
                Each pick's points against what {atPick(grades.productionBasis)} scored in this draft
                (a fitted baseline), in points.{' '}
                <span className="value-delta steal">+</span> means he outproduced them,{' '}
                <span className="value-delta reach">&minus;</span> means he fell short. {legendText(grades)}{' '}
                <GradesEarlyBadge early={grades.gradesEarly} />
                {grades.gradesEarly && <span className="muted"> {gradesEarlySentence(grades.earlyThresholdWeeks)}</span>}
              </p>
              <DraftGradeStrip teams={grades.teams} picks={grades.picks} />
              <StealsAndBusts grades={grades} board={board.picks} />
            </>
          )}
          <div className="board-stage">
            <DraftBoard
              board={picks}
              teams={board.teams}
              rounds={board.rounds}
              myPicks={myPicks}
              userPicks={{}}
              seats={seats.seats}
              mySlot={seats.mySlot ?? undefined}
              sport={seats.sport}
              reversalRound={seats.reversalRound}
              onCellClick={setOpenPick}
              valueView={showAdp}
              adpAtDraft={adpAtDraft}
              grades={gradesByPick}
              gradesBasis={grades?.productionBasis}
            />
          </div>
        </section>
      </div>

      {openPick && <PlayerCard pick={openPick} teams={board.teams} grades={showPlayed ? grades : null} onClose={() => setOpenPick(null)} />}
    </div>
  )
}

/** The comparison group in prose. Basketball fits one curve over the whole draft; football fits per position. */
function atPick(basis: ProductionBasis): string {
  return basis === 'WEEKLY_AVERAGE_GAME' ? 'players taken at that pick' : 'players at his position taken at that pick'
}

/** One row of a steals/busts list: the board's pick data names him, PickGrade.playerName is the fallback. */
function ValueRow({ pickNo, grades, board }: { pickNo: number; grades: DraftGrades; board: RealPick[] }) {
  const g = grades.picks.find((x) => x.pickNo === pickNo)
  const real = board.find((x) => x.pickNo === pickNo)
  const name = real?.player.name ?? g?.playerName ?? `Pick ${pickNo}`
  const manager = real?.manager ?? grades.teams.find((t) => t.slot === g?.slot)?.manager ?? null
  const round = g?.round ?? real?.round
  const value = g?.valueOverSlot
  return (
    <li className="sb-row">
      <span className="sb-main">
        <span className="sb-name">{name}</span>
        <span className="muted small">
          {round != null ? `Round ${round}, ` : ''}pick {pickNo}
          {manager ? ` · ${manager}` : ''}
        </span>
      </span>
      <span className={`value-delta ${value == null ? '' : value >= 0 ? 'steal' : 'reach'} mono`}>
        {value != null ? `${signedPoints(value)} pts` : 'unknown'}
      </span>
    </li>
  )
}

/**
 * Spec 018 US4. The value is the same number the cells show: points against
 * players at his position drafted around that pick, so a steal is a pick who
 * beat that, not a pick who was cheap.
 */
function StealsAndBusts({ grades, board }: { grades: DraftGrades; board: RealPick[] }) {
  if (grades.steals.length === 0 && grades.busts.length === 0) return null
  return (
    <section className="steals-busts" aria-label="Steals and busts">
      <h3 className="section-title">Steals &amp; busts</h3>
      <p className="muted small">Value is points vs. what {atPick(grades.productionBasis)} scored in this draft (fitted).</p>
      <div className="sb-cols">
        <div className="sb-col sb-steals">
          <h4 className="tiny muted">Steals</h4>
          <ol>
            {grades.steals.map((n) => (
              <ValueRow key={n} pickNo={n} grades={grades} board={board} />
            ))}
          </ol>
        </div>
        <div className="sb-col sb-busts">
          <h4 className="tiny muted">Busts</h4>
          <ol>
            {grades.busts.map((n) => (
              <ValueRow key={n} pickNo={n} grades={grades} board={board} />
            ))}
          </ol>
        </div>
      </div>
    </section>
  )
}
