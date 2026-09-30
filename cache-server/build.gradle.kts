plugins {
    java
    id("org.springframework.boot")
}

description = "CacheLab demo server: REST API, metrics SSE stream, workloads and demo acts."

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":cache-core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.webmvc.ui)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
