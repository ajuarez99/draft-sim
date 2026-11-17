import { useEffect, useRef, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import AccountMenu from './AccountMenu'
import ErrorBoundary from './ErrorBoundary'
import { trackPageView } from '../analytics'
import { brand, homeAria, signInLink, skipLink } from '../redraftCopy'
import { clearUser, useUser } from '../user'

/**
 * Spec 073: the Time Machine site's shell. A slim bar (wordmark, account menu) over one scrolling
 * `<main>`; no league header, no jump-to search, no Ctrl+K, no league list fetch.
 *
 * It renders `.app-shell > header.topbar + main.app-main#main` on purpose: styles.css lays the page out
 * from those classes, and the draft room's height (`calc(100vh - 290px)`) and scroll containment depend
 * on them. Children sit inside an ErrorBoundary, so a page that throws keeps the bar and a way home.
 * Spec 074: it renders the bar and `<main>` for a guest too. A guest sees a "Sign in" link (to the home page's
 * `#sign-in` panel) where a signed-in visitor sees the account menu, and a guest's page views are recorded under
 * their route (analytics.ts) rather than as "Landing".
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

  // C6: a client-side navigation to a new page starts at its top and puts focus on its h1, so a screen-reader or keyboard
  // user lands where a page load would have put them (and "Next season" no longer opens at the bottom of the page).
  // Not on the first render (that is a page load already) and not when the link carried a hash (the page handles it).
  const firstPath = useRef(location.pathname)
  useEffect(() => {
    if (firstPath.current === location.pathname) return
    firstPath.current = location.pathname
    if (location.hash) return
    const main = mainRef.current
    if (!main) return
    main.scrollTop = 0
    if (document.scrollingElement) document.scrollingElement.scrollTop = 0
    const focusH1 = () => {
      const h1 = main.querySelector('h1')
      if (h1) {
        h1.setAttribute('tabindex', '-1')
        h1.focus({ preventScroll: true })
      } else {
        main.focus({ preventScroll: true })
      }
    }
    focusH1()
    // V-02: a page that loads its data after the navigation (a season page) swaps its loading-state h1 for the real one, and
    // the swap drops the focused node, which leaves focus on <body>. For a few seconds, when that happens (and only then: focus
    // on <body> means nobody has moved it), the new h1 takes the focus. A keypress or click means the visitor is driving: stop.
    const watch = new MutationObserver(() => {
      if (document.activeElement === document.body || document.activeElement == null) focusH1()
      else if (!main.contains(document.activeElement) || document.activeElement !== main.querySelector('h1')) stop()
    })
    const stop = () => {
      watch.disconnect()
      window.clearTimeout(timer)
      document.removeEventListener('keydown', stop, true)
      document.removeEventListener('pointerdown', stop, true)
    }
    const timer = window.setTimeout(stop, 5000)
    document.addEventListener('keydown', stop, true)
    document.addEventListener('pointerdown', stop, true)
    watch.observe(main, { childList: true, subtree: true })
    return stop
  }, [location.pathname, location.hash])

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
        {skipLink}
      </a>

      <header className="topbar">
        <Link to="/" className="topbar-wordmark cond" aria-label={homeAria}>
          {brand}
        </Link>
        <div className="topbar-actions">
          {user ? (
            <AccountMenu username={user.username} displayName={user.displayName} avatarId={user.avatar} onSignOut={signOut} />
          ) : (
            <Link className="topbar-signin" to="/#sign-in">
              {signInLink}
            </Link>
          )}
        </div>
      </header>

      <main className="app-main" id="main" tabIndex={-1} ref={mainRef}>
        <ErrorBoundary>{children}</ErrorBoundary>
      </main>
    </div>
  )
}
