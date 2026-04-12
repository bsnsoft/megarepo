plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    implementation(project(":megarepo-repository"))
    implementation(project(":megarepo-storage"))
    implementation(project(":megarepo-database"))
    implementation(project(":megarepo-security"))
    implementation(project(":megarepo-tasks"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.commons.codec)
    testImplementation(libs.spring.boot.starter.test)
}
