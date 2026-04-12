plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(libs.guava)
    compileOnly(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
}
