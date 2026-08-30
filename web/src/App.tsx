import { Link, Route, Routes } from 'react-router-dom'
import DraftPicker from './pages/DraftPicker'
import DraftView from './pages/DraftView'

// Reserved for later features, not built here: /mock/new (C, interactive mock
// draft room) and /drafts/:draftId/live (D, live Sleeper polling frontend).

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
        <Route path="/drafts/:draftId" element={<DraftView />} />
      </Routes>
    </div>
  )
}
