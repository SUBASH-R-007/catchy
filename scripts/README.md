# scripts

Build and export helpers.

- `build-all.sh` (Step 5): builds the dashboard, copies `dashboard/dist` into the server's static
  resources, and builds the server jar.

The circuit-board generator lives with the dashboard: `dashboard/scripts/generate-circuit.mjs`
(`npm run generate:circuit`).
