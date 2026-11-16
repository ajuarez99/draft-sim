import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './styles.css'
import { initAnalytics } from './analytics'
import { acceptCarry } from './siteCarry'
import { currentSite } from './siteEnv'

// Until 2026-10-05 commissioner actions asked for the admin token and kept it here.
// Nothing reads it any more; don't leave the operator's secret sitting in storage.
try {
  localStorage.removeItem('bk.commissionerKey.v1')
} catch {
  // Storage blocked -- nothing could have been saved there either.
}

// Spec 073 FR-020: the league site takes saved stat-calculator formulas forwarded from the old main address
// (a fragment on the URL), before anything renders. Never throws; always strips the fragment.
if (currentSite().site === 'leagues') {
  let storage: Pick<Storage, 'getItem' | 'setItem'> = { getItem: () => null, setItem: () => {} }
  try {
    storage = localStorage
  } catch {
    // Storage blocked: the fragment is still stripped, nothing is saved.
  }
  acceptCarry(window.location, window.history, storage)
}

// Spec 052: a no-op unless this build has a website id and the page is on the production host.
initAnalytics()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
