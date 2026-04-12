plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(project(":megarepo-core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.commons.codec)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)
    testImplementation(libs.spring.boot.starter.test)
}
