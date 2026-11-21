import { afterEach, describe, expect, it } from 'vitest'
import { applyPageHead } from './pageTitle'
import { imageAlt } from './redraftCopy'

// Spec 079 PR-05: a shared game load carries that game's picture in the head; a client-side move must put the site picture back.
const meta = (attr: string, key: string) => document.head.querySelector(`meta[${attr}="${key}"]`)?.getAttribute('content')

afterEach(() => {
  document.head.innerHTML = ''
})

describe('applyPageHead image tags', () => {
  it('replaces a per-game image and alt with the site image and alt', () => {
    document.head.innerHTML = [
      '<meta property="og:image" content="https://api.example/games/9/preview.png?v=1" />',
      '<meta name="twitter:image" content="https://api.example/games/9/preview.png?v=1" />',
      '<meta property="og:image:alt" content="Champion, 13-1" />',
      '<meta name="twitter:image:alt" content="Champion, 13-1" />',
    ].join('')
    applyPageHead({ title: 'Ball Knowers Redraft' })
    const site = 'https://www.ballknowers.co/og-image-tm.png'
    expect(meta('property', 'og:image')).toBe(site)
    expect(meta('name', 'twitter:image')).toBe(site)
    expect(meta('property', 'og:image:alt')).toBe(imageAlt)
    expect(meta('name', 'twitter:image:alt')).toBe(imageAlt)
  })

  it('adds the four tags when the head has none, once each', () => {
    applyPageHead({ title: 'A' })
    applyPageHead({ title: 'B' })
    expect(document.head.querySelectorAll('meta[property="og:image"]')).toHaveLength(1)
    expect(meta('name', 'twitter:image')).toBe('https://www.ballknowers.co/og-image-tm.png')
  })
})
