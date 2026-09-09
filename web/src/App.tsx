import { useState } from 'react'
import { Link, Route, Routes, useLocation, useParams } from 'react-router-dom'
import SignIn from './pages/SignIn'
import DraftPicker from './pages/DraftPicker'
import DraftView from './pages/DraftView'
import CompletedDraftBoard from './pages/CompletedDraftBoard'
import LiveDraftView from './pages/LiveDraftView'
import MockSetup from './pages/MockSetup'
import MockDraftView from './pages/MockDraftView'
import ManagerTendencies from './pages/ManagerTendencies'
import LeagueHistory from './pages/LeagueHistory'
import PowerRankings from './pages/PowerRankings'
import ManagerHistory from './pages/ManagerHistory'
import { TopSlotContext } from './topSlot'
import { clearUser, useUser } from './user'
import { hueFor } from './hue'

// Forces a full remount of DraftView on every draft change. Without this,
// React Router does not remount on a :draftId param change alone -- result/
// userPicks/resimming/requestSeqRef would all persist across a draft switch,
// so a resim left in flight for the old draft could land later and paint its
// board onto the new draft's now-open screen. See
// claude/reactive-resimulation.md §3, "Cross-draft leak".
function KeyedDraftView() {
  const { draftId } = useParams<{ draftId: string }>()
  return <DraftView key={draftId} />
}

// Same remount wrapper, same reason: a param-only change would keep the old
// draft's fetched board/seats alive across the switch.
function KeyedCompletedDraftBoard() {
  const { draftId } = useParams<{ draftId: string }>()
  return <CompletedDraftBoard key={draftId} />
}

// Same remount wrapper, same reason: LiveDraftView holds a simulation in
// flight (requestSeqRef/result) and an EventSource keyed on draftId, and a
// param-only change would keep every one of them alive across the switch --
// an in-flight resim for the old draft could land and paint onto the new one.
function KeyedLiveDraftView() {
  const { draftId } = useParams<{ draftId: string }>()
  return <LiveDraftView key={draftId} />
}

// Same remount wrapper, same reason: a param-only change would keep the old
// session's fetched state and in-flight submitPick call alive across the switch.
function KeyedMockDraftView() {
  const { sessionId } = useParams<{ sessionId: string }>()
  return <MockDraftView key={sessionId} />
}

export default function App() {
  // Ref callback (not useRef) because a plain ref's mutation doesn't trigger
  // a re-render -- DraftView's settings-popover portal needs to know the
  // instant this node exists, not just eventually. See topSlot.tsx.
  const [topSlot, setTopSlot] = useState<HTMLDivElement | null>(null)
  const location = useLocation()
  const user = useUser()

  return (
    <div className="app">
      <header className="top">
        <div className="top-left">
          <h1>
            <Link to="/">Ball Knowers</Link>
          </h1>
          {/* E5 (design-review-next-steps.md): the header carried exactly one
              nav chip and no indication of where you were. Home and Managers
              are the only two destinations with no other route back to them --
              every league-scoped page (draft room, history, power rankings)
              now hangs off a league card per E1, so this deliberately stays a
              two-item nav rather than growing a link per route. `.startsWith`
              on Managers so its own sub-route (/managers/:id/history) still
              marks the chip current, the same way a browser tab would. */}
          <nav className="top-nav" aria-label="Global">
            <Link to="/" className={`chip${location.pathname === '/' ? ' on' : ''}`}>
              Home
            </Link>
            <Link
              to="/managers"
              className={`chip${location.pathname.startsWith('/managers') ? ' on' : ''}`}
            >
              Managers
            </Link>
          </nav>
        </div>
        <div className="top-right">
          <div className="top-slot" ref={setTopSlot} />
          {/* Identity is a header chip, not a route -- claude/user-identity-
              and-onboarding.md §5b. Same color-initials avatar treatment as
              SeatPopover/ManagerHistory rather than Sleeper's own avatar
              image, so it reads as this app's identity language everywhere.
              "Sign out" doubles as "switch user": clearUser() drops straight
              back to SignIn, which is the same screen a switch would need --
              there's nothing server-side to revoke either way (§7). */}
          {user && (
            <div className="top-user">
              <span
                className="avatar"
                style={{
                  background: `oklch(28% 0.03 ${hueFor(user.username)})`,
                  color: `oklch(82% 0.1 ${hueFor(user.username)})`,
                }}
                aria-hidden="true"
              >
                {(user.displayName || user.username).charAt(0).toUpperCase()}
              </span>
              <span className="top-user-name">{user.displayName || user.username}</span>
              <button className="chip" onClick={clearUser}>
                Sign out
              </button>
            </div>
          )}
        </div>
      </header>

      <TopSlotContext.Provider value={topSlot}>
        <Routes>
          <Route path="/" element={user ? <DraftPicker /> : <SignIn />} />
          <Route path="/drafts/:draftId" element={<KeyedDraftView />} />
          <Route path="/drafts/:draftId/board" element={<KeyedCompletedDraftBoard />} />
          <Route path="/drafts/:draftId/live" element={<KeyedLiveDraftView />} />
          <Route path="/mock/new" element={<MockSetup />} />
          <Route path="/mock/:sessionId" element={<KeyedMockDraftView />} />
          <Route path="/managers" element={<ManagerTendencies />} />
          <Route path="/managers/:managerId/history" element={<ManagerHistory />} />
          <Route path="/leagues/:sleeperLeagueId/history" element={<LeagueHistory />} />
          <Route path="/leagues/:sleeperLeagueId/power" element={<PowerRankings />} />
          {/* React Router's own fallback renders a bare "Not Found" with no
              way back -- on a mistyped draft id that was the whole screen. */}
          <Route
            path="*"
            element={
              <div className="content">
                <section className="panel">
                  <h2>Nothing here</h2>
                  <p className="muted">
                    No draft, mock or page at this address. <Link to="/">Back to your leagues</Link>.
                  </p>
                </section>
              </div>
            }
          />
        </Routes>
      </TopSlotContext.Provider>
    </div>
  )
}
