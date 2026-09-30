import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.jmh) apply false
}

allprojects {
    group = "io.cachelab"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
        }
        tasks.withType<Javadoc>().configureEach { options.encoding = "UTF-8" }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat(rootProject.libs.versions.google.java.format.get())
        }
    }
}
