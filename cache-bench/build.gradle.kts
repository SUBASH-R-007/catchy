plugins {
    java
    id("me.champeau.jmh")
}

description = "CacheLab JMH benchmarks and result exporter."

dependencies {
    implementation(project(":cache-core"))
}

jmh {
    jmhVersion.set(libs.versions.jmh.core.get())
}
