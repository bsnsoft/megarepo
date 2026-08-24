plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":megarepo-core"))
    implementation(project(":megarepo-storage"))
    implementation(project(":megarepo-database"))
    implementation(project(":megarepo-security"))
    implementation(project(":megarepo-repository"))
    implementation(project(":megarepo-search"))
    implementation(project(":megarepo-tasks"))
    implementation(project(":megarepo-rest-api"))
    implementation(project(":megarepo-web-ui"))

    // Format plugins - loaded as runtime dependencies
    runtimeOnly(project(":megarepo-format-maven"))
    runtimeOnly(project(":megarepo-format-pypi"))
    runtimeOnly(project(":megarepo-format-npm"))
    runtimeOnly(project(":megarepo-format-nuget"))
    runtimeOnly(project(":megarepo-format-raw"))
    runtimeOnly(project(":megarepo-format-docker"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.springdoc.openapi.starter)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    // For AppAdminAuthorizationTest, which drives the real SecurityConfig filter
    // chain against the controllers this module contributes to /api/v1/admin/**
    // and /api/v1/system/license. Those endpoints cannot be asserted from
    // megarepo-rest-api — it does not see this module — so the authorization
    // proof for them has to live here, next to the controllers it covers.
    testImplementation(libs.spring.security.test)
    // The CPE/purl comparison report is proven here rather than in
    // megarepo-repository, because it needs the per-format PurlMapper beans and
    // those live in the format modules, which depend on megarepo-repository and
    // cannot be depended on from it. They are runtimeOnly here, hence on this
    // module's test runtime classpath exactly as in the shipped application.
    // Both sides of the comparison are queries, so the proof needs the migrated
    // schema rather than mocked repositories.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

springBoot {
    mainClass.set("de.bsnsoft.megarepo.app.MegaRepoApplication")
}

tasks.bootJar {
    archiveFileName.set("megarepo.jar")
}

val gitVersion = providers.exec {
    commandLine("git", "describe", "--abbrev=10", "--tags", "--always")
}.standardOutput.asText.map { it.trim() }

tasks.register("generateVersionProperties") {
    val outputDir = layout.buildDirectory.dir("generated-resources/version")
    val version = gitVersion
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("megarepo-version.properties").writeText("megarepo.version=${version.get()}\n")
    }
}

sourceSets.main {
    resources.srcDir(tasks.named("generateVersionProperties").map { layout.buildDirectory.dir("generated-resources/version").get() })
}

tasks.named("processResources") {
    dependsOn("generateVersionProperties")
}
