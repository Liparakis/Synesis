import org.gradle.internal.os.OperatingSystem
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture

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
val nativeMcpDirectory = layout.buildDirectory.dir("native-mcp")
val githubSha = providers.environmentVariable("GITHUB_SHA").orElse("UNKNOWN")
val runtimeVersion = Runtime.version().toString()
val launcherBat = layout.buildDirectory.file("install/synesis/bin/synesis.bat")
val launcherUnix = layout.buildDirectory.file("install/synesis/bin/synesis")
val cliSourceDirectory = layout.projectDirectory.dir("src").asFile
val cliBuildFile = layout.projectDirectory.file("build.gradle.kts").asFile

abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val commit: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val javaRuntime: Property<String>

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().file("synesis-build.properties").asFile
        output.parentFile.mkdirs()
        output.writeText(
            "version=${version.get()}\n" +
                    "recordFormat=SDR2\n" +
                    "reconciliationProtocol=PRP1\n" +
                    "commit=${commit.get()}\n" +
                    "time=${Instant.now()}\n" +
                    "platform=${platform.get()}\n" +
                    "javaRuntime=${javaRuntime.get()}\n"
        )
    }
}

abstract class TrailingWhitespaceTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val files: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val offenders = files.files.filter { source ->
            source.useLines { lines -> lines.any { it.endsWith(" ") || it.endsWith("\t") } }
        }
        require(offenders.isEmpty()) { "Trailing whitespace: ${offenders.joinToString()}" }
    }
}

val buildInfo = tasks.register<GenerateBuildInfoTask>("buildInfo") {
    description = ""
    outputs.dir(buildInfoDirectory)
    outputDirectory.set(buildInfoDirectory)
    version.set(bundleVersion)
    commit.set(githubSha)
    platform.set(bundlePlatform)
    javaRuntime.set(runtimeVersion)
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
    implementation(project(":coordination"))
    // The CLI keeps the compile-time boundary via reflection, but the installed
    // distribution must carry the MCP server so `synesis mcp` is runnable.
    runtimeOnly(project(":mcp"))
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
    dependsOn("testInstallDist")
}

// Process-level CLI tests exercise the generated Java launcher. Stage the
// application distribution they need without invoking the native MCP and
// installer builds reserved for release packaging.
tasks.register<Sync>("testInstallDist") {
    group = "verification"
    description = "Stages the Java application distribution for process tests."
    dependsOn(tasks.jar, tasks.startScripts)
    into(layout.buildDirectory.dir("install/synesis"))
    from(tasks.startScripts) {
        into("bin")
    }
    from(tasks.jar) {
        into("lib")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
    }
}

tasks.register("launcherSmoke") {
    group = "verification"
    description = "Checks that the standard Application launchers are generated."
    dependsOn(tasks.installDist)
    doLast {
        require(launcherBat.get().asFile.isFile)
        require(launcherUnix.get().asFile.isFile)
    }
}

val nativeMcpLauncher = tasks.register("nativeMcpLauncher") {
    group = "distribution"
    description = "Builds native MCP and installer launchers for the requested platforms."
    notCompatibleWithConfigurationCache(
        "Native cross-platform launcher packaging uses execution-time process and file APIs."
    )
    inputs.files(rootProject.fileTree("bootstrap") {
        include("*.go")
        include("go.mod")
        include("go.sum")
        include("cmd/synesis-mcp/*.go")
    })
    inputs.property("bundlePlatform", bundlePlatform)
    inputs.property("hostPlatform", hostPlatform)
    outputs.dir(nativeMcpDirectory)
    doLast {
        val outputRoot = nativeMcpDirectory.get().asFile
        delete(outputRoot)
        val source = rootProject.file("bootstrap")
        val platforms = mapOf(
            "windows-x64" to ("windows" to "amd64"),
            "windows-arm64" to ("windows" to "arm64"),
            "linux-x64" to ("linux" to "amd64"),
            "linux-arm64" to ("linux" to "arm64"),
            "macos-x64" to ("darwin" to "amd64"),
            "macos-arm64" to ("darwin" to "arm64")
        )
        val requestedPlatforms = linkedSetOf(bundlePlatform.get(), hostPlatform)
        for (name in requestedPlatforms) {
            val target = platforms[name] ?: error("Unsupported native launcher platform: $name")
            val outputDirectory = outputRoot.resolve(name)
            outputDirectory.mkdirs()
            val suffix = if (target.first == "windows") ".exe" else ""
            val environment = mutableMapOf<String, String>()
            environment["GOOS"] = target.first
            environment["GOARCH"] = target.second
            environment["CGO_ENABLED"] = "0"
            for ((binaryName, packagePath) in listOf("synesis-mcp$suffix" to "./cmd/synesis-mcp", "synesis-installer$suffix" to ".")) {
                val output = outputDirectory.resolve(binaryName)
                val process = ProcessBuilder(
                    "go", "build", "-trimpath", "-ldflags=-s -w", "-o", output.absolutePath, packagePath
                ).directory(source).redirectErrorStream(true).apply {
                    environment().putAll(environment)
                }.start()
                val outputText = process.inputStream.bufferedReader().readText()
                require(process.waitFor() == 0) { "native launcher build failed for $name/$binaryName: $outputText" }
                require(output.isFile) { "native launcher missing for $name/$binaryName: $output" }
                if (target.first != "windows") output.setExecutable(true)
            }
        }
        val hostArtifact = outputRoot.resolve(hostPlatform).resolve(if (isWindows) "synesis-mcp.exe" else "synesis-mcp")
        val installedBin = layout.buildDirectory.dir("install/synesis/bin").get().asFile
        installedBin.mkdirs()
        copy { from(hostArtifact); into(installedBin) }
    }
}

tasks.named("installDist") { finalizedBy(nativeMcpLauncher) }

fun filesUnder(dir: File, extensions: Set<String>): List<File> =
    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension in extensions }.toList()
    else listOfNotNull(dir.takeIf { it.isFile && it.extension in extensions })

tasks.register<TrailingWhitespaceTask>("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in CLI source and documentation."
    files.from(
        fileTree(cliSourceDirectory) {
            include("**/*.java")
            include("**/*.kt")
            include("**/*.kts")
        },
        cliBuildFile,
    )
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs strict compiler diagnostics."
    dependsOn(tasks.compileJava, tasks.compileTestJava)
}

val runtimeImage = tasks.register("runtimeImage") {
    group = "distribution"
    description = "Builds the minimal native jlink runtime for the current host."
    notCompatibleWithConfigurationCache(
        "Runtime-image packaging resolves and invokes the selected Java toolchain at execution time."
    )
    dependsOn(tasks.installDist)
    outputs.dir(runtimeImageDirectory)
    doLast {
        val javaToolchains = project.extensions.getByType<JavaToolchainService>()
        val javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().metadata.installationPath.asFile
        val jlink = javaHome.resolve("bin").resolve(if (isWindows) "jlink.exe" else "jlink")
        val jmods = javaHome.resolve("jmods")
        require(jlink.isFile) { "jlink not found: $jlink" }
        delete(runtimeImageDirectory)
        val process = ProcessBuilder(
            jlink.absolutePath,
            "--module-path",
            jmods.absolutePath,
            "--add-modules",
            "java.base,java.logging,java.naming,java.net.http,jdk.httpserver,jdk.jfr,jdk.unsupported",
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
    notCompatibleWithConfigurationCache(
        "Bundle assembly uses execution-time archive and filesystem operations."
    )
    dependsOn(runtimeImage, tasks.installDist, nativeMcpLauncher, ":mcp:jar")
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
        copy {
            from(project(":mcp").tasks.named<Jar>("jar").get().archiveFile)
            into(libDir)
        }
        copy { from(runtimeImageDirectory); into(root.resolve("runtime")) }
        val bin = root.resolve("bin")
        bin.mkdirs()
        val nativeLauncher = nativeMcpDirectory.get().asFile.resolve(bundlePlatform.get())
            .resolve(if (bundlePlatform.get().startsWith("windows")) "synesis-mcp.exe" else "synesis-mcp")
        val nativeInstaller = nativeMcpDirectory.get().asFile.resolve(bundlePlatform.get())
            .resolve(if (bundlePlatform.get().startsWith("windows")) "synesis-installer.exe" else "synesis-installer")
        require(nativeLauncher.isFile) { "Native MCP launcher missing for ${bundlePlatform.get()}: $nativeLauncher" }
        require(nativeInstaller.isFile) { "Native installer missing for ${bundlePlatform.get()}: $nativeInstaller" }
        copy { from(nativeLauncher, nativeInstaller); into(bin) }
        if (!bundlePlatform.get().startsWith("windows")) {
            require(bin.resolve("synesis-mcp").setExecutable(true)) {
                "Unable to mark Unix MCP launcher executable"
            }
            require(bin.resolve("synesis-installer").setExecutable(true)) {
                "Unable to mark Unix installer executable"
            }
        }
        bin.resolve("synesis.cmd").writeText(
            "@echo off\r\nsetlocal\r\nset \"APP_HOME=%~dp0..\"\r\nset \"SYNESIS_LAUNCHER=%~f0\"\r\n\"%APP_HOME%\\runtime\\bin\\java.exe\" --enable-native-access=ALL-UNNAMED -cp \"%APP_HOME%\\app\\synesis-cli.jar;%APP_HOME%\\app\\lib\\*\" org.synesis.cli.SynesisCli %*\r\nexit /b %ERRORLEVEL%\r\n"
        )
        bin.resolve("synesis").writeText(
            $$"#!/bin/sh\nAPP_HOME=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")/..\" && pwd)\"\nexport SYNESIS_LAUNCHER=\"$APP_HOME/bin/synesis\"\nexec \"$APP_HOME/runtime/bin/java\" --enable-native-access=ALL-UNNAMED -cp \"$APP_HOME/app/synesis-cli.jar:$APP_HOME/app/lib/*\" org.synesis.cli.SynesisCli \"$@\"\n"
        )
        root.resolve("VERSION").writeText(bundleVersion.get() + "\n")
        root.resolve("README.md").writeText(
            "Run bin/synesis-installer (Unix) or bin/synesis-installer.exe (Windows) to install, repair, or uninstall.\n" +
                    "After installation, run bin/synesis (Unix) or bin/synesis.cmd (Windows).\n"
        )
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

val bundleArchive = if (isWindows) platformZip else platformTarGz
val bundleArchiveFile = bundleArchive.flatMap { it.archiveFile }

tasks.register("bundleSmokeTest") {
    group = "verification"
    description = "Extracts and runs the platform archive outside the source tree."
    notCompatibleWithConfigurationCache(
        "Bundle smoke testing extracts archives and launches external processes."
    )
    dependsOn(bundleArchive)
    doLast {
        val smokeRoot = Files.createTempDirectory("synesis-bundle-smoke-").toFile()
        val archive = bundleArchiveFile.get().asFile
        val extractedRoot = smokeRoot.resolve("bundle")
        if (!isWindows) {
            require(platformBundleDirectory.get().asFile.resolve("bin/synesis").canExecute()) {
                "Unix bundle launcher is not executable before archiving"
            }
            require(platformBundleDirectory.get().asFile.resolve("bin/synesis-installer").canExecute()) {
                "Unix installer is not executable before archiving"
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
        val installer = bundleRoot.resolve("bin").resolve(if (isWindows) "synesis-installer.exe" else "synesis-installer")
        require(installer.isFile) { "Native installer missing from bundle: $installer" }
        if (!isWindows) {
            require(launcher.setExecutable(true)) { "Unable to restore Unix launcher permissions after extraction" }
            require(installer.setExecutable(true)) { "Unable to restore Unix installer permissions after extraction" }
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
            val installerCommand = if (isWindows) {
                mutableListOf("cmd.exe", "/c", installer.absolutePath, "version")
            } else {
                mutableListOf(installer.absolutePath, "version")
            }
            val installerResult = ProcessBuilder(installerCommand).directory(smokeRoot).redirectErrorStream(true).start()
            val installerOutput = installerResult.inputStream.bufferedReader().readText()
            require(installerResult.waitFor() == 0 && "SYNESIS_BOOTSTRAP_VERSION=" in installerOutput) {
                "Native installer version check failed:\n$installerOutput"
            }
            val project = smokeRoot.resolve("project")
            require(project.mkdirs()) { "Unable to create smoke project: $project" }
            fun git(vararg arguments: String) {
                val command = mutableListOf("git", "-C", project.absolutePath).apply { addAll(arguments) }
                val gitProcessBuilder = ProcessBuilder(command).directory(smokeRoot).redirectErrorStream(true)
                gitProcessBuilder.environment().putAll(
                    mapOf(
                        "GIT_CONFIG_NOSYSTEM" to "1",
                        "GIT_CONFIG_NOGLOBAL" to "1",
                        "GIT_TERMINAL_PROMPT" to "0",
                        "GIT_OPTIONAL_LOCKS" to "0",
                        "GIT_AUTHOR_NAME" to "Synesis Bundle Smoke",
                        "GIT_AUTHOR_EMAIL" to "synesis-bundle-smoke@example.invalid",
                        "GIT_COMMITTER_NAME" to "Synesis Bundle Smoke",
                        "GIT_COMMITTER_EMAIL" to "synesis-bundle-smoke@example.invalid",
                    ),
                )
                val gitProcess = gitProcessBuilder.start()
                gitProcess.outputStream.close()
                val output = CompletableFuture.supplyAsync {
                    val bytes = gitProcess.inputStream.readAllBytes()
                    String(bytes, StandardCharsets.UTF_8)
                }
                if (!gitProcess.waitFor(30, TimeUnit.SECONDS)) {
                    gitProcess.toHandle().descendants().forEach { it.destroyForcibly() }
                    gitProcess.destroyForcibly()
                    val diagnostic = output.get(5, TimeUnit.SECONDS)
                    throw GradleException(
                        "Smoke Git command timed out: ${command.joinToString(" ")}\n$diagnostic",
                    )
                }
                val gitOutput = output.get(5, TimeUnit.SECONDS)
                require(gitProcess.exitValue() == 0) {
                    "Smoke Git command failed: ${command.joinToString(" ")}\n$gitOutput"
                }
            }
            git("init")
            git("config", "user.name", "Synesis Bundle Smoke")
            git("config", "user.email", "synesis-bundle-smoke@example.invalid")
            project.resolve("README.md").writeText("Synesis bundle smoke project\n")
            git("add", ".")
            git("commit", "-m", "Initial smoke baseline")
            run("init", "--project", project.absolutePath)
            run("provider", "list", "--project", project.absolutePath)
            run("provider", "install", "claude", "--project", project.absolutePath)
            run("provider", "status", "claude", "--project", project.absolutePath)
            run("provider", "uninstall", "claude", "--project", project.absolutePath)
            run("doctor", "--project", project.absolutePath)

            // Installed stdio MCP smoke test
            val mcpCommand = (if (isWindows) mutableListOf("cmd.exe", "/c", launcher.absolutePath)
            else mutableListOf(launcher.absolutePath)).apply {
                addAll(
                    listOf(
                        "mcp",
                        "--provider",
                        "codex",
                        "--project",
                        project.absolutePath,
                        "--connection-instance-id",
                        "smoke-conn-1"
                    )
                )
            }
            val mcpProcess = ProcessBuilder(mcpCommand).directory(smokeRoot).start()
            val mcpWriter = mcpProcess.outputStream.bufferedWriter(Charsets.UTF_8)
            val mcpReader = mcpProcess.inputStream.bufferedReader(Charsets.UTF_8)
            mcpWriter.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n")
            mcpWriter.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n")
            mcpWriter.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}\n")
            mcpWriter.flush()
            mcpWriter.close()

            val line1 = mcpReader.readLine()
            val line2 = mcpReader.readLine()
            val line3 = mcpReader.readLine()
            require(mcpProcess.waitFor() == 0) { "MCP process failed to exit 0" }
            require(line1 != null && line1.contains("protocolVersion")) { "MCP initialize failed: $line1" }
            require(line2 != null && line2.contains("\"name\":\"ensure_session\"")) { "MCP tools/list failed: $line2" }
            require(line3 != null && line3.contains("ready")) { "MCP ensure_session failed: $line3" }
        } finally {
            delete(smokeRoot)
        }
    }
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis")
}

tasks.register("distributionCheck") {
    group = "verification"
    description = "Runs distribution launcher and archive smoke checks explicitly."
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis", "launcherSmoke", "bundleSmokeTest")
}
