plugins {
    `java-library`
}

// ── Frontend Build Integration ─────────────────────────────────────────
// Builds the React frontend with Vite and copies the output to static resources.
// For development, use `cd frontend && npm run dev` with Vite's proxy.

val frontendDir = project.file("frontend")
val frontendDist = frontendDir.resolve("dist")
val staticDir = project.file("src/main/resources/static")

val npmInstall by tasks.registering(Exec::class) {
    workingDir = frontendDir
    commandLine("pnpm", "install")
    inputs.file(frontendDir.resolve("package.json"))
    inputs.file(frontendDir.resolve("pnpm-lock.yaml"))
    outputs.dir(frontendDir.resolve("node_modules"))
}

val frontendBuild by tasks.registering(Exec::class) {
    dependsOn(npmInstall)
    workingDir = frontendDir
    commandLine("pnpm", "run", "build")
    inputs.dir(frontendDir.resolve("src"))
    inputs.file(frontendDir.resolve("index.html"))
    inputs.file(frontendDir.resolve("vite.config.ts"))
    inputs.file(frontendDir.resolve("tsconfig.json"))
    outputs.dir(frontendDist)
}

val copyFrontend by tasks.registering(Copy::class) {
    dependsOn(frontendBuild)
    from(frontendDist)
    into(staticDir)
}

tasks.named("processResources") {
    dependsOn(copyFrontend)
}
