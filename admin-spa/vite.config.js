import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev proxies mirror the production nginx routes (admin/admin-spa/nginx.conf):
//   /py/*  -> book_admin (FastAPI :8010)  — auth, config push, accounts, audit
//   /api/* -> admin-api  (Spring  :8104)  — users/money/pricing/waitlist
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/py': {
        target: 'http://localhost:8010',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/py/, ''),
      },
      '/api': {
        target: 'http://localhost:8104',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
