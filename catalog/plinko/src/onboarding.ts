// First-run tutorial persistence. Returns "seen" on storage failure so a
// blocked/private-mode webview never nags the player on every load.

const TUTORIAL_SEEN_KEY = 'plinko.tutorialSeen'

export function hasSeenTutorial(): boolean {
  try {
    return localStorage.getItem(TUTORIAL_SEEN_KEY) === '1'
  } catch {
    return true
  }
}

export function markTutorialSeen(): void {
  try {
    localStorage.setItem(TUTORIAL_SEEN_KEY, '1')
  } catch {
    /* ignore */
  }
}
