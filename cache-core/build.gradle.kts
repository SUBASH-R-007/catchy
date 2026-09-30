plugins {
    `java-library`
    `java-test-fixtures`
    jacoco
}

description = "CacheLab core: in-memory cache with LRU/LFU eviction and independent TTL (zero runtime dependencies)."

java {
    withSourcesJar()
    withJavadocJar()
}

// cache-core must never gain runtime dependencies: only test scopes are allowed below.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Werror")
}

tasks.javadoc {
    // Every public type and member must be documented; doclint problems fail the build.
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.test {
    useJUnitPlatform { excludeTags("stress") }
    finalizedBy(tasks.jacocoTestReport)
}

// Gate 3: 20 consecutive 32-thread x 5 s stress runs per engine (~3.5 min). Not part of `build`.
val stressTest by tasks.registering(Test::class) {
    description = "Runs the long stress-gate tests tagged 'stress'."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("stress") }
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
