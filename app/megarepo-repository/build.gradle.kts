plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    implementation(project(":megarepo-storage"))
    implementation(project(":megarepo-database"))
    // purl (package-URL) identity — PackageURL appears in the signatures of
    // PurlMapper/PurlBuilder/ComponentIdentity, which the format modules
    // implement, so it is part of this module's API surface.
    api(libs.packageurl.java)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    // ComparableVersion for the firewall's Maven version scheme. The class is
    // self-contained, so the plexus-utils transitive is excluded to keep the
    // dependency surface of a security component minimal.
    implementation(libs.maven.artifact) {
        exclude(group = "org.codehaus.plexus", module = "plexus-utils")
    }
    testImplementation(libs.spring.boot.starter.test)
    // The advisory ingest and lookup are SQL-shaped — idempotent replacement of
    // affected ranges, the (purl_type, purl_name) index, IS NULL namespace
    // matching. Mocked repositories would assert the mock, so those two run
    // against the real migrated schema in a container. Every other test in this
    // module stays a plain unit test.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
}
