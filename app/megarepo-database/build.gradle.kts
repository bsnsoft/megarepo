plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    api(libs.spring.boot.starter.data.jpa)
    api(libs.flyway.core)
    api(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}
