plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    api(libs.guava)
    // Jackson 2 ObjectMapper bean + jdk8/jsr310/parameter-names modules (Boot 4 defaults to Jackson 3).
    api(libs.spring.boot.jackson2)
    compileOnly(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.test)
}
