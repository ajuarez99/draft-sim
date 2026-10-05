import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './styles.css'

// Until 2026-10-05 commissioner actions asked for the admin token and kept it here.
// Nothing reads it any more; don't leave the operator's secret sitting in storage.
try {
  localStorage.removeItem('bk.commissionerKey.v1')
} catch {
  // Storage blocked -- nothing could have been saved there either.
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
