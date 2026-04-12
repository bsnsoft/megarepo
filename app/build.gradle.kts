plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    id("org.owasp.dependencycheck") version "12.1.1"
}

dependencyCheck {
    failBuildOnCVSS = 7.0f // Above 7 is no-go for production
    suppressionFile = "owasp-suppressions.xml"
    analyzers.kev.enabled = false // CISA KEV feed returns 403 from CI runners
}

val gitDescribeVersion = providers.exec {
    commandLine("git", "describe", "--abbrev=10", "--tags", "--always")
}.standardOutput.asText.map { it.trim() }

allprojects {
    group = "de.bsnsoft.megarepo"
    version = gitDescribeVersion.get()
    repositories {
        mavenCentral()
    }
}

subprojects {
    // Skip java plugin for BOM module (it uses java-platform)
    if (name != "megarepo-bom") {
        apply(plugin = "java")
        apply(plugin = "io.spring.dependency-management")

        the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
            imports {
                mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
            }
            dependencies {
                // Override Tomcat to fix CVE-2026-34483, CVE-2026-34486, CVE-2026-34487, CVE-2026-34500
                dependency("org.apache.tomcat.embed:tomcat-embed-core:10.1.54")
                dependency("org.apache.tomcat.embed:tomcat-embed-el:10.1.54")
                dependency("org.apache.tomcat.embed:tomcat-embed-websocket:10.1.54")
                // Fix CVE-2025-48924 (uncontrolled recursion in ClassUtils)
                dependency("org.apache.commons:commons-lang3:3.18.0")
            }
        }

        configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        tasks.withType<JavaCompile> {
            options.compilerArgs.addAll(listOf("-parameters"))
            options.release.set(21)
        }

        dependencies {
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    }
}
