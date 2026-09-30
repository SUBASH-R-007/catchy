/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL prepended to `/api/v1/...` (default: same origin). */
  readonly VITE_API_BASE?: string;
  /** `"true"` swaps the network for the in-browser mock API (src/api/mock). */
  readonly VITE_MOCK_API?: string;
  /** Mock only: `"false"` makes `/auth/demo-login` return 404. */
  readonly VITE_MOCK_DEMO_MODE?: string;
  /** Mock only: artificial latency in ms (default 120). */
  readonly VITE_MOCK_LATENCY_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
