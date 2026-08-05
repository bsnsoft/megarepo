plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    implementation(project(":megarepo-repository"))
    implementation(project(":megarepo-storage"))
    implementation(project(":megarepo-database"))
    implementation(libs.packageurl.java)
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
}
