import { useState } from 'react'
import { Link, Route, Routes, useParams } from 'react-router-dom'
import DraftPicker from './pages/DraftPicker'
import DraftView from './pages/DraftView'
import LiveDraftView from './pages/LiveDraftView'
import MockSetup from './pages/MockSetup'
import MockDraftView from './pages/MockDraftView'
import { TopSlotContext } from './topSlot'

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

  return (
    <div className="app">
      <header className="top">
        <h1>
          <Link to="/">draft-sim</Link>
        </h1>
        <div className="top-slot" ref={setTopSlot} />
      </header>

      <TopSlotContext.Provider value={topSlot}>
        <Routes>
          <Route path="/" element={<DraftPicker />} />
          <Route path="/drafts/:draftId" element={<KeyedDraftView />} />
          <Route path="/drafts/:draftId/live" element={<KeyedLiveDraftView />} />
          <Route path="/mock/new" element={<MockSetup />} />
          <Route path="/mock/:sessionId" element={<KeyedMockDraftView />} />
        </Routes>
      </TopSlotContext.Provider>
    </div>
  )
}
