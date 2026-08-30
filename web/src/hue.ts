// Deterministic per-manager color, shared by the board header and seat cards
// so the same manager reads as the same color everywhere on the page.
export function hueFor(seed: string) {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) % 360
  return h
}
