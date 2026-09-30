# CacheLab

*A cache you can trust under load: provably correct eviction, independent expiry, and a live panel
that shows why LRU or LFU wins on your traffic.*

> **Status: Step 1 of 5 (foundation and theme shell).** The public API contracts, build, CI, the
> demo server's metrics stream (fake data for now) and the circuit-board dashboard shell are in
> place. The cache engine lands in Step 2. See [`PROGRESS.md`](PROGRESS.md) and
> [`SPEC.md`](SPEC.md). The full README (usage, semantics, complexity, benchmarks) is written in
> Step 5.

## Quickstart (development)

Requirements: JDK 21+ (Gradle provisions a Java 21 toolchain if needed) and Node 20.19+.

```bash
./gradlew :cache-server:bootRun            # API + metrics stream on http://localhost:8080
cd dashboard && npm install && npm run dev # dashboard on http://localhost:5173
```

Other useful commands:

```bash
./gradlew spotlessApply build              # format, compile, run all JVM tests
curl -N localhost:8080/api/metrics/stream  # watch the SSE stream (one event every 500 ms)
cd dashboard && npm run lint && npm run typecheck && npm test -- --run && npm run build
```

Swagger UI: <http://localhost:8080/swagger-ui>.

## Modules

| Module | Purpose |
|---|---|
| `cache-core` | The library: LRU / LFU / LFU-decay eviction, independent TTL, thread-safe. **Zero runtime dependencies.** |
| `cache-bench` | JMH benchmarks vs a synchronized `LinkedHashMap` and Caffeine (Step 4) |
| `cache-spring` | Spring Boot starter (`@Cacheable`) and Micrometer binder (Step 4) |
| `cache-server` | Demo server: REST API, metrics SSE stream, workloads, guided demo |
| `examples/formulary-service` | Tiny `@Cacheable` example app (Step 4) |
| `dashboard` | React live metrics panel with the circuit-board theme |

Design records: [`docs/adr`](docs/adr). Decisions not fixed by the spec:
[`docs/DECISIONS.md`](docs/DECISIONS.md). Metrics payload contract:
[`docs/api/metrics.schema.json`](docs/api/metrics.schema.json).

## License

[MIT](LICENSE)
