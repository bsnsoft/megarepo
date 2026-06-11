plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// This module only has tests, no production code
tasks.bootJar { enabled = false }
tasks.jar { enabled = false }

// Allow overriding the integration-test database when port 5432 is taken locally:
// ./gradlew :megarepo-integration-tests:test -Pmegarepo.it.db.url=jdbc:postgresql://localhost:55432/megarepo?stringtype=unspecified
tasks.test {
    (project.findProperty("megarepo.it.db.url") as String?)?.let {
        systemProperty("megarepo.it.db.url", it)
    }
}

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
