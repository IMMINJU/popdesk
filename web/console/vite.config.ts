import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The server tells the two apps apart by Host (ADR D-43), so the dev proxy
// sends console.localhost instead of the dev server's own host.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
        headers: { host: 'console.localhost' },
      },
    },
  },
  build: {
    outDir: 'dist',
  },
});
