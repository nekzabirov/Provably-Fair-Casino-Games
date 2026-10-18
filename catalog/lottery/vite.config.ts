import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// In prod the lottery nginx proxies /api/v1 to the backend and the bundle uses
// same-origin URLs (src/config.ts). For local `npm run dev` the vite server stands
// in for that nginx, proxying the same path to the engine on :8080.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      '/api/v1': { target: 'http://localhost:8080' },
    },
  },
})
