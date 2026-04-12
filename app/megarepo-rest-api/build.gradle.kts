plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    implementation(project(":megarepo-repository"))
    implementation(project(":megarepo-database"))
    implementation(project(":megarepo-security"))
    implementation(project(":megarepo-search"))
    implementation(project(":megarepo-storage"))
    implementation(project(":megarepo-tasks"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.openapi.starter)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
