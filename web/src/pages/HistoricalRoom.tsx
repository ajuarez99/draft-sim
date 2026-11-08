import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  getHistoricalGame,
  historicalAuto,
  historicalPick,
  type HistoricalGameState,
  type HistoricalPlayerCard,
  type PredictedPick,
  type Seat,
  type MockSeat,
} from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import DraftBoard from '../components/DraftBoard'
import TurnIndicator from '../components/TurnIndicator'
import FormatSummary from '../components/FormatSummary'
import DraftRoomLayout from '../components/DraftRoomLayout'
import RoomControls from '../components/RoomControls'
import HistoricalPlayerList from '../components/HistoricalPlayerList'
import HistoricalResults from '../components/HistoricalResults'
import PageHeader from '../components/PageHeader'
import { LoadingScreen } from '../components/Skeleton'
import { cardToPlayerRef, coverageText, historicalSeats, sportOf, statLong } from '../historicalCopy'

/** Same stub the mock room uses: no fitted-history provenance exists for a computer team here. */
function toBoardSeat(s: MockSeat): Seat {
  return {
    slot: s.slot,
    managerId: -s.slot,
    manager: s.manager,
    avatarId: null,
    provenance: 'NEUTRAL',
    reachBias: 0,
    relativeReachBias: null,
    relativeReachStdErr: null,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved: 0,
    picksScored: 0,
  }
}

/** What counts, what does not, and why: the era disclosure (FR-007/FR-008), per sport (spec 056). */
function SeasonDisclosure({ game }: { game: HistoricalGameState }) {
  const s = game.season
  const nfl = sportOf(game) === 'NFL'
  const excluded = Object.entries(s.excludedStats)
  return (
    <section className="panel hist-disclosure" aria-label="What counts this season">
      <div className="hist-disclosure-cols">
        <div>
          <h2>{nfl ? `Scoring in ${s.label}` : `Stats that count in ${s.label}`}</h2>
          <ul className="hist-counted">
            {s.countedStats.map((k) => (
              <li key={k} className="hist-pill" title={nfl ? statLong(k) : coverageText(k, s.statCoverage[k])}>
                {nfl ? statLong(k) : coverageText(k, s.statCoverage[k])}
              </li>
            ))}
          </ul>
          {!nfl && <p className="muted small">A stat missing from a box score scores 0. Bonuses are not shown on player cards.</p>}
        </div>
        {excluded.length > 0 && (
          <div>
            <h2>Not counted</h2>
            <ul className="hist-excluded">
              {excluded.map(([k, why]) => (
                <li key={k}>
                  <strong>{statLong(k)}</strong> <span className="muted">{why}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
      {(s.dataNotes ?? []).length > 0 && (
        <ul className="hist-notes muted small" aria-label="About the data">
          {(s.dataNotes ?? []).map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
      <p className="muted small">
        Board order: {s.rankingLabel}. {s.settingsSource}
      </p>
      {(s.attribution ?? null) && <p className="muted small hist-attribution">{s.attribution}</p>}
    </section>
  )
}

export default function HistoricalRoom() {
  const { gameId = '' } = useParams<{ gameId: string }>()
  const id = Number(gameId)
  const [state, setState] = useState<HistoricalGameState | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  const [busy, setBusy] = useState<'PICK' | 'FINISH' | 'DRAFT' | null>(null)

  useEffect(() => {
    getHistoricalGame(id)
      .then(setState)
      .catch((e) => fail(e))
  }, [id])

  const seats = useMemo(() => (state ? historicalSeats(state) : []), [state])

  // The in-flight guard is a ref: `busy` is state captured in the click closure, so two clicks landing before the
  // re-render would both pass it (review finding 9).
  const inFlight = useRef(false)

  async function run(kind: 'PICK' | 'FINISH' | 'DRAFT', call: () => Promise<HistoricalGameState>) {
    if (!state || busy || inFlight.current) return
    inFlight.current = true
    setBusy(kind)
    setError(null)
    try {
      setState(await call())
    } catch (e) {
      fail(e)
    } finally {
      inFlight.current = false
      setBusy(null)
    }
  }

  if (notFound && !state) return <NotFound what="page" />
  if (error && !state) {
    return (
      <div className="content">
        <div className="error">{error}</div>
      </div>
    )
  }
  if (!state) return <LoadingScreen label="Loading this draft…" />

  const sport = sportOf(state)
  const complete = state.status === 'COMPLETE'
  const drafting = state.status === 'DRAFTING'
  const board: PredictedPick[] = state.picks
    .filter((p): p is typeof p & { player: HistoricalPlayerCard } => p.player != null)
    .map((p) => ({
      pickNo: p.pickNo,
      round: p.round,
      slot: p.draftSlot,
      manager: seats.find((s) => s.slot === p.draftSlot)?.manager ?? String(p.draftSlot),
      avatarId: null,
      player: cardToPlayerRef(p.player, sport),
      probability: 1,
      isModal: true,
      alternatives: [],
    }))
  const userPicks: Record<number, ReturnType<typeof cardToPlayerRef>> = {}
  for (const p of state.picks) {
    if (p.seatType === 'USER' && p.player) userPicks[p.pickNo] = cardToPlayerRef(p.player, sport)
  }
  const nextOwnPick = state.myPicks.find((p) => p > state.currentPickNo) ?? null

  const header = (
    <PageHeader
      eyebrow="Time Machine"
      title={
        complete
          ? `You drafted ${state.season.label}`
          : state.status === 'ABANDONED'
            ? `${state.season.label} (abandoned)`
            : `You're drafting ${state.season.label}`
      }
      sub={
        complete
          ? 'The season is in the books. Here is how your team did.'
          : "You see each player's line from the season before. The real season plays out once the draft is over."
      }
    />
  )

  if (state.status === 'ABANDONED') {
    return (
      <div className="content">
        {header}
        <p className="muted">This game was abandoned.</p>
        <Link to="/history">Back to Time Machine</Link>
      </div>
    )
  }

  const boardNode = (density: 'full' | 'compact') => (
    <DraftBoard
      density={density}
      room="real"
      onTheClockPickNo={drafting ? state.currentPickNo : undefined}
      board={board}
      teams={state.teams}
      rounds={state.rounds}
      myPicks={state.myPicks}
      userPicks={userPicks}
      seats={seats.map(toBoardSeat)}
      mySlot={state.userSlot}
      sport={sport === 'NFL' ? 'nfl' : 'nba'}
      reversalRound={0}
      hideProvenanceDots
      hidePhotos
    />
  )

  if (complete && state.result) {
    return (
      <div className="content">
        {header}
        <SeasonDisclosure game={state} />
        <HistoricalResults game={state} result={state.result} />
        <details className="how">
          <summary>Your draft board</summary>
          {boardNode('full')}
        </details>
        <p>
          <Link to="/history">Back to Time Machine</Link>
        </p>
      </div>
    )
  }

  return (
    <div className="content">
      {header}
      <SeasonDisclosure game={state} />
      <DraftRoomLayout
        rounds={state.rounds}
        status={
          <TurnIndicator
            currentPickNo={state.currentPickNo}
            onTheClockSlot={state.onTheClockSlot}
            isUsersTurn={state.isUsersTurn}
            seats={seats}
            complete={false}
            teams={state.teams}
            rounds={state.rounds}
            nextOwnPick={nextOwnPick}
          >
            <FormatSummary teams={state.teams} rounds={state.rounds} draftType="snake" reversalRound={0} />
          </TurnIndicator>
        }
        notices={error ? <div className="error">{error}</div> : undefined}
        controls={
          <RoomControls
            primary={{
              label: busy === 'FINISH' ? 'Finishing…' : 'Auto-finish the draft',
              onClick: () => void run('FINISH', () => historicalAuto(state.id, 'FINISH')),
              disabled: busy != null,
              title: 'Makes every remaining pick, yours included, and replays the season',
            }}
          >
            {state.isUsersTurn && (
              <button
                type="button"
                className="chip"
                disabled={busy != null}
                onClick={() => void run('PICK', () => historicalAuto(state.id, 'PICK'))}
                title="Makes your pick for you"
              >
                {busy === 'PICK' ? 'Picking…' : 'Auto-pick'}
              </button>
            )}
          </RoomControls>
        }
        board={(density) => boardNode(density)}
        list={
          <HistoricalPlayerList
            players={state.available}
            rankingLabel={state.season.rankingLabel}
            sport={sport}
            canPick={state.isUsersTurn}
            busy={busy != null}
            onPick={(c) => void run('DRAFT', () => historicalPick(state.id, c.id))}
          />
        }
      />
    </div>
  )
}
