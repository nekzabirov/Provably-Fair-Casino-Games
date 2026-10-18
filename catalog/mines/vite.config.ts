import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// In prod the mines nginx proxies /ws/mines + /api/v1 to the backend and the
// bundle uses same-origin URLs (src/config.ts). For local `npm run dev` the vite
// server stands in for that nginx, proxying the same paths to the engine on :8080.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/ws/mines': { target: 'ws://localhost:8080', ws: true },
      '/api/v1': { target: 'http://localhost:8080' },
    },
  },
})
