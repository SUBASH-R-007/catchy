# CLAUDE.md — CacheLab working rules

`SPEC.md` is the single source of truth. Read it (and `PROGRESS.md`) before doing anything.
This file condenses SPEC sections 0 and 2 so every session inherits the rules.

## Operating rules (SPEC 0)

1. Read all of `SPEC.md` before writing code; section numbers are referenced everywhere.
2. Work in the five steps of SPEC 12, in order; within a step, build modules in the listed order.
   At each gate: run every verification command → update `PROGRESS.md` → commit → **stop and
   report** (what was built, how to run it, test results, deviations). Wait for `continue`, unless
   the user said `run all remaining steps` — then continue only if the gate passed.
3. Keep `PROGRESS.md` current (step, checklist, known issues, exact next action) with every commit.
4. Any decision the spec does not fix → one line in `docs/DECISIONS.md` (decision + reason).
5. Spec impossible/contradictory → simplest option that best serves the judging criteria (SPEC 1);
   record it and mention it in the gate report. Never silently drop a feature.
6. Don't over-engineer: no frameworks, libraries or features beyond the spec unless required.
7. Verify before claiming done: run tests and builds; for UI, run the dev server and check it in a
   browser. A feature is done only when its "done when" checks pass.
8. Commit after each module (or part) with conventional commits, e.g.
   `feat(core): O(1) LFU policy with frequency buckets`. End messages with the
   `Co-Authored-By` trailer.
9. **`cache-core` has zero runtime dependencies. Always.**
10. Write tests first at the tricky points: LFU bucket list (4.3), locking and listener dispatch
    (4.6), expiry index versioning (4.5), LFU-decay merge (6.1), runtime policy switch (6.2),
    Bélády replay (6.4), SSE emitter lifecycle (8.4).
11. Subagents only for independent, well-scoped tasks; you integrate and verify their work.
12. If files exist, audit them against the spec and extend additively; never recreate from scratch.

## Stack and conventions (SPEC 2)

- **Backend:** Java 21 (Gradle toolchain), Gradle 8.x Kotlin DSL + `gradle/libs.versions.toml`,
  Spring Boot 3.5.x, springdoc-openapi, Micrometer, JUnit 5 + AssertJ, JMH (`me.champeau.jmh`),
  Caffeine only in `cache-bench` and test scope, Spotless (google-java-format), JaCoCo on
  `cache-core`. No Lombok.
- **Frontend:** Vite, React 18, TypeScript strict, Tailwind (colours = CSS variables), React Router,
  Recharts, lucide-react, `@fontsource` (Space Grotesk, Inter, JetBrains Mono) — no external font
  or CDN requests. Vitest + Testing Library, ESLint + Prettier.
- **Packages:** `io.cachelab` (public API), `io.cachelab.internal` (engine, policies),
  `io.cachelab.advisor`, `io.cachelab.diagnostics` (main source set), test fixtures in
  `io.cachelab.testing`.
- Javadoc on every public type and method: semantics, null handling, thread-safety, complexity
  (`cache-core` enforces this: javadoc doclint + `-Werror`).
- Methods longer than ~40 lines need a reason. All randomness is seeded (`SplittableRandom`) unless
  it is explicitly for stress tests.

## Commands

```bash
./gradlew spotlessApply build                                   # format + build + all JVM tests
./gradlew :cache-core:dependencies --configuration runtimeClasspath   # must print "No dependencies"
./gradlew :cache-server:bootRun                                 # server on :8080
cd dashboard && npm run lint && npm run typecheck && npm test -- --run && npm run build
cd dashboard && npm run dev                                     # dashboard on :5173 (proxies /api)
```

Environment notes: this machine's downloads are slow (~200 KB/s); the Gradle daemon may run on a
newer JDK while compiling with the Java 21 toolchain.
