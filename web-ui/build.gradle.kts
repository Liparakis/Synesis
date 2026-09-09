import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
}

group = "org.synesis"
version = "0.1.0-SNAPSHOT"

val npmCommand = if (OperatingSystem.current().isWindows) "npm.cmd" else "npm"
val packageJson = layout.projectDirectory.file("package.json")
val packageLock = layout.projectDirectory.file("package-lock.json")
val nodeModules = layout.projectDirectory.dir("node_modules")
val sourceDirectory = layout.projectDirectory.dir("src")
val publicDirectory = layout.projectDirectory.dir("public")
val distDirectory = layout.projectDirectory.dir("dist")

val npmCi = tasks.register<Exec>("npmCi") {
    group = "web-ui"
    description = "Installs the locked web UI dependencies."
    inputs.files(packageJson, packageLock)
    outputs.dir(nodeModules)
    workingDir(layout.projectDirectory)
    commandLine(npmCommand, "ci", "--ignore-scripts", "--no-audit", "--no-fund")
}

fun npmTask(name: String, descriptionText: String, vararg arguments: String) =
        tasks.register<Exec>(name) {
            group = "web-ui"
            description = descriptionText
            dependsOn(npmCi)
            inputs.files(packageJson, packageLock, sourceDirectory, publicDirectory,
                    layout.projectDirectory.file("index.html"),
                    layout.projectDirectory.file("vite.config.ts"),
                    layout.projectDirectory.file("tsconfig.json"),
                    layout.projectDirectory.file("tsconfig.node.json"),
                    layout.projectDirectory.file("tailwind.config.cjs"),
                    layout.projectDirectory.file("postcss.config.cjs"),
                    layout.projectDirectory.file("eslint.config.js"),
                    layout.projectDirectory.file("vitest.config.ts"))
            workingDir(layout.projectDirectory)
            commandLine(npmCommand, "run", *arguments)
        }

val typecheck = npmTask("webUiTypecheck", "Type-checks the web UI.", "typecheck")
val lint = npmTask("webUiLint", "Lints the web UI.", "lint")
val tests = npmTask("webUiTest", "Runs the web UI tests.", "test")
val buildWebUi = npmTask("webUiBuild", "Builds production web UI assets.", "build")

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildWebUi)
    from(distDirectory) {
        into("web-ui")
    }
}

tasks.named("check") {
    dependsOn(typecheck, lint, tests, buildWebUi)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
