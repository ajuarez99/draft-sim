import { Component, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'

/**
 * The app had no boundary at all, so ANY TypeError thrown while rendering a
 * page unmounted the whole tree and left a blank screen -- no rail, no way
 * back, nothing on the page saying what happened. That has now bitten twice:
 * `d.status.replace(...)` on a draft row Sleeper returned without a status
 * (see DraftSummary.status in api.ts), and `data.sportState.season` on the
 * power-rankings page when the deployed backend was older than the deployed
 * frontend and still answered `nflState` (2026-09-15).
 *
 * A crash is still a bug to fix at the source -- this only stops one broken
 * page from taking the site with it, and prints the error where it can be
 * read without opening devtools.
 */
type Props = { pathname: string; children: ReactNode }
type State = { error: Error | null }

class Boundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error) {
    // Still worth a console entry: the on-screen message is deliberately short,
    // and the stack is the part worth pasting into a bug report.
    console.error('Render error caught by ErrorBoundary:', error)
  }

  // Navigating away from the broken page clears it. Deliberately conditional
  // on there being an error -- resetting unconditionally on every path change
  // would be indistinguishable from keying this component on `pathname`, which
  // would remount every healthy page on every navigation too.
  componentDidUpdate(prev: Props) {
    if (prev.pathname !== this.props.pathname && this.state.error) this.setState({ error: null })
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className="content scrolls">
        <section className="panel">
          <div className="panel-head">
            <h2>This page hit an error</h2>
          </div>
          <p className="muted">
            Something in this screen failed to render. The rest of the app still works —{' '}
            <Link to="/">back to your leagues</Link>.
          </p>
          <div className="error">{this.state.error.message}</div>
        </section>
      </div>
    )
  }
}

export default function ErrorBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return <Boundary pathname={pathname}>{children}</Boundary>
}
