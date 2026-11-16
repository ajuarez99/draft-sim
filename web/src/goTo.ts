// Spec 073: the one place the app leaves its own origin. A module of its own so tests can vi.mock it
// and never navigate jsdom (window.location.replace cannot be spied on in jsdom).
export function goTo(url: string): void {
  window.location.replace(url)
}
