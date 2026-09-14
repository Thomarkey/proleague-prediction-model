import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // Built straight into the jar's static resources, so Spring serves the SPA in production.
  build: { outDir: '../src/main/resources/static', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8080' } },
})
