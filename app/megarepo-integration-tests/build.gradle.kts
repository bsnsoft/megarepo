plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// This module only has tests, no production code
tasks.bootJar { enabled = false }
tasks.jar { enabled = false }

dependencies {
    testImplementation(project(":megarepo-app"))
    testImplementation(project(":megarepo-database"))
    testImplementation(project(":megarepo-core"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
}
