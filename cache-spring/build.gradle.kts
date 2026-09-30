plugins {
    `java-library`
}

description = "CacheLab Spring Boot starter and Micrometer binder."

dependencies {
    api(project(":cache-core"))
}
