plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    implementation(project(":megarepo-storage"))
    implementation(project(":megarepo-database"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    testImplementation(libs.spring.boot.starter.test)
}
