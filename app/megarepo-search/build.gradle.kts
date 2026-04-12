plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    implementation(project(":megarepo-database"))
    implementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.spring.boot.starter.test)
}
