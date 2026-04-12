plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    implementation(project(":megarepo-database"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
