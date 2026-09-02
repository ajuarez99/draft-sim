import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Vitest doesn't put `afterEach` in global scope by default, and RTL's own
// automatic cleanup relies on finding it there -- without this, each test's
// render() accumulates in the same jsdom document instead of resetting.
afterEach(() => {
  cleanup()
})
