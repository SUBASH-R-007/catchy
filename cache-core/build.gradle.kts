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
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
