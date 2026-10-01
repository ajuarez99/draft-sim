import type { ComponentType } from 'react'
import { Route, Routes, useParams } from 'react-router-dom'
import SignIn from './pages/SignIn'
import DraftPicker from './pages/DraftPicker'
import DraftView from './pages/DraftView'
import CompletedDraftBoard from './pages/CompletedDraftBoard'
import LiveDraftView from './pages/LiveDraftView'
import MockSetup from './pages/MockSetup'
import MockDraftView from './pages/MockDraftView'
import ManagerTendencies from './pages/ManagerTendencies'
import LeagueHome from './pages/LeagueHome'
import LeagueHistory from './pages/LeagueHistory'
import PowerRankings from './pages/PowerRankings'
import LeagueAnalysis from './pages/LeagueAnalysis'
import RosterManagement from './pages/RosterManagement'
import ExpectedWins from './pages/ExpectedWins'
import SeasonForecast from './pages/SeasonForecast'
import WeeklyReport from './pages/WeeklyReport'
import Superlatives from './pages/Superlatives'
import PowerRankingsVerify from './pages/PowerRankings.verify'
import ManagerHistory from './pages/ManagerHistory'
import ManagerComparison from './pages/ManagerComparison'
import AppShell from './components/AppShell'
import NotFound from './components/NotFound'
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

// One generic remount wrapper for every /leagues/:sleeperLeagueId/* page. The
// rail's year links (spec 011) switch seasons on the SAME route --
// /leagues/<2026>/expected-wins -> /leagues/<2025>/expected-wins -- and an
// unkeyed route element is reused across a param-only change: the old season's
// data stays up while the new one loads, an error banner can sit over the other
// season's numbers, and an effect still in flight for the old id (LeagueAnalysis
// has a few) can land late and overwrite the new season. Keying on the id gives
// the same clean slate the draft routes above get.
function KeyedByLeague({ page: Page }: { page: ComponentType }) {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  return <Page key={sleeperLeagueId} />
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
  // Keyed* wrappers above exist to force remounts, and the shell must
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
              {/* specs/006-deeper-history-both-sports US4: manager-scoped, not
                  league-scoped -- addressed by two manager ids, not a league,
                  so it lives beside /managers/:managerId/history rather than in
                  destinations.ts. That registry's own LeagueDestination.href
                  takes a LeagueContext (a lineage + a viewed season); a
                  comparison between two people has neither, and
                  destinations.test.ts's "does not claim routes that belong to
                  no league" already asserts /managers/... routes match no row
                  there (contracts/head-to-head-api.md's own wording talks
                  about destinations.ts, but the registry it names is strictly
                  for pages reached from inside one league -- see that file's
                  own header comment). */}
              <Route path="/managers/:aId/versus/:bId" element={<ManagerComparison />} />
              <Route path="/leagues/:sleeperLeagueId" element={<KeyedByLeague page={LeagueHome} />} />
              <Route path="/leagues/:sleeperLeagueId/history" element={<KeyedByLeague page={LeagueHistory} />} />
              <Route path="/leagues/:sleeperLeagueId/power" element={<KeyedByLeague page={PowerRankings} />} />
              <Route path="/leagues/:sleeperLeagueId/analysis" element={<KeyedByLeague page={LeagueAnalysis} />} />
              <Route
                path="/leagues/:sleeperLeagueId/roster-management"
                element={<KeyedByLeague page={RosterManagement} />}
              />
              <Route
                path="/leagues/:sleeperLeagueId/expected-wins"
                element={<KeyedByLeague page={ExpectedWins} />}
              />
              <Route
                path="/leagues/:sleeperLeagueId/forecast"
                element={<KeyedByLeague page={SeasonForecast} />}
              />
              <Route
                path="/leagues/:sleeperLeagueId/weekly-report"
                element={<KeyedByLeague page={WeeklyReport} />}
              />
              <Route
                path="/leagues/:sleeperLeagueId/superlatives"
                element={<KeyedByLeague page={Superlatives} />}
              />
              {/* power-rankings-reskin.md §7: a self-check harness, not a page
                  real users should ever reach -- dev-only. */}
              {import.meta.env.DEV && (
                <Route path="/leagues/:sleeperLeagueId/power/verify" element={<KeyedByLeague page={PowerRankingsVerify} />} />
              )}
              {/* React Router's own fallback renders a bare "Not Found" with no
                  way back -- on a mistyped draft id that was the whole screen. */}
              <Route path="*" element={<NotFound what="page" />} />
            </Routes>
          </ErrorBoundary>
        ) : (
          <SignIn />
        )}
      </AppShell>
    </div>
  )
}
