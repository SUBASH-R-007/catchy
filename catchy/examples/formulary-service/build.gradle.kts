plugins {
    java
}

description = "Tiny @Cacheable example app backed by the CacheLab Spring Boot starter."

dependencies {
    implementation(project(":cache-spring"))
}
