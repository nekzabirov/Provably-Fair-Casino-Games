import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
import { startSplash } from './preload'

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

// Drive the splash (Plinko has no heavy assets — it waits only for auth).
startSplash()

createApp(App).mount('#app')
