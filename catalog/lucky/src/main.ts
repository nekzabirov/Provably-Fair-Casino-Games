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

createApp(App).mount('#app')
