import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act } from 'react'
import DraftRoomLayout from './DraftRoomLayout'

// Stubbed per test: jsdom has no layout, so the breakpoint is the only thing to drive.
const narrow = vi.hoisted(() => ({ value: false }))
vi.mock('../useNarrow', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../useNarrow')>()),
  useNarrow: () => narrow.value,
}))

beforeEach(() => {
  narrow.value = false
  window.localStorage.clear()
})

function room(over: Partial<React.ComponentProps<typeof DraftRoomLayout>> = {}) {
  return (
    <DraftRoomLayout
      rounds={7}
      status={<div>S</div>}
      notices={<div>N</div>}
      controls={<div>C</div>}
      prompt={<div>P</div>}
      compactRow={<div>R</div>}
      board={<div>B</div>}
      targets={<div>T</div>}
      list={<div>L</div>}
      {...over}
    />
  )
}

describe('DraftRoomLayout', () => {
  it('renders the regions in a fixed order, with the divider between board and targets', () => {
    const { container } = render(room())
    const order = [...container.querySelectorAll('[data-region], [role="separator"]')].map(
      (el) => el.getAttribute('data-region') ?? 'divider',
    )
    expect(order).toEqual(['status', 'notices', 'controls', 'prompt', 'compactRow', 'board', 'divider', 'targets', 'list'])
  })

  it('leaves out an optional region that is not given, but still shows the required ones', () => {
    const { container } = render(room({ notices: undefined, prompt: undefined, compactRow: undefined, targets: undefined }))
    const regions = [...container.querySelectorAll('[data-region]')].map((el) => el.getAttribute('data-region'))
    expect(regions).toEqual(['status', 'controls', 'board', 'list'])
  })

  it('renders absence content in a region instead of nothing', () => {
    render(room({ list: <p>Draft complete</p>, boardOverlay: <p>Waiting for the draft to start</p> }))
    expect(screen.getByText('Draft complete')).toBeInTheDocument()
    expect(screen.getByText('Waiting for the draft to start')).toBeInTheDocument()
  })

  it('hands the board its density, full until the room has been measured', () => {
    const seen: string[] = []
    render(
      room({
        board: (d) => {
          seen.push(d)
          return <div>B</div>
        },
      }),
    )
    expect(seen[seen.length - 1]).toBe('full')
  })

  it('keeps the board in a non-scrolling relative wrapper so the pick card anchors to it', () => {
    const { container } = render(room({ boardPreStart: true }))
    const wrapper = container.querySelector('[data-region="board"]')!
    expect(wrapper.className).toContain('room-board')
    expect(wrapper.className).toContain('pre-start')
    expect(wrapper).toHaveTextContent('B')
  })

  describe('below 1280px', () => {
    beforeEach(() => {
      narrow.value = true
    })

    it('has no divider and one Board | Players control', () => {
      render(room())
      expect(screen.queryByRole('separator')).toBeNull()
      expect(screen.getByRole('button', { name: 'Board' })).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByRole('button', { name: 'Players' })).toHaveAttribute('aria-pressed', 'false')
    })

    it('shows one of board or list at a time and keeps every other region visible', () => {
      const { container } = render(room())
      const board = container.querySelector('[data-region="board"]')!
      const list = container.querySelector('[data-region="list"]')!
      // CSS is not loaded in jsdom, so the hiding is asserted by its class.
      expect(board.className).not.toContain('room-pane-hidden')
      expect(list.className).toContain('room-pane-off')

      fireEvent.click(screen.getByRole('button', { name: 'Players' }))
      expect(board.className).toContain('room-pane-hidden')
      expect(list.className).not.toContain('room-pane-off')

      for (const t of ['S', 'N', 'C', 'P', 'R', 'T']) expect(screen.getByText(t)).toBeInTheDocument()
    })

    it('keeps the board mounted on the Players tab so a pick card inside it survives', () => {
      render(room({ board: <div>board with a card</div> }))
      fireEvent.click(screen.getByRole('button', { name: 'Players' }))
      expect(screen.getByText('board with a card')).toBeInTheDocument()
    })
  })

  describe('measuring the space for the split', () => {
    const roMocks: Array<() => void> = []
    const heights = { room: 800, prompt: 0 }
    const origObserver = globalThis.ResizeObserver
    const origOffset = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
    const origClient = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight')

    beforeEach(() => {
      heights.room = 800
      heights.prompt = 0
      roMocks.length = 0
      globalThis.ResizeObserver = class {
        constructor(cb: () => void) {
          roMocks.push(cb)
        }
        observe() {}
        unobserve() {}
        disconnect() {}
      } as unknown as typeof ResizeObserver
      Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
        configurable: true,
        get(this: HTMLElement) {
          const r = this.getAttribute('data-region')
          if (r === 'status') return 40
          if (r === 'prompt') return heights.prompt
          return 0
        },
      })
      Object.defineProperty(Element.prototype, 'clientHeight', {
        configurable: true,
        get(this: HTMLElement) {
          return this.classList.contains('room') ? heights.room : 0
        },
      })
    })
    afterEach(() => {
      globalThis.ResizeObserver = origObserver
      if (origOffset) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', origOffset)
      if (origClient) Object.defineProperty(Element.prototype, 'clientHeight', origClient)
    })

    it('re-measures when the fixed regions above the split grow after mount', () => {
      const { container } = render(room({ notices: undefined, controls: undefined, compactRow: undefined, targets: undefined }))
      const sep = container.querySelector('[role="separator"]')!
      // 800 room - 40 status - 8 divider = 752 shared; max board = 752 - (88 + 3*27).
      expect(sep).toHaveAttribute('aria-valuemax', String(752 - 169))
      heights.prompt = 150
      act(() => roMocks.forEach((cb) => cb()))
      // The prompt takes 150px; the split has 150px less to share.
      expect(sep).toHaveAttribute('aria-valuemax', String(752 - 150 - 169))
    })
  })

  it('uses a breakpoint styles.css really has', async () => {
    const { ROOM_STACKED } = await import('../useNarrow')
    // @ts-ignore -- test-only Node import, the app has no @types/node
    const fs = await import('node:fs')
    const css: string = fs.readFileSync('src/styles.css', 'utf8')
    expect(css).toContain(`@media ${ROOM_STACKED}`)
  })
})
