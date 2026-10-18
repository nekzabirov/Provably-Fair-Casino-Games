import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

// iOS Safari ignores user-scalable=no, so block pinch / double-tap zoom directly.
document.addEventListener('gesturestart', (e) => e.preventDefault())
document.addEventListener('gesturechange', (e) => e.preventDefault())
let lastTouchEnd = 0
document.addEventListener(
  'touchend',
  (e) => {
    const now = Date.now()
    if (now - lastTouchEnd <= 300) e.preventDefault()
    lastTouchEnd = now
  },
  { passive: false },
)

// Reveal the app and fade the pre-paint splash once Vue has mounted.
function hideSplash(): void {
  const splash = document.getElementById('splash')
  if (!splash) return
  splash.classList.add('is-hidden')
  setTimeout(() => splash.remove(), 500)
}

createApp(App).mount('#app')
requestAnimationFrame(() => requestAnimationFrame(hideSplash))
