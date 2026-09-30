/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

/** Where the Vite dev / preview servers proxy `/api` (the telemetry service). */
const apiTarget = process.env.CATCHY_API_URL || 'http://localhost:8090';

const proxy = {
  '/api': {
    target: apiTarget,
    changeOrigin: true,
  },
};

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  return {
    plugins: [react()],
    // Production builds bake the mock flag in as a literal so the in-browser mock is tree-shaken out
    // (and not even emitted) unless VITE_MOCK_API=true. Dev and tests resolve it at run time.
    define:
      command === 'build'
        ? { 'import.meta.env.VITE_MOCK_API': JSON.stringify(env.VITE_MOCK_API === 'true' ? 'true' : 'false') }
        : {},
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 5173, proxy },
    build: { sourcemap: false, chunkSizeWarningLimit: 700 },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./vitest.setup.ts'],
      css: false,
      restoreMocks: true,
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});
