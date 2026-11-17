plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    api(libs.spring.boot.starter.data.jpa)
    // Boot 4 only auto-configures Flyway (migrations at startup) through its own module.
    api(libs.spring.boot.starter.flyway)
    api(libs.flyway.core)
    api(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}
