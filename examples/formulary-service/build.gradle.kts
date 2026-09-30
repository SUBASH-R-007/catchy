plugins {
    java
    id("org.springframework.boot")
}

description = "Tiny @Cacheable example app backed by the CacheLab Spring Boot starter."

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":cache-spring"))
    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    testLogging.showStandardStreams = true
}
