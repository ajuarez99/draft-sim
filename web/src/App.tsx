import { Link, Route, Routes, useParams } from 'react-router-dom'
import DraftPicker from './pages/DraftPicker'
import DraftView from './pages/DraftView'

// Reserved for later features, not built here: /mock/new (C, interactive mock
// draft room) and /drafts/:draftId/live (D, live Sleeper polling frontend).

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

export default function App() {
  return (
    <div className="app">
      <header className="top">
        <h1>
          <Link to="/">draft-sim</Link>
        </h1>
      </header>

      <Routes>
        <Route path="/" element={<DraftPicker />} />
        <Route path="/drafts/:draftId" element={<KeyedDraftView />} />
      </Routes>
    </div>
  )
}
