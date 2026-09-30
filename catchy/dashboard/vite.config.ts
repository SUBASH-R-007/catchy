/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  // The Spring Boot server (./gradlew :cache-server:bootRun) serves the API and the SSE stream.
  // Override with CACHELAB_API_URL when port 8080 is taken, e.g. CACHELAB_API_URL=http://localhost:8081.
  const env = loadEnv(mode, '.', '');
  const apiUrl = env.CACHELAB_API_URL || 'http://localhost:8080';

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: apiUrl,
          changeOrigin: false,
          // When the server dies mid-stream, end the browser's SSE response too, so the dashboard
          // notices at once (Vite only ends responses whose headers were not yet sent).
          configure: (proxy) => {
            proxy.on('error', (_err, _req, res) => {
              if ('headersSent' in res && res.headersSent && !res.writableEnded) res.end();
            });
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      restoreMocks: true,
    },
  };
});
