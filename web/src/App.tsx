import { Link, Route, Routes, useParams } from 'react-router-dom'
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
import LeagueAnalysis from './pages/LeagueAnalysis'
import RosterManagement from './pages/RosterManagement'
import ExpectedWins from './pages/ExpectedWins'
import PowerRankingsVerify from './pages/PowerRankings.verify'
import ManagerHistory from './pages/ManagerHistory'
import AppShell from './components/AppShell'
import ErrorBoundary from './components/ErrorBoundary'
import { useUser } from './user'

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
  const user = useUser()

  // One shell for the whole site. The horizontal `.top` header this replaced
  // was suppressed on "/" only (`0c6f0ab`), which left the app with two
  // different chromes split one route wide -- see
  // claude/site-wide-shell-propagation.md §A. AppShell renders nothing but
  // its children while signed out, so SignIn is still the whole screen.
  //
  // AppShell wraps `<Routes>` rather than sitting inside a route element: the
  // four Keyed* wrappers below exist to force remounts, and the shell must
  // not be caught up in them.
  return (
    <div className="app">
      <AppShell>
        {user ? (
          // Inside AppShell, outside <Routes>: a page that throws while
          // rendering keeps the rail and the league switcher on screen, so
          // there is still a way out. Before this, one TypeError blanked the
          // entire document.
          <ErrorBoundary>
            <Routes>
              <Route path="/" element={<DraftPicker />} />
              <Route path="/drafts/:draftId" element={<KeyedDraftView />} />
              <Route path="/drafts/:draftId/board" element={<KeyedCompletedDraftBoard />} />
              <Route path="/drafts/:draftId/live" element={<KeyedLiveDraftView />} />
              <Route path="/mock/new" element={<MockSetup />} />
              <Route path="/mock/:sessionId" element={<KeyedMockDraftView />} />
              <Route path="/managers" element={<ManagerTendencies />} />
              <Route path="/managers/:managerId/history" element={<ManagerHistory />} />
              <Route path="/leagues/:sleeperLeagueId/history" element={<LeagueHistory />} />
              <Route path="/leagues/:sleeperLeagueId/power" element={<PowerRankings />} />
              <Route path="/leagues/:sleeperLeagueId/analysis" element={<LeagueAnalysis />} />
              <Route
                path="/leagues/:sleeperLeagueId/roster-management"
                element={<RosterManagement />}
              />
              <Route
                path="/leagues/:sleeperLeagueId/expected-wins"
                element={<ExpectedWins />}
              />
              {/* power-rankings-reskin.md §7: a self-check harness, not a page
                  real users should ever reach -- dev-only. */}
              {import.meta.env.DEV && (
                <Route path="/leagues/:sleeperLeagueId/power/verify" element={<PowerRankingsVerify />} />
              )}
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
          </ErrorBoundary>
        ) : (
          <SignIn />
        )}
      </AppShell>
    </div>
  )
}
