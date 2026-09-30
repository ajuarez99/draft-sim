import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import NotFound from './NotFound'
import { ApiError, isNotFound } from '../apiError'

describe('NotFound', () => {
  it('names a league 404 as missing or not yours, with a way home', () => {
    render(
      <MemoryRouter>
        <NotFound what="league" />
      </MemoryRouter>,
    )
    expect(screen.getByText(/No league here, or it isn't one of yours/)).toBeTruthy()
    expect(screen.getByRole('link', { name: /back to your leagues/i })).toBeTruthy()
  })

  it('ApiError keeps its status and never has an empty message', () => {
    expect(isNotFound(new ApiError(404, ''))).toBe(true)
    expect(new ApiError(404, '').message).toBe('HTTP 404')
    expect(isNotFound(new Error('404'))).toBe(false)
  })
})
