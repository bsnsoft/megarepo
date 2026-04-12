plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":megarepo-core"))
    implementation(project(":megarepo-repository"))
    implementation(project(":megarepo-database"))
    implementation(project(":megarepo-storage"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.spring.boot.starter.test)
}
