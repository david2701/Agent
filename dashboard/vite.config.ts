import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Backend that /api is proxied to in development (KAIRO_API=http://localhost:8088 npm run dev).
const api = process.env.KAIRO_API ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: api, changeOrigin: true, ws: true },
    },
  },
  preview: {
    port: 4173,
    // Same CSP that Ktor applies in production, to catch violations before deploying.
    headers: {
      'Content-Security-Policy':
        "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self' ws: wss:; font-src 'self'; frame-ancestors 'none'; base-uri 'self'",
    },
    proxy: {
      '/api': { target: api, changeOrigin: true, ws: true },
    },
  },
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 700,
  },
})
