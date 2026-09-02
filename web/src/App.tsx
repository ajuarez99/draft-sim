import { useState } from 'react'
import { Link, Route, Routes, useParams } from 'react-router-dom'
import DraftPicker from './pages/DraftPicker'
import DraftView from './pages/DraftView'
import LiveDraftView from './pages/LiveDraftView'
import { TopSlotContext } from './topSlot'

// Reserved for later features, not built here: /mock/new (C, interactive mock
// draft room).

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
        </Routes>
      </TopSlotContext.Provider>
    </div>
  )
}
