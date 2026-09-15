import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Vitest doesn't put `afterEach` in global scope by default, and RTL's own
// automatic cleanup relies on finding it there -- without this, each test's
// render() accumulates in the same jsdom document instead of resetting.
afterEach(() => {
  cleanup()
})

// jsdom implements no layout, so it ships no scrollIntoView at all -- calling
// it throws rather than being a no-op. StartMockModal calls it to keep a
// seeded league visible in its 220px scrolling list. A no-op stub is the
// honest stand-in: there is no scrolling to assert on in jsdom either way, and
// the alternative is a component guarding a browser API that always exists in
// the browser.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}
