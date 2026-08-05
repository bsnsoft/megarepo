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
    testImplementation(libs.spring.boot.starter.test)
}
