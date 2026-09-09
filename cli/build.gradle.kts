import org.gradle.internal.os.OperatingSystem
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.jar.JarFile
import java.util.zip.ZipFile

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
val protectionReleaseId = providers.gradleProperty("synesisReleaseId")
    .orElse(providers.environmentVariable("SYNESIS_RELEASE_ID").orElse("local"))
val protectionSeed = providers.gradleProperty("synesisProtectionSeed")
    .orElse(providers.environmentVariable("SYNESIS_PROTECTION_SEED").orElse("UNSET"))
val protectionJmods = providers.gradleProperty("synesisJmods")
    .orElse(providers.environmentVariable("SYNESIS_JMODS").orElse(""))
val launcherBat = layout.buildDirectory.file("install/synesis/bin/synesis.bat")
val launcherUnix = layout.buildDirectory.file("install/synesis/bin/synesis")
val cliSourceDirectory = layout.projectDirectory.dir("src").asFile
val cliBuildFile = layout.projectDirectory.file("build.gradle.kts").asFile
val protectionLiteConfiguration = configurations.create("protectionLite")
val protectionLiteDirectory = layout.buildDirectory.dir("protection-lite")
val protectionLiteBundleDirectory = protectionLiteDirectory.map { it.dir("bundle") }
val protectionLitePrivateDirectory = protectionLiteDirectory.map { it.dir("private") }
val protectionLiteJar = protectionLiteDirectory.map { it.file("synesis-cli-protection-lite.jar") }
val protectionLiteMapping = protectionLitePrivateDirectory.map { it.file("mapping.txt") }
val protectionLiteSeeds = protectionLitePrivateDirectory.map { it.file("seeds.txt") }
val protectionLiteUsage = protectionLitePrivateDirectory.map { it.file("usage.txt") }
val protectionLiteArtifactManifest = protectionLitePrivateDirectory.map { it.file("artifact-manifest.txt") }
val protectionLiteProvenance = protectionLitePrivateDirectory.map { it.file("provenance.json") }
val protectionLiteRules = layout.projectDirectory.file("src/release/proguard/protection-lite.pro")

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
    implementation(project(":web-ui"))
    // The CLI keeps the compile-time boundary via reflection, but the installed
    // distribution must carry the MCP server so `synesis mcp` is runnable.
    runtimeOnly(project(":mcp"))
    implementation(libs.picocli)
    implementation(libs.zxing.core)
    add("protectionLite", libs.proguard.base)
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
            for ((binaryName, packagePath) in listOf(
                "synesis-mcp$suffix" to "./cmd/synesis-mcp",
                "synesis-installer$suffix" to "."
            )) {
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
            "java.base,java.desktop,java.logging,java.naming,java.net.http,jdk.httpserver,jdk.jfr,jdk.unsupported",
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
            $$"""#!/bin/sh
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
export SYNESIS_LAUNCHER="$APP_HOME/bin/synesis"
exec "$APP_HOME/runtime/bin/java" --enable-native-access=ALL-UNNAMED -cp "$APP_HOME/app/synesis-cli.jar:$APP_HOME/app/lib/*" org.synesis.cli.SynesisCli "$@"
"""
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

// The CLI script is evaluated before some sibling Java plugins register their
// lazy `jar` tasks. Evaluate only these release inputs before resolving the
// providers; the normal build tasks remain unchanged.
listOf(":link", ":project-record", ":workspace", ":coordination", ":mcp-contract", ":mcp", ":web-ui")
    .forEach { evaluationDependsOn(it) }

val protectionLiteOwnedJarProviders = listOf(
    project(":link").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    tasks.named<Jar>("jar").flatMap { it.archiveFile },
    project(":project-record").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    project(":workspace").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    project(":coordination").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    project(":mcp-contract").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    project(":mcp").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    project(":web-ui").tasks.named<Jar>("jar").flatMap { it.archiveFile },
)

val protectionLiteInputTasks = listOf(
    tasks.named<Jar>("jar"),
    project(":link").tasks.named<Jar>("jar"),
    project(":project-record").tasks.named<Jar>("jar"),
    project(":workspace").tasks.named<Jar>("jar"),
    project(":coordination").tasks.named<Jar>("jar"),
    project(":mcp-contract").tasks.named<Jar>("jar"),
    project(":mcp").tasks.named<Jar>("jar"),
    project(":web-ui").tasks.named<Jar>("jar"),
)

val protectionLite = tasks.register<JavaExec>("protectionLite") {
    group = "distribution"
    description = "Builds the explicit ProGuard protection-lite JVM payload."
    notCompatibleWithConfigurationCache(
        "Protection-lite resolves toolchain/library paths and invokes an external protector."
    )
    dependsOn(protectionLiteInputTasks)
    classpath = protectionLiteConfiguration
    mainClass.set("proguard.ProGuard")
    inputs.files(protectionLiteOwnedJarProviders)
    inputs.file(protectionLiteRules)
    outputs.files(protectionLiteJar, protectionLiteMapping, protectionLiteSeeds, protectionLiteUsage)
    doFirst {
        val outputJar = protectionLiteJar.get().asFile
        val privateDirectory = protectionLitePrivateDirectory.get().asFile
        delete(protectionLiteDirectory.get().asFile)
        outputJar.parentFile.mkdirs()
        privateDirectory.mkdirs()
        val ownedJars = protectionLiteOwnedJarProviders.map { it.get().asFile }.distinct()
        require(ownedJars.all { it.isFile }) { "Protection-lite input JAR missing: $ownedJars" }
        val ownedPaths = ownedJars.map { it.absoluteFile.normalize() }.toSet()
        val externalJars = configurations.runtimeClasspath.get().files
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .filterNot { it.absoluteFile.normalize() in ownedPaths }
            .distinctBy { it.absoluteFile.normalize() }
        val javaHome = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().metadata.installationPath.asFile
        val configuredJmods = protectionJmods.get().trim()
        val jmods = if (configuredJmods.isBlank()) javaHome.resolve("jmods") else file(configuredJmods)
        require(jmods.exists()) { "Java library image path missing: $jmods" }
        val requiredJmodNames = setOf(
            "java.base.jmod", "java.desktop.jmod", "java.logging.jmod", "java.naming.jmod",
            "java.net.http.jmod", "jdk.httpserver.jmod", "jdk.jfr.jmod", "jdk.unsupported.jmod",
        )
        val jmodFiles = if (jmods.isDirectory) {
            requiredJmodNames.mapNotNull { name -> jmods.resolve(name).takeIf { it.isFile } }
        } else {
            listOf(jmods)
        }
        require(jmodFiles.isNotEmpty()) { "Java library modules are required for protection-lite analysis: $jmods" }
        setArgs(
            buildList {
                addAll(listOf("-include", protectionLiteRules.asFile.absolutePath))
                ownedJars.forEach { addAll(listOf("-injars", it.absolutePath + "(!META-INF/MANIFEST.MF)")) }
                addAll(listOf("-outjars", outputJar.absolutePath))
                jmodFiles.forEach {
                    val path = if (it.extension.equals("jmod", ignoreCase = true)) {
                        it.absolutePath + "(!**.jar;!module-info.class)"
                    } else {
                        it.absolutePath
                    }
                    addAll(listOf("-libraryjars", path))
                }
                externalJars.forEach { addAll(listOf("-libraryjars", it.absolutePath)) }
                addAll(listOf(
                    "-printmapping", protectionLiteMapping.get().asFile.absolutePath,
                    "-printseeds", protectionLiteSeeds.get().asFile.absolutePath,
                    "-printusage", protectionLiteUsage.get().asFile.absolutePath,
                ))
            },
        )
    }
}

val protectionLiteBundle = tasks.register<Sync>("protectionLiteBundle") {
    group = "distribution"
    description = "Stages a self-contained protection-lite bundle without mutating the developer bundle."
    dependsOn(platformBundle, protectionLite)
    into(protectionLiteBundleDirectory)
    from(platformBundleDirectory)
    doLast {
        val bundleRoot = protectionLiteBundleDirectory.get().asFile
        val app = bundleRoot.resolve("app")
        val appLib = app.resolve("lib")
        require(app.isDirectory && appLib.isDirectory) { "Staged bundle layout is incomplete: $bundleRoot" }
        val ownedNames = protectionLiteOwnedJarProviders.map { it.get().asFile.name }.toSet()
        appLib.listFiles()?.filter { it.name in ownedNames }?.forEach { delete(it) }
        val protectedCliJar = app.resolve("synesis-cli.jar")
        delete(protectedCliJar)
        copy {
            from(protectionLiteJar)
            into(app)
            rename { "synesis-cli.jar" }
        }
        bundleRoot.resolve("PROTECTION_PROFILE").writeText("protection-lite\n")
    }
}

val protectionLiteArchive = tasks.register<Zip>("protectionLiteArchive") {
    group = "distribution"
    description = "Archives the staged protection-lite bundle as a shipped candidate artifact."
    dependsOn(protectionLiteBundle)
    archiveFileName.set("synesis-${bundleVersion.get()}-${bundlePlatform.get()}-protection-lite.zip")
    destinationDirectory.set(protectionLiteDirectory)
    from(protectionLiteBundleDirectory) {
        into("synesis-${bundleVersion.get()}-${bundlePlatform.get()}-protection-lite")
    }
}

val protectionLiteBundleSmokeTest = tasks.register("protectionLiteBundleSmokeTest") {
    group = "verification"
    description = "Extracts and runs bounded CLI, UI, provider, MCP, and native checks against the protection-lite archive."
    notCompatibleWithConfigurationCache(
        "Protection-lite smoke testing extracts an archive and launches external processes."
    )
    dependsOn(protectionLiteArchive)
    doLast {
        val smokeRoot = Files.createTempDirectory("synesis-protection-lite-smoke-").toFile()
        val archive = protectionLiteArchive.get().archiveFile.get().asFile
        val extractedRoot = smokeRoot.resolve("bundle")
        copy {
            from(zipTree(archive))
            into(extractedRoot)
        }
        val bundleRoot = extractedRoot.resolve("synesis-${bundleVersion.get()}-${bundlePlatform.get()}-protection-lite")
        val launcher = bundleRoot.resolve("bin").resolve(if (isWindows) "synesis.cmd" else "synesis")
        val installer = bundleRoot.resolve("bin").resolve(if (isWindows) "synesis-installer.exe" else "synesis-installer")
        val nativeMcp = bundleRoot.resolve("bin").resolve(if (isWindows) "synesis-mcp.exe" else "synesis-mcp")
        require(launcher.isFile) { "Protection-lite launcher missing: $launcher" }
        require(installer.isFile) { "Protection-lite native installer missing: $installer" }
        require(nativeMcp.isFile) { "Protection-lite native MCP launcher missing: $nativeMcp" }
        if (!isWindows) {
            require(launcher.setExecutable(true)) { "Unable to restore Unix protected launcher permissions" }
            require(installer.setExecutable(true)) { "Unable to restore Unix protected installer permissions" }
            require(nativeMcp.setExecutable(true)) { "Unable to restore Unix protected MCP launcher permissions" }
            require(bundleRoot.resolve("runtime/bin/java").setExecutable(true)) {
                "Unable to restore Unix protected runtime permissions"
            }
        }
        fun launcherCommand(vararg arguments: String): MutableList<String> =
            (if (isWindows) mutableListOf("cmd.exe", "/c", launcher.absolutePath)
            else mutableListOf(launcher.absolutePath)).apply { addAll(arguments) }

        fun runWithExit(expectedExitCode: Int, vararg arguments: String): String {
            val process = ProcessBuilder(launcherCommand(*arguments)).directory(smokeRoot)
                .redirectErrorStream(true)
                .apply { environment()["JAVA_HOME"] = smokeRoot.resolve("missing-java").absolutePath }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            require(process.waitFor() == expectedExitCode) {
                "Protection-lite command failed: ${arguments.joinToString(" ")}\n$output"
            }
            return output
        }

        fun run(vararg arguments: String): String = runWithExit(0, *arguments)

        fun installerVersion(installer: java.io.File, workingDirectory: java.io.File): String {
            val command = if (isWindows) {
                mutableListOf("cmd.exe", "/c", installer.absolutePath, "version")
            } else {
                mutableListOf(installer.absolutePath, "version")
            }
            val process = ProcessBuilder(command).directory(workingDirectory).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            require(process.waitFor() == 0) { "Protected native installer version check failed:\n$output" }
            return output
        }

        fun git(vararg arguments: String) {
            val command = mutableListOf("git", "-C", smokeRoot.resolve("project").absolutePath).apply { addAll(arguments) }
            val process = ProcessBuilder(command).directory(smokeRoot).redirectErrorStream(true).apply {
                environment().putAll(
                    mapOf(
                        "GIT_CONFIG_NOSYSTEM" to "1",
                        "GIT_CONFIG_NOGLOBAL" to "1",
                        "GIT_TERMINAL_PROMPT" to "0",
                        "GIT_OPTIONAL_LOCKS" to "0",
                        "GIT_AUTHOR_NAME" to "Synesis Protection Smoke",
                        "GIT_AUTHOR_EMAIL" to "synesis-protection-smoke@example.invalid",
                        "GIT_COMMITTER_NAME" to "Synesis Protection Smoke",
                        "GIT_COMMITTER_EMAIL" to "synesis-protection-smoke@example.invalid",
                    ),
                )
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            require(process.waitFor() == 0) { "Protection smoke Git command failed: ${command.joinToString(" ")}\n$output" }
        }

        try {
            require(run("version").contains("SYNESIS_VERSION=")) { "Protected version output missing" }
            require(run("--help").contains("Usage:")) { "Protected help output missing" }
            require(run("ui", "--help").contains("Start Synesis locally")) {
                "Protected UI command metadata missing"
            }
            require(installerVersion(installer, smokeRoot).contains("SYNESIS_BOOTSTRAP_VERSION=")) {
                "Protected native installer version output missing"
            }
            val nativeMcpProcess = ProcessBuilder(mutableListOf(nativeMcp.absolutePath, "version"))
                .directory(smokeRoot).redirectErrorStream(true).start()
            val nativeMcpOutput = nativeMcpProcess.inputStream.bufferedReader().readText()
            require(nativeMcpProcess.waitFor() == 0 && nativeMcpOutput.contains("SYNESIS_VERSION=")) {
                "Protected native MCP launcher failed to reach the protected CLI:\n$nativeMcpOutput"
            }
            val profileMarker = bundleRoot.resolve("PROTECTION_PROFILE")
            require(profileMarker.readText() == "protection-lite\n") { "Protection profile marker missing" }

            val project = smokeRoot.resolve("project")
            require(project.mkdirs()) { "Unable to create protected smoke project: $project" }
            git("init")
            git("config", "user.name", "Synesis Protection Smoke")
            git("config", "user.email", "synesis-protection-smoke@example.invalid")
            project.resolve("README.md").writeText("Synesis protection smoke project\n")
            git("add", ".")
            git("commit", "-m", "Initial protection smoke baseline")
            run("init", "--project", project.absolutePath)
            run("provider", "list", "--project", project.absolutePath)
            run("provider", "install", "claude", "--project", project.absolutePath)
            run("provider", "status", "claude", "--project", project.absolutePath)
            run("provider", "uninstall", "claude", "--project", project.absolutePath)
            run("provider", "install", "codex", "--project", project.absolutePath)
            run("doctor", "--project", project.absolutePath)
            run("ui", "--project", project.absolutePath, "--duration-seconds", "1", "--no-browser")

            val mcpProcess = ProcessBuilder(
                launcherCommand(
                    "mcp", "--provider", "codex", "--project", project.absolutePath,
                    "--connection-instance-id", "protection-smoke-1",
                ),
            ).directory(smokeRoot).start()
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
            require(mcpProcess.waitFor() == 0) { "Protected MCP process failed to exit 0" }
            require(line1 != null && line1.contains("protocolVersion")) { "Protected MCP initialize failed: $line1" }
            require(line2 != null && line2.contains("\"name\":\"ensure_session\"")) {
                "Protected MCP tools/list failed: $line2"
            }
            require(line3 != null && line3.contains("ready")) { "Protected MCP ensure_session failed: $line3" }
        } finally {
            delete(smokeRoot)
        }
    }
}

val protectionLiteProvenanceTask = tasks.register("protectionLiteProvenance") {
    group = "distribution"
    description = "Writes the private protection-lite provenance record outside the customer bundle."
    dependsOn(protectionLite, protectionLiteArchive)
    outputs.files(protectionLiteProvenance, protectionLiteArtifactManifest)
    doLast {
        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun json(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")

        fun gitValue(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git") + arguments.toList())
                .directory(rootProject.layout.projectDirectory.asFile)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            require(process.waitFor() == 0) {
                "Unable to read Git provenance (${arguments.joinToString(" ")}): $output"
            }
            return output
        }

        val configuredCommit = githubSha.get().trim()
        val sourceCommit = configuredCommit
            .takeIf { it.isNotBlank() && it != "UNKNOWN" }
            ?: gitValue("rev-parse", "HEAD")
        val dirtyTree = gitValue("status", "--porcelain", "--untracked-files=all").isNotBlank()
        val protectionJavaHome = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().metadata.installationPath.asFile
        val configuredJmods = protectionJmods.get().trim()
        val libraryJmods = if (configuredJmods.isBlank()) {
            protectionJavaHome.resolve("jmods")
        } else {
            file(configuredJmods)
        }
        val jarHash = sha256(protectionLiteJar.get().asFile)
        val archive = protectionLiteArchive.get().archiveFile.get().asFile
        val archiveHash = sha256(archive)
        val rulesHash = sha256(protectionLiteRules.asFile)
        val bundleRoot = protectionLiteBundleDirectory.get().asFile
        val manifestLines = Files.walk(bundleRoot.toPath()).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) }
                .map { path ->
                    val relative = bundleRoot.toPath().relativize(path).toString()
                        .replace(File.separatorChar, '/')
                    "$relative\t${sha256(path.toFile())}"
                }
                .sorted()
                .toList()
        }
        val manifestText = "# SYNESIS_PROTECTION_LITE_MANIFEST_V1\n" +
                manifestLines.joinToString("\n", postfix = "\n")
        protectionLiteArtifactManifest.get().asFile.parentFile.mkdirs()
        protectionLiteArtifactManifest.get().asFile.writeText(manifestText)
        val manifestHash = sha256(protectionLiteArtifactManifest.get().asFile)
        val record = """{
  "schema": 1,
  "profile": "protection-lite",
  "releaseId": "${json(protectionReleaseId.get())}",
  "sourceCommit": "${json(sourceCommit)}",
  "dirtyTree": $dirtyTree,
  "tool": "ProGuard",
  "toolVersion": "${libs.versions.proguard.get()}",
  "javaRuntime": "$runtimeVersion",
  "libraryJmods": "${json(libraryJmods.absolutePath)}",
  "rulesSha256": "$rulesHash",
  "seed": "${json(protectionSeed.get())}",
  "artifact": "${protectionLiteJar.get().asFile.name}",
  "artifactSha256": "$jarHash",
  "shippedArchive": "${archive.name}",
  "shippedArchiveSha256": "$archiveHash",
  "artifactManifest": "${protectionLiteArtifactManifest.get().asFile.name}",
  "artifactManifestSha256": "$manifestHash",
  "mapping": "${protectionLiteMapping.get().asFile.name}",
  "seeds": "${protectionLiteSeeds.get().asFile.name}",
  "usage": "${protectionLiteUsage.get().asFile.name}"
}
""".trimIndent() + "\n"
        protectionLiteProvenance.get().asFile.parentFile.mkdirs()
        protectionLiteProvenance.get().asFile.writeText(record)
    }
}

val protectionLiteIntegrityCheck = tasks.register("protectionLiteIntegrityCheck") {
    group = "verification"
    description = "Checks the private protection-lite artifact manifest and non-shipping support boundary."
    dependsOn(protectionLiteProvenanceTask)
    notCompatibleWithConfigurationCache(
        "Integrity acceptance inspects generated archives and private provenance files."
    )
    doLast {
        fun sha256Bytes(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        fun sha256(file: java.io.File): String = sha256Bytes(file.readBytes())

        val bundleRoot = protectionLiteBundleDirectory.get().asFile
        val manifestFile = protectionLiteArtifactManifest.get().asFile
        val manifestLines = manifestFile.readLines()
        require(manifestLines.firstOrNull() == "# SYNESIS_PROTECTION_LITE_MANIFEST_V1") {
            "Protection-lite artifact manifest header missing"
        }
        val entries = manifestLines.drop(1).filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t', limit = 2)
            require(parts.size == 2 && parts[0].isNotBlank() && parts[1].matches(Regex("[0-9a-f]{64}"))) {
                "Malformed protection-lite manifest line: $line"
            }
            parts[0] to parts[1]
        }
        require(entries.isNotEmpty()) { "Protection-lite artifact manifest is empty" }
        require(entries.map { it.first }.distinct().size == entries.size) {
            "Protection-lite artifact manifest contains duplicate paths"
        }
        entries.forEach { (relative, expectedHash) ->
            val file = bundleRoot.resolve(relative.replace('/', File.separatorChar)).normalize()
            require(file.toPath().startsWith(bundleRoot.toPath())) {
                "Protection-lite manifest escapes bundle root: $relative"
            }
            require(file.isFile) { "Protected artifact missing from manifest: $relative" }
            require(sha256(file) == expectedHash) { "Protected artifact hash mismatch: $relative" }
        }

        require(!bundleRoot.resolve("private").exists()) {
            "Private protection material entered the customer bundle"
        }
        val mapping = protectionLiteMapping.get().asFile.readText()
        require(mapping.contains("org.synesis.cli.SynesisCli -> org.synesis.cli.SynesisCli:")) {
            "Protected CLI entrypoint is absent from the private mapping"
        }
        require(mapping.contains("org.synesis.cli.bootstrap.CliRuntime -> org.synesis.cli.a.a:")) {
            "Expected transformed CLI implementation is absent from the private mapping"
        }
        ZipFile(protectionLiteArchive.get().archiveFile.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.none { name ->
                name.endsWith(".java") || name.endsWith(".map") || name.endsWith(".sourcemap") ||
                        name.contains("mapping.txt") || name.contains("seeds.txt") || name.contains("usage.txt") ||
                        name.contains("provenance.json") || name.contains("artifact-manifest.txt")
            }) { "Private/source material leaked into the protection-lite archive" }
            require(names.any { it.endsWith("/app/synesis-cli.jar") }) {
                "Protected CLI payload is absent from the protection-lite archive"
            }
        }
        JarFile(protectionLiteJar.get().asFile).use { jar ->
            require(jar.entries().asSequence().any { it.name == "web-ui/index.html" }) {
                "Packaged UI resource is absent from the protected CLI payload"
            }
            require(jar.entries().asSequence().any { it.name.startsWith("org/synesis/a/") && it.name.endsWith(".class") }) {
                "Protected CLI payload contains no renamed implementation class"
            }
        }

        val versionEntry = entries.singleOrNull { it.first == "VERSION" }
        require(versionEntry != null) { "Protection-lite manifest lacks VERSION" }
        val tamperedHash = sha256Bytes((bundleRoot.resolve("VERSION").readBytes() + "tampered".toByteArray()))
        require(tamperedHash != versionEntry.second) {
            "Protection-lite manifest failed the non-mutating tamper-difference check"
        }
    }
}

tasks.register("maximumRelease") {
    group = "distribution"
    description = "Fails closed until a licensed maximum-profile protector is configured."
    doLast {
        val tool = providers.gradleProperty("synesisMaximumProtector")
            .orElse(providers.environmentVariable("SYNESIS_MAXIMUM_PROTECTOR")).orNull
        require(!tool.isNullOrBlank()) {
            "Maximum release is blocked: set -PsynesisMaximumProtector or SYNESIS_MAXIMUM_PROTECTOR " +
                    "to a licensed, version-pinned protector integration; protection-lite is not a substitute."
        }
        throw GradleException(
            "Maximum release integration is not enabled for '$tool'; no commercial protector is installed in this checkout."
        )
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
val runnableInstallerFile = layout.buildDirectory.file(
    "distributions/synesis-${bundlePlatform.get()}${if (bundlePlatform.get().startsWith("windows")) ".exe" else ""}"
)
val selfExtractMagic = "SYNESIS_SELF_EXTRACT_V1\n".toByteArray(StandardCharsets.UTF_8)

val runnableInstaller = tasks.register("runnableInstaller") {
    group = "distribution"
    description = "Creates a single-file self-extracting Synesis installer."
    notCompatibleWithConfigurationCache(
        "Single-file installer assembly appends the platform archive to a native executable."
    )
    dependsOn(bundleArchive, nativeMcpLauncher)
    inputs.file(bundleArchiveFile)
    inputs.property("bundlePlatform", bundlePlatform)
    outputs.file(runnableInstallerFile)
    doLast {
        val archive = bundleArchiveFile.get().asFile
        val nativeInstaller = nativeMcpDirectory.get().asFile.resolve(bundlePlatform.get())
            .resolve(if (bundlePlatform.get().startsWith("windows")) "synesis-installer.exe" else "synesis-installer")
        val output = runnableInstallerFile.get().asFile
        output.parentFile.mkdirs()
        require(nativeInstaller.isFile) { "Native installer missing: $nativeInstaller" }
        Files.copy(nativeInstaller.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.newOutputStream(output.toPath(), StandardOpenOption.APPEND).use { stream ->
            Files.copy(archive.toPath(), stream)
            val footer = DataOutputStream(stream)
            footer.write(selfExtractMagic)
            footer.writeLong(archive.length())
            footer.flush()
        }
        if (!bundlePlatform.get().startsWith("windows")) {
            require(output.setExecutable(true)) { "Unable to mark runnable installer executable" }
        }
    }
}

tasks.register("bundleSmokeTest") {
    group = "verification"
    description = "Extracts and runs the platform archive outside the source tree."
    notCompatibleWithConfigurationCache(
        "Bundle smoke testing extracts archives and launches external processes."
    )
    dependsOn(bundleArchive, runnableInstaller)
    doLast {
        val smokeRoot = Files.createTempDirectory("synesis-bundle-smoke-").toFile()
        val archive = bundleArchiveFile.get().asFile
        val standaloneInstaller = runnableInstallerFile.get().asFile
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
        val installer =
            bundleRoot.resolve("bin").resolve(if (isWindows) "synesis-installer.exe" else "synesis-installer")
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
            val standaloneCommand = if (isWindows) {
                mutableListOf("cmd.exe", "/c", standaloneInstaller.absolutePath)
            } else {
                mutableListOf(standaloneInstaller.absolutePath)
            }
            val standaloneProcess = ProcessBuilder(standaloneCommand).directory(smokeRoot)
                .redirectErrorStream(true).start()
            standaloneProcess.outputStream.bufferedWriter().use { it.write("4\n") }
            val standaloneOutput = standaloneProcess.inputStream.bufferedReader().readText()
            require(standaloneProcess.waitFor() == 0 && "Synesis Installer" in standaloneOutput) {
                "Single-file installer smoke test failed:\n$standaloneOutput"
            }
            run("version")
            val installerCommand = if (isWindows) {
                mutableListOf("cmd.exe", "/c", installer.absolutePath, "version")
            } else {
                mutableListOf(installer.absolutePath, "version")
            }
            val installerResult =
                ProcessBuilder(installerCommand).directory(smokeRoot).redirectErrorStream(true).start()
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
            run("provider", "install", "codex", "--project", project.absolutePath)
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
