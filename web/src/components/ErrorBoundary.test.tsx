import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary'

// React itself logs every caught error, and so does the boundary. Both are
// wanted in the browser and pure noise here.
beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}))
afterEach(() => vi.restoreAllMocks())

function Bomb(): never {
  throw new TypeError("Cannot read properties of undefined (reading 'season')")
}

function App() {
  return (
    <MemoryRouter initialEntries={['/boom']}>
      {/* Outside the boundary, like the real rail inside AppShell: the way
          out has to survive the crash it is offering an escape from. */}
      <Link to="/">rail home</Link>
      <ErrorBoundary>
        <Routes>
          <Route path="/boom" element={<Bomb />} />
          <Route path="/" element={<p>Your leagues</p>} />
        </Routes>
      </ErrorBoundary>
    </MemoryRouter>
  )
}

it('shows the message instead of blanking the app, and prints the error', () => {
  render(<App />)

  expect(screen.getByText('This page hit an error')).toBeTruthy()
  expect(screen.getByText(/reading 'season'/)).toBeTruthy()
  expect(console.error).toHaveBeenCalled()
})

it('clears itself when you navigate away', async () => {
  render(<App />)
  expect(screen.getByText('This page hit an error')).toBeTruthy()

  await userEvent.click(screen.getByText('rail home'))

  // No reload: the boundary resets on the path change, so the next page
  // renders normally rather than staying stuck on the crash from the last one.
  expect(screen.getByText('Your leagues')).toBeTruthy()
  expect(screen.queryByText('This page hit an error')).toBeNull()
})
