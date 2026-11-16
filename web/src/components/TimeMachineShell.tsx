import { useEffect, useRef, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import AccountMenu from './AccountMenu'
import ErrorBoundary from './ErrorBoundary'
import { trackPageView } from '../analytics'
import { clearUser, useUser } from '../user'

/**
 * Spec 073: the Time Machine site's shell. A slim bar (wordmark, account menu) over one scrolling
 * `<main>`; no league header, no jump-to search, no Ctrl+K, no league list fetch.
 *
 * It renders `.app-shell > header.topbar + main.app-main#main` on purpose: styles.css lays the page out
 * from those classes, and the draft room's height (`calc(100vh - 290px)`) and scroll containment depend
 * on them. Children sit inside an ErrorBoundary, so a page that throws keeps the bar and a way home.
 * Signed out it renders only its children (the front door), like AppShell.
 *
 * Like AppShell it sits OUTSIDE the inner <Routes> so it is not remounted by the Keyed* wrappers, and it
 * sends one page view per path / signed-in change. The redirect routes in App.tsx are outside this
 * component on purpose: a redirecting path must send nothing.
 */
export default function TimeMachineShell({ children }: { children: ReactNode }) {
  const user = useUser()
  const location = useLocation()
  const navigate = useNavigate()
  const mainRef = useRef<HTMLElement>(null)

  const signedIn = user != null
  useEffect(() => {
    trackPageView(location.pathname, signedIn)
  }, [signedIn, location.pathname])

  if (!user) return <>{children}</>

  function signOut() {
    clearUser()
    navigate('/', { replace: true })
  }

  return (
    <div className="app-shell">
      {/* First focusable element. Focus is moved by hand: a bare #main link would add a hash to the URL under the router. */}
      <a
        href="#main"
        className="skip-link"
        onClick={(e) => {
          e.preventDefault()
          mainRef.current?.focus()
        }}
      >
        Skip to content
      </a>

      <header className="topbar">
        <Link to="/" className="topbar-wordmark cond" aria-label="Ball Knowers Time Machine, home">
          Ball Knowers
        </Link>
        <div className="topbar-actions">
          <AccountMenu username={user.username} displayName={user.displayName} avatarId={user.avatar} onSignOut={signOut} />
        </div>
      </header>

      <main className="app-main" id="main" tabIndex={-1} ref={mainRef}>
        <ErrorBoundary>{children}</ErrorBoundary>
      </main>
    </div>
  )
}
