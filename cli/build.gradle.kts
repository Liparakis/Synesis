import org.gradle.kotlin.dsl.application
import org.gradle.kotlin.dsl.dependencies
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.internal.os.OperatingSystem
import org.gradle.language.jvm.tasks.ProcessResources
import java.time.Instant
import java.util.Locale
import java.nio.file.Files

plugins {
    application
}

group = "org.synesis"
version = "0.1.0-SNAPSHOT"

val hostArch = if (System.getProperty("os.arch").lowercase(Locale.ROOT).contains("aarch64")) "arm64" else "x64"
val hostPlatform = when {
    OperatingSystem.current().isWindows -> "windows-$hostArch"
    OperatingSystem.current().isMacOsX -> "macos-$hostArch"
    else -> "linux-$hostArch"
}
val isWindows = hostPlatform.startsWith("windows")

val bundlePlatform = providers.gradleProperty("bundlePlatform").orElse(hostPlatform)
val bundleVersion = providers.gradleProperty("synesisVersion")
    .orElse(providers.environmentVariable("SYNESIS_VERSION").orElse("0.1.0-dev.local"))
val buildInfoDirectory = layout.buildDirectory.dir("generated-resources/build-info")
val platformBundleDirectory =
    layout.buildDirectory.dir("platform-bundle/synesis-${bundleVersion.get()}-${bundlePlatform.get()}")
val runtimeImageDirectory = layout.buildDirectory.dir("platform-runtime")

val buildInfo = tasks.register("buildInfo") {
    description = ""
    outputs.dir(buildInfoDirectory)
    doLast {
        val output = buildInfoDirectory.get().file("synesis-build.properties").asFile
        output.parentFile.mkdirs()
        output.writeText(
            "version=${bundleVersion.get()}\n" + "recordFormat=SDR2\n" + "reconciliationProtocol=PRP1\n" + "commit=${
                providers.environmentVariable(
                    "GITHUB_SHA"
                ).orElse("UNKNOWN").get()
            }\n" + "time=${Instant.now()}\n" + "platform=${bundlePlatform.get()}\n" + "javaRuntime=${Runtime.version()}\n"
        )
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
    withJavadocJar()
}

application {
    applicationName = "synesis"
    mainClass = "org.synesis.cli.SynesisCli"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":link"))
    implementation(project(":project-record"))
    implementation(project(":workspace"))
    implementation(libs.picocli)
    implementation(libs.zxing.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildInfo)
    from(buildInfoDirectory)
}

configurations.configureEach { resolutionStrategy.activateDependencyLocking() }

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    isFailOnError = true
    options.encoding = "UTF-8"
    with(options as StandardJavadocDocletOptions) {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    dependsOn(tasks.installDist)
}

tasks.register("launcherSmoke") {
    group = "verification"
    description = "Checks that the standard Application launchers are generated."
    dependsOn(tasks.installDist)
    doLast {
        require(layout.buildDirectory.file("install/synesis/bin/synesis.bat").get().asFile.isFile)
        require(layout.buildDirectory.file("install/synesis/bin/synesis").get().asFile.isFile)
    }
}

fun filesUnder(dir: File, extensions: Set<String>): List<File> =
    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension in extensions }.toList()
    else listOfNotNull(dir.takeIf { it.isFile && it.extension in extensions })

tasks.register("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in CLI source and documentation."
    doLast {
        val extensions = setOf("java", "kt", "kts")
        val files =
            filesUnder(project.file("src"), extensions) + filesUnder(project.file("build.gradle.kts"), extensions)
        val offenders = files.filter { source ->
            source.useLines { lines -> lines.any { it.endsWith(" ") || it.endsWith("\t") } }
        }
        require(offenders.isEmpty()) { "Trailing whitespace: ${offenders.joinToString()}" }
    }
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs strict compiler diagnostics."
    dependsOn(tasks.compileJava, tasks.compileTestJava)
}

val runtimeImage = tasks.register("runtimeImage") {
    group = "distribution"
    description = "Builds the minimal native jlink runtime for the current host."
    dependsOn(tasks.installDist)
    outputs.dir(runtimeImageDirectory)
    doLast {
        val javaHome = File(System.getProperty("java.home"))
        val jlink = javaHome.resolve("bin").resolve(if (isWindows) "jlink.exe" else "jlink")
        val jmods = javaHome.resolve("jmods")
        require(jlink.isFile) { "jlink not found: $jlink" }
        delete(runtimeImageDirectory)
        val process = ProcessBuilder(
            jlink.absolutePath,
            "--module-path",
            jmods.absolutePath,
            "--add-modules",
            "java.base,java.logging,java.naming,jdk.jfr,jdk.unsupported",
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress=2",
            "--output",
            runtimeImageDirectory.get().asFile.absolutePath
        ).inheritIO().start()
        require(process.waitFor() == 0) { "jlink failed with exit code ${process.exitValue()}" }
    }
}

val platformBundle = tasks.register("platformBundle") {
    group = "distribution"
    description = "Assembles a self-contained Synesis application bundle."
    dependsOn(runtimeImage, tasks.installDist)
    outputs.dir(platformBundleDirectory)
    doLast {
        val root = platformBundleDirectory.get().asFile
        delete(root)
        val app = root.resolve("app")
        val libDir = app.resolve("lib")
        libDir.mkdirs()
        copy {
            from(tasks.jar.get().archiveFile)
            into(app)
            rename { "synesis-cli.jar" }
        }
        copy {
            from(layout.buildDirectory.dir("install/synesis/lib"))
            into(libDir)
            exclude("cli-*.jar")
        }
        copy { from(runtimeImageDirectory); into(root.resolve("runtime")) }
        val bin = root.resolve("bin")
        bin.mkdirs()
        bin.resolve("synesis.cmd").writeText(
            "@echo off\r\nsetlocal\r\nset \"APP_HOME=%~dp0..\"\r\nset \"SYNESIS_LAUNCHER=%~f0\"\r\n\"%APP_HOME%\\runtime\\bin\\java.exe\" --enable-native-access=ALL-UNNAMED -cp \"%APP_HOME%\\app\\synesis-cli.jar;%APP_HOME%\\app\\lib\\*\" org.synesis.cli.SynesisCli %*\r\nexit /b %ERRORLEVEL%\r\n"
        )
        bin.resolve("synesis").writeText(
            $$"#!/bin/sh\nAPP_HOME=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")/..\" && pwd)\"\nexport SYNESIS_LAUNCHER=\"$APP_HOME/bin/synesis\"\nexec \"$APP_HOME/runtime/bin/java\" --enable-native-access=ALL-UNNAMED -cp \"$APP_HOME/app/synesis-cli.jar:$APP_HOME/app/lib/*\" org.synesis.cli.SynesisCli \"$@\"\n"
        )
        root.resolve("VERSION").writeText(bundleVersion.get() + "\n")
        root.resolve("README.md").writeText("Run bin/synesis (Unix) or bin/synesis.cmd (Windows).\n")
        root.resolve("LICENSE").writeText(rootProject.file("LICENSE").readText())
        root.resolve("manifest.json").writeText(
            "{\"schemaVersion\":1,\"version\":\"${bundleVersion.get()}\",\"platform\":\"${bundlePlatform.get()}\"}\n"
        )
        if (!isWindows) {
            bin.resolve("synesis").setExecutable(true)
        }
    }
}

val platformZip = tasks.register<Zip>("platformZip") {
    description = ""
    group = "distribution"
    dependsOn(platformBundle)
    archiveFileName.set("synesis-${bundleVersion.get()}-${bundlePlatform.get()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(platformBundleDirectory)
    into(platformBundleDirectory.get().asFile.name)
}

val platformTarGz = tasks.register<Tar>("platformTarGz") {
    description = ""
    group = "distribution"
    dependsOn(platformBundle)
    archiveFileName.set("synesis-${bundleVersion.get()}-${bundlePlatform.get()}.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    from(platformBundleDirectory)
    into(platformBundleDirectory.get().asFile.name)
}

tasks.register("platformArchive") {
    group = "distribution"
    description = "Creates ZIP and gzip tar platform archives."
    dependsOn(platformZip, platformTarGz)
}

tasks.register("bundleSmokeTest") {
    group = "verification"
    description = "Extracts and runs the platform archive outside the source tree."
    dependsOn("platformArchive")
    doLast {
        val smokeRoot = Files.createTempDirectory("synesis-bundle-smoke-").toFile()
        val archive = if (isWindows) platformZip.get().archiveFile.get().asFile
        else platformTarGz.get().archiveFile.get().asFile
        val extractedRoot = smokeRoot.resolve("bundle")
        if (!isWindows) {
            require(platformBundleDirectory.get().asFile.resolve("bin/synesis").canExecute()) {
                "Unix bundle launcher is not executable before archiving"
            }
            require(runtimeImageDirectory.get().asFile.resolve("bin/java").canExecute()) {
                "Bundled Unix Java runtime is not executable before archiving"
            }
        }
        copy {
            from(if (archive.extension == "zip") zipTree(archive) else tarTree(resources.gzip(archive)))
            into(extractedRoot)
        }
        val bundleRoot = extractedRoot.resolve(platformBundleDirectory.get().asFile.name)
        val launcher = bundleRoot.resolve("bin").resolve(if (isWindows) "synesis.cmd" else "synesis")
        if (!isWindows) {
            require(launcher.setExecutable(true)) { "Unable to restore Unix launcher permissions after extraction" }
            require(bundleRoot.resolve("runtime/bin/java").setExecutable(true)) {
                "Unable to restore bundled Java runtime permissions after extraction"
            }
        }
        fun runWithExit(expectedExitCode: Int, arguments: Array<out String>) {
            val command = (if (isWindows) mutableListOf("cmd.exe", "/c", launcher.absolutePath)
            else mutableListOf(launcher.absolutePath)).apply { addAll(arguments) }
            val processBuilder = ProcessBuilder(command).directory(smokeRoot).redirectErrorStream(true)
            processBuilder.environment()["JAVA_HOME"] = smokeRoot.resolve("missing-java").absolutePath
            val result = processBuilder.start()
            val output = result.inputStream.bufferedReader().readText()
            require(result.waitFor() == expectedExitCode) { "Bundle command failed: ${arguments.joinToString(" ")}\n$output" }
            if (arguments.firstOrNull() == "version") require("SYNESIS_VERSION=" in output)
        }
        fun run(vararg arguments: String) = runWithExit(0, arguments)
        fun run(expectedExitCode: Int, vararg arguments: String) = runWithExit(expectedExitCode, arguments)
        try {
            run("version")
            val project = smokeRoot.resolve("project")
            require(project.mkdirs()) { "Unable to create smoke project: $project" }
            run("init", "--project", project.absolutePath)
            run("provider", "list", "--project", project.absolutePath)
            run("provider", "install", "antigravity", "--project", project.absolutePath)
            run(1, "provider", "status", "antigravity", "--project", project.absolutePath)
            run("provider", "uninstall", "antigravity", "--project", project.absolutePath)
            run("doctor", "--project", project.absolutePath)
        } finally {
            delete(smokeRoot)
        }
    }
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis", "launcherSmoke", "bundleSmokeTest")
}
