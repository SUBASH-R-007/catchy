plugins {
    `java-library`
}

description = "CacheLab Spring Boot starter: CacheManager, @Cacheable support and Micrometer binder."

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(project(":cache-core"))
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-context")
    // Micrometer is optional: the binder activates only when it is on the classpath.
    compileOnly("io.micrometer:micrometer-core")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("io.micrometer:micrometer-core")
    testRuntimeOnly(libs.junit.platform.launcher)
}
