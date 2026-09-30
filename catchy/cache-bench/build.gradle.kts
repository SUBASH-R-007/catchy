plugins {
    java
    id("me.champeau.jmh")
}

description = "CacheLab JMH benchmarks and result exporter."

dependencies {
    implementation(project(":cache-core"))
    // Caffeine is the reference ceiling; it is used only by the benchmarks (SPEC 2).
    jmh(libs.caffeine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Hackathon-speed settings (SPEC 5): 1 fork, 3 warm-up and 5 measurement iterations of 1 s each.
// Threads (1, 4, 16, 32) are covered by one @Threads method per count in CacheBenchmark.
jmh {
    jmhVersion.set(libs.versions.jmh.core.get())
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)
    warmup.set("1s")
    timeOnIteration.set("1s")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
}

val exportBench by tasks.registering(JavaExec::class) {
    description = "Converts the JMH JSON results into cache-server's bench-results.json."
    group = "benchmark"
    mainClass.set("io.cachelab.bench.BenchExport")
    classpath = sourceSets.main.get().runtimeClasspath
    args(
        layout.buildDirectory.file("results/jmh/results.json").get().asFile.absolutePath,
        rootProject.file("cache-server/src/main/resources/bench/bench-results.json").absolutePath,
    )
}
