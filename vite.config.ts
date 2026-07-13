import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
// GitHub Pages build (mode === 'github') uses a relative base so the built
// assets resolve correctly under https://user.github.io/<repo>/.
export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  base: mode === 'github' ? './' : '/',
  server: {
    port: 5180,
  },
  optimizeDeps: {
    exclude: ['@ffmpeg/ffmpeg', '@ffmpeg/util'],
  },
}))
