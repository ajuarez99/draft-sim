import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  autoMock,
  getMockSession,
  submitMockPick,
  type MockSeat,
  type MockSessionState,
  type PlayerRef,
  type PredictedPick,
  type Seat,
} from '../api'
import { useFailure } from '../useFailure'
import { isNotFound } from '../apiError'
import { useTargets } from '../useTargets'
import { markTaken } from '../targets'
import TargetStrip from '../components/TargetStrip'
import RoomControls from '../components/RoomControls'
import NotFound from '../components/NotFound'
import DraftBoard from '../components/DraftBoard'
import TurnIndicator from '../components/TurnIndicator'
import OnTheClockPickInput from '../components/OnTheClockPickInput'
import FormatSummary from '../components/FormatSummary'
import CompactRow from '../components/CompactRow'
import DraftRoomLayout from '../components/DraftRoomLayout'
import AvailabilityPanel from '../components/AvailabilityPanel'
import { computeTeamNeeds } from '../teamNeeds'
import { LoadingScreen } from '../components/Skeleton'
import { useRailLeagueHint } from '../appSlots'

/**
 * Always reports NEUTRAL/zeroed behaviour, even for a MANAGER-type seat
 * (managerSeats lets /mock/new assign a real manager -- see MockSetup.tsx).
 * Harmless today only because DraftBoard is rendered here with
 * hideProvenanceDots and this view never opens a seat popover, so nothing
 * reads these fields -- MockSessionState.SeatView doesn't carry the real
 * reachBias/unpredictability/positionalTilt to begin with. If a click-to-
 * inspect popover is ever added to the mock board, this needs real profile
 * data threaded through, not this stub.
 */
function toBoardSeat(s: MockSeat): Seat {
  return {
    slot: s.slot,
    managerId: s.managerId ?? -s.slot, // synthetic per-slot id so bot headers still get distinct colors
    manager: s.manager,
    avatarId: s.avatarId,
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

export default function MockDraftView() {
  const { sessionId = '' } = useParams<{ sessionId: string }>()
  const [state, setState] = useState<MockSessionState | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  const [submitting, setSubmitting] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  // Auto-pick / auto-finish (spec 024 US5). `autoBusy` names the request in flight; a 404 from a
  // backend that predates the endpoint hides both controls rather than offering dead buttons.
  const [autoBusy, setAutoBusy] = useState<'PICK' | 'FINISH' | null>(null)
  const [autoAvailable, setAutoAvailable] = useState(true)
  const targets = useTargets(useMemo(() => ({ mockSessionId: Number(sessionId) }), [sessionId]))

  // A mock seeded from a league is exactly where you want that league's real
  // room one click away -- but nothing in `/mock/:id` names the league, so the
  // page hands it to the rail rather than the rail reading it off the path.
  // Undefined (an older backend that predates V16) publishes nothing, which
  // shows no League section rather than throwing.
  useRailLeagueHint(state?.sourceSleeperLeagueId)

  useEffect(() => {
    getMockSession(Number(sessionId))
      .then(setState)
      .catch((e) => fail(e))
  }, [sessionId])

  // One shared busy flag: a manual pick and an auto request must never overlap, or the response
  // that lands last would overwrite the board with an older state.
  const busy = submitting || autoBusy != null

  async function pick(player: PlayerRef) {
    if (!state || busy) return
    setSubmitting(true)
    setError(null)
    try {
      const next = await submitMockPick(state.id, player.sleeperId)
      setState(next)
      setPickerOpen(false)
    } catch (e) {
      fail(e)
    } finally {
      setSubmitting(false)
    }
  }

  async function auto(scope: 'PICK' | 'FINISH') {
    if (!state || busy) return
    setAutoBusy(scope)
    setError(null)
    try {
      setState(await autoMock(state.id, scope))
    } catch (e) {
      if (isNotFound(e)) setAutoAvailable(false)
      else fail(e)
    } finally {
      setAutoBusy(null)
    }
  }

  if (notFound && !state) return <NotFound what="mock" />
  if (error && !state) {
    return (
      <div className="content">
        <div className="error">{error}</div>
      </div>
    )
  }
  if (!state) {
    // Was `<div className="content" />` -- a blank screen for the whole fetch,
    // indistinguishable from a route that had crashed.
    return <LoadingScreen label="Loading this mock draft…" />
  }

  // Every committed pick is real, not a probability -- probability:1/isModal:true
  // is what makes DraftBoard render it exactly like a pick you actually made
  // (its "chosen"/predicted precedence collapses to the same thing either way).
  const board: PredictedPick[] = state.picks
    .filter((p): p is typeof p & { player: PlayerRef } => p.player != null)
    .map((p) => ({
      pickNo: p.pickNo,
      round: p.round,
      slot: p.draftSlot,
      manager: state.seats.find((s) => s.slot === p.draftSlot)?.manager ?? String(p.draftSlot),
      avatarId: state.seats.find((s) => s.slot === p.draftSlot)?.avatarId ?? null,
      player: p.player,
      probability: 1,
      isModal: true,
      alternatives: [],
    }))

  const userPicks: Record<number, PlayerRef> = {}
  for (const p of state.picks) {
    // AUTO is the user's own seat decided by auto-pick (MockSessionState.PickView.source),
    // so it is still "your pick" for the board, the roster strip and the picker's fit.
    if ((p.source === 'USER' || p.source === 'AUTO') && p.player) userPicks[p.pickNo] = p.player
  }
  const autoPickNos = new Set(state.picks.filter((p) => p.source === 'AUTO').map((p) => p.pickNo))
  const feedPicks = board.map((p) => ({ ...p, auto: autoPickNos.has(p.pickNo) }))
  const takenIds = new Set(state.picks.flatMap((p) => (p.player ? [p.player.id] : [])))
  const targetIds = new Set(targets.items.map((t) => t.sleeperId))
  const markedTargets = markTaken(targets.items, takenIds)
  const draftedPlayers = state.picks.flatMap((p) => (p.player ? [p.player] : []))

  const complete = state.status === 'COMPLETE'
  const round = state.onTheClockSlot != null ? Math.ceil(state.currentPickNo / state.teams) : state.rounds
  const draftedByUser = Object.values(userPicks)
  // Your roster for the compact row.
  const myPlayers = draftedByUser
  const myNeeds = computeTeamNeeds(state.sport, state.rosterPositions, myPlayers)
  // Every pick in a mock session is already committed -- there is no reveal
  // cutoff to respect here the way DraftView has one, so `board` is the feed.
  const nextOwnPick = state.myPicks.find((p) => p > state.currentPickNo) ?? null

  return (
    <div className="content">
      <DraftRoomLayout
        rounds={state.rounds}
        status={
          // One line: the fork note rides inline in the status row (short, muted, ellipsized).
          <TurnIndicator
            currentPickNo={state.currentPickNo}
            onTheClockSlot={state.onTheClockSlot}
            isUsersTurn={state.isUsersTurn}
            seats={state.seats}
            complete={complete}
            teams={state.teams}
            rounds={state.rounds}
            nextOwnPick={nextOwnPick}
          >
            {state.sourceDraftId != null && (
              <span
                className="muted small status-note"
                title={`Forked from a live draft, continuing from pick ${state.forkedAtPickNo}.`}
              >
                Forked from live, from pick {state.forkedAtPickNo}
              </span>
            )}
            <FormatSummary teams={state.teams} rounds={state.rounds} draftType="snake" reversalRound={state.reversalRound} />
          </TurnIndicator>
        }
        notices={error ? <div className="error">{error}</div> : undefined}
        controls={
          // Rendered on your turn even when the auto endpoints are unavailable: "Pick from
          // full list" lives here now instead of in its own prompt region.
          autoAvailable || (state.isUsersTurn && !complete) ? (
            <RoomControls
              primary={
                autoAvailable
                  ? {
                      label: autoBusy === 'FINISH' ? 'Finishing…' : 'Auto-finish the draft',
                      onClick: () => void auto('FINISH'),
                      disabled: complete || busy,
                      title: complete
                        ? 'The draft is already complete'
                        : 'Makes every remaining pick, yours included, and goes straight to the finished board',
                    }
                  : undefined
              }
            >
              {state.isUsersTurn && !complete && (
                <button
                  type="button"
                  className="chip"
                  onClick={() => setPickerOpen(true)}
                  disabled={busy}
                  title="Choose from the full list of available players"
                >
                  {submitting ? 'Submitting…' : 'Pick from full list'}
                </button>
              )}
              {autoAvailable && state.isUsersTurn && !complete && (
                <button
                  type="button"
                  className="chip"
                  disabled={busy}
                  onClick={() => void auto('PICK')}
                  title="Takes your top target, else the best player who would start for you"
                >
                  {autoBusy === 'PICK' ? 'Picking…' : 'Auto-pick'}
                </button>
              )}
            </RoomControls>
          ) : undefined
        }
        compactRow={
          // The session's own sport (claude/nba-mock-drafts.md). These components were
          // already sport-keyed -- position filters, name abbreviation, pick-run
          // detection -- and were being handed a hardcoded 'nfl' only because
          // MockSessionState had no sport field to read. It does now.
          <CompactRow
            feedPicks={feedPicks}
            teams={state.teams}
            sport={state.sport}
            needs={myNeeds.length > 0 ? myNeeds : undefined}
          />
        }
        targets={
          <TargetStrip
            items={markedTargets}
            status={targets.status}
            error={targets.error}
            sport={state.sport}
            room="mock"
            onMove={targets.move}
            onRemove={targets.remove}
            onRetry={targets.retry}
          />
        }
        board={(density) => (
          <DraftBoard
            density={density}
            room="real"
            onTheClockPickNo={complete ? undefined : state.currentPickNo}
            board={board}
            teams={state.teams}
            rounds={state.rounds}
            myPicks={state.myPicks}
            userPicks={userPicks}
            seats={state.seats.map(toBoardSeat)}
            mySlot={state.userSlot}
            sport={state.sport}
            reversalRound={state.reversalRound}
            hideProvenanceDots
          />
        )}
        list={
          // Always mounted (spec 024 FR-003): a mock's idle state is only ever
          // your turn or complete, since the server plays the bots' picks inside
          // the request that returns the state. On complete the region says so
          // instead of vanishing. Mock drafts run no simulation, so there are no
          // survival numbers, and the panel says that instead of leaving a gap;
          // the picker stays reachable as the full list.
          complete ? (
            <section className="panel avail-region">
              <p className="muted">Draft complete</p>
            </section>
          ) : (
            <AvailabilityPanel
              players={state.available}
              noAvailabilityReason="Availability needs a simulation, which mock drafts don't run."
              recentPicks={board.map((p) => p.player)}
              myPicks={state.myPicks.filter((p) => p >= state.currentPickNo)}
              teams={state.teams}
              started
              sport={state.sport}
              // Spec 023 US3: the league the mock borrowed its settings from, for the Stats
              // view. Null for a mock started with no league, which the panel explains.
              sleeperLeagueId={state.sourceSleeperLeagueId ?? null}
              draftedPlayers={draftedPlayers}
              pickedPlayerIds={takenIds}
              {...(targets.status === 'ready'
                ? { targetIds: targetIds, onAddTarget: targets.add, onRemoveTarget: targets.remove }
                : {})}
            />
          )
        }
      />

      {pickerOpen && (
        <OnTheClockPickInput
          pickNo={state.currentPickNo}
          round={round}
          available={state.available}
          rosterPositions={state.rosterPositions}
          draftedPlayers={draftedByUser}
          sport={state.sport}
          onPick={pick}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </div>
  )
}
