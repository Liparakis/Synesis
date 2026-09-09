import org.gradle.internal.os.OperatingSystem
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
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
val maximumProtector = providers.gradleProperty("synesisMaximumProtector")
    .orElse(providers.environmentVariable("SYNESIS_MAXIMUM_PROTECTOR").orElse(""))
val maximumProtectorConfig = providers.gradleProperty("synesisMaximumConfig")
    .orElse(providers.environmentVariable("SYNESIS_MAXIMUM_CONFIG").orElse(""))
val maximumReleaseDirectory = layout.buildDirectory.dir("maximum-release")
val maximumReleaseBundleDirectory = maximumReleaseDirectory.map { it.dir("bundle") }
val maximumReleasePrivateDirectory = maximumReleaseDirectory.map { it.dir("private") }
val maximumReleaseRequest = maximumReleaseDirectory.map { it.file("request.properties") }
val maximumReleaseResult = maximumReleaseDirectory.map { it.file("result.properties") }
val maximumReleaseArtifactManifest = maximumReleasePrivateDirectory.map { it.file("artifact-manifest.txt") }
val maximumReleaseManifest = maximumReleaseDirectory.map { it.file("manifest.json") }
val maximumReleaseSignature = maximumReleaseDirectory.map { it.file("manifest.json.sig") }
val maximumDeveloperArchive = providers.gradleProperty("synesisDeveloperArchive")
    .orElse(providers.environmentVariable("SYNESIS_DEVELOPER_ARCHIVE"))
val maximumNativeHardeningEvidence = maximumReleasePrivateDirectory.map { it.file("native-hardening.json") }

fun writeMaximumProperties(file: File, values: Map<String, String>) {
    val properties = Properties()
    values.forEach { (key, value) -> properties.setProperty(key, value) }
    file.parentFile.mkdirs()
    file.outputStream().use { properties.store(it, "Synesis maximum-release adapter contract") }
}

fun readMaximumProperties(file: File): Properties {
    require(file.isFile) { "Maximum-release adapter result is missing: $file" }
    return Properties().also { properties -> file.inputStream().use(properties::load) }
}

fun maximumProperty(properties: Properties, key: String): String = properties.getProperty(key)?.trim().orEmpty()

fun maximumSha256(file: File): String {
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

fun validateMaximumRetraceEvidence(file: File, mappingFile: File, label: String) {
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    fun value(key: String): String = properties.getProperty(key)?.trim().orEmpty()
    require(value("schema") == "1") { "$label retrace evidence schema must be 1" }
    require(value("status") == "verified") { "$label retrace evidence is not verified" }
    require(value("retraceTool").isNotBlank()) { "$label retrace tool is missing" }
    require(value("testCase").isNotBlank()) { "$label retrace test case is missing" }
    require(value("mappingSha256").equals(maximumSha256(mappingFile), ignoreCase = true)) {
        "$label retrace evidence does not bind to the private mapping hash"
    }
    listOf("inputStackTraceSha256", "outputStackTraceSha256").forEach { key ->
        require(value(key).matches(Regex("[0-9a-fA-F]{64}"))) {
            "$label retrace evidence $key is missing or malformed"
        }
    }
}

fun maximumNativeAuditValue(file: File, key: String): String {
    val pattern = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    return pattern.find(file.readText())?.groupValues?.get(1).orEmpty()
}

fun rejectMaximumSymlinks(root: File, label: String) {
    if (!root.exists()) return
    val symbolicLink = Files.walk(root.toPath()).use { paths ->
        paths.filter { Files.isSymbolicLink(it) }.findFirst().orElse(null)
    }
    require(symbolicLink == null) {
        "$label contains an unsupported symbolic link: $symbolicLink"
    }
}

private val releaseLockfileNames = setOf(
    "gradle.lockfile",
    "settings-gradle.lockfile",
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
    "gradle-wrapper.properties",
    "libs.versions.toml",
)

fun releaseToolVersion(vararg command: String): String {
    return try {
        val process = ProcessBuilder(command.toList())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.isNotBlank()) {
            output.lineSequence().first().trim()
        } else {
            "NOT_AVAILABLE"
        }
    } catch (_: Exception) {
        "NOT_AVAILABLE"
    }
}

fun releaseLockfileSnapshot(root: File): Pair<Int, String> {
    val excludedDirectories = setOf(".git", "build", "node_modules")
    val files = root.walkTopDown()
        .onEnter { directory -> directory.name !in excludedDirectories }
        .filter { file ->
            file.isFile && file.name in releaseLockfileNames &&
                    file.relativeTo(root).invariantSeparatorsPath.split('/').none { it in excludedDirectories }
        }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .toList()
    val lines = files.joinToString("\n", postfix = if (files.isEmpty()) "" else "\n") { file ->
        "${file.relativeTo(root).invariantSeparatorsPath}\t${maximumSha256(file)}"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(lines.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return files.size to digest
}

fun maximumReleaseProvenance(
    root: File,
    sourceCommit: String,
    dirtyTree: Boolean,
    releaseId: String,
    seed: String,
    protectorName: String,
    protectorVersion: String,
    configuration: File,
): Map<String, String> {
    val (lockfileCount, lockfilesSha256) = releaseLockfileSnapshot(root)
    val nodeVersion = releaseToolVersion("node", "--version")
    val npmVersion = releaseToolVersion("npm", "--version")
    val nativeToolchain = releaseToolVersion("go", "version")
    require(nodeVersion != "NOT_AVAILABLE") {
        "Maximum release provenance requires the Node.js toolchain to be available"
    }
    require(npmVersion != "NOT_AVAILABLE") {
        "Maximum release provenance requires the npm toolchain to be available"
    }
    require(nativeToolchain != "NOT_AVAILABLE") {
        "Maximum release provenance requires the Go native toolchain to be available"
    }
    return linkedMapOf(
        "sourceCommit" to sourceCommit,
        "dirtyTree" to dirtyTree.toString(),
        "releaseId" to releaseId,
        "seed" to seed,
        "protectorName" to protectorName,
        "protectorVersion" to protectorVersion,
        "configuration" to configuration.absolutePath,
        "configurationSha256" to maximumSha256(configuration),
        "lockfileCount" to lockfileCount.toString(),
        "lockfilesSha256" to lockfilesSha256,
        "gradleVersion" to gradle.gradleVersion,
        "javaRuntime" to Runtime.version().toString(),
        "javaToolchain" to "25",
        "nodeVersion" to nodeVersion,
        "npmVersion" to npmVersion,
        "nativeToolchain" to nativeToolchain,
    )
}

fun maximumHexDecode(value: String): ByteArray {
    require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]+"))) {
        "Expected an even-length hexadecimal value"
    }
    return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

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

val maximumReleasePrepare = tasks.register("maximumReleasePrepare") {
    group = "distribution"
    description = "Invokes the supplied licensed maximum-protection adapter and validates its private evidence contract."
    notCompatibleWithConfigurationCache(
        "Maximum protection invokes a release-environment adapter and inspects customer/private output boundaries."
    )
    dependsOn(platformBundle)
    inputs.dir(platformBundleDirectory)
    outputs.dir(maximumReleaseBundleDirectory)
    outputs.dir(maximumReleasePrivateDirectory)
    doLast {
        val protectorValue = maximumProtector.get().trim()
        require(protectorValue.isNotBlank()) {
            "Maximum release is blocked: set -PsynesisMaximumProtector or SYNESIS_MAXIMUM_PROTECTOR " +
                    "to a licensed, version-pinned protector adapter; protection-lite is not a substitute."
        }
        val configValue = maximumProtectorConfig.get().trim()
        require(configValue.isNotBlank()) {
            "Maximum release is blocked: set -PsynesisMaximumConfig or SYNESIS_MAXIMUM_CONFIG " +
                    "to the private, version-pinned protector configuration."
        }
        val protectorFile = project.file(protectorValue).absoluteFile
        val configFile = project.file(configValue).absoluteFile
        require(protectorFile.isFile) { "Maximum protector adapter is not a file: $protectorFile" }
        require(configFile.isFile) { "Maximum protector configuration is not a file: $configFile" }
        val sourceCheckout = rootProject.layout.projectDirectory.asFile.absoluteFile
        fun normalizedPath(file: File): String = file.toPath().toAbsolutePath().normalize().toString()
            .let { path -> if (isWindows) path.lowercase(Locale.ROOT) else path }
        fun isWithin(child: String, parent: String): Boolean {
            val boundary = parent.trimEnd(File.separatorChar) + File.separator
            return child == parent || child.startsWith(boundary)
        }
        val sourceCheckoutPath = normalizedPath(sourceCheckout)
        val canonicalSourceCheckoutPath = normalizedPath(sourceCheckout.canonicalFile)
        fun isInSourceCheckout(file: File): Boolean =
            isWithin(normalizedPath(file), sourceCheckoutPath) ||
                    isWithin(normalizedPath(file.canonicalFile), canonicalSourceCheckoutPath)
        require(!isInSourceCheckout(protectorFile)) {
            "Maximum protector adapter must be supplied outside the source checkout"
        }
        require(!isInSourceCheckout(configFile)) {
            "Maximum protector configuration must be supplied outside the source checkout"
        }

        val releaseId = protectionReleaseId.get().trim()
        val seed = protectionSeed.get().trim()
        require(releaseId.isNotBlank() && !releaseId.equals("local", ignoreCase = true)) {
            "Maximum release requires an explicit non-local release ID (-PsynesisReleaseId or SYNESIS_RELEASE_ID)."
        }
        require(seed.isNotBlank() && !seed.equals("UNSET", ignoreCase = true)) {
            "Maximum release requires a release-specific protection seed (-PsynesisProtectionSeed or SYNESIS_PROTECTION_SEED)."
        }

        fun gitValue(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git") + arguments.toList())
                .directory(rootProject.layout.projectDirectory.asFile)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            require(process.waitFor() == 0) {
                "Unable to read Git provenance (${arguments.joinToString(" ")}): $output"
            }
            return output
        }

        val repositoryHead = gitValue("rev-parse", "HEAD")
        val sourceCommit = githubSha.get().trim().takeIf { it.isNotBlank() && it != "UNKNOWN" }
            ?.also { reported ->
                require(reported.equals(repositoryHead, ignoreCase = true)) {
                    "GITHUB_SHA does not match the release checkout HEAD"
                }
            }
            ?: repositoryHead
        val dirtyTree = gitValue("status", "--porcelain", "--untracked-files=all").isNotBlank()
        require(!dirtyTree) {
            "Maximum release requires a clean source checkout; run it from a reviewed release commit, not a dirty developer workspace."
        }
        val tierInventory = rootProject.file("docs/release/protection-tier-inventory.md")
        val keepRuleInventory = rootProject.file("docs/release/protection-keep-rules.md")
        val acceptanceProcedure = rootProject.file("docs/release/protected-acceptance.md")
        require(tierInventory.isFile && keepRuleInventory.isFile && acceptanceProcedure.isFile) {
            "Maximum release source/acceptance inventories are incomplete"
        }
        val inputBundle = platformBundleDirectory.get().asFile.absoluteFile
        val root = maximumReleaseDirectory.get().asFile
        delete(root)
        val outputBundle = maximumReleaseBundleDirectory.get().asFile.absoluteFile
        val privateDirectory = maximumReleasePrivateDirectory.get().asFile.absoluteFile
        outputBundle.mkdirs()
        privateDirectory.mkdirs()
        val requestFile = maximumReleaseRequest.get().asFile.absoluteFile
        val resultFile = maximumReleaseResult.get().asFile.absoluteFile
        val requestedProvenance = maximumReleaseProvenance(
            rootProject.layout.projectDirectory.asFile,
            sourceCommit,
            dirtyTree,
            releaseId,
            seed,
            "pending-adapter",
            "pending-adapter",
            configFile,
        )
        writeMaximumProperties(
            requestFile,
            linkedMapOf(
                "schema" to "1",
                "profile" to "maximum-release",
                "component" to "cli",
                "releaseId" to releaseId,
                "version" to bundleVersion.get(),
                "platform" to bundlePlatform.get(),
                "sourceCommit" to sourceCommit,
                "dirtyTree" to dirtyTree.toString(),
                "seed" to seed,
                "inputBundle" to inputBundle.path,
                "outputBundle" to outputBundle.path,
                "privateDirectory" to privateDirectory.path,
                "configuration" to configFile.path,
                "tierInventory" to tierInventory.absolutePath,
                "tierInventorySha256" to maximumSha256(tierInventory),
                "keepRuleInventory" to keepRuleInventory.absolutePath,
                "keepRuleInventorySha256" to maximumSha256(keepRuleInventory),
                "acceptanceProcedure" to acceptanceProcedure.absolutePath,
                "acceptanceProcedureSha256" to maximumSha256(acceptanceProcedure),
                "requiredRings" to "controlFlow,virtualization,strings,analysisEnvironment,protectedPayload,antiDebug",
                "retraceEvidenceFormat" to "properties-v1:schema,status,retraceTool,testCase,mappingSha256,inputStackTraceSha256,outputStackTraceSha256",
                "licenseEvidenceFormat" to "properties-v1:licenseMode,licenseEvidence",
                "nativeSymbolsScope" to "owned",
                "lockfileCount" to requestedProvenance.getValue("lockfileCount"),
                "lockfilesSha256" to requestedProvenance.getValue("lockfilesSha256"),
                "gradleVersion" to requestedProvenance.getValue("gradleVersion"),
                "javaRuntime" to requestedProvenance.getValue("javaRuntime"),
                "javaToolchain" to requestedProvenance.getValue("javaToolchain"),
                "nodeVersion" to requestedProvenance.getValue("nodeVersion"),
                "npmVersion" to requestedProvenance.getValue("npmVersion"),
                "nativeToolchain" to requestedProvenance.getValue("nativeToolchain"),
                "configurationSha256" to requestedProvenance.getValue("configurationSha256"),
                "invocation" to "The adapter must invoke the selected commercial protector; this task does not implement protection.",
            ),
        )

        val command = if (isWindows && protectorFile.extension.lowercase(Locale.ROOT) in setOf("cmd", "bat")) {
            listOf("cmd.exe", "/d", "/c", protectorFile.absolutePath, "--synesis-request", requestFile.absolutePath)
        } else {
            listOf(protectorFile.absolutePath, "--synesis-request", requestFile.absolutePath)
        }
        val process = ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Maximum protector adapter failed with exit code $exitCode; adapter output was discarded to avoid leaking release secrets."
        }

        val result = readMaximumProperties(resultFile)
        fun resultValue(key: String): String = maximumProperty(result, key)

        require(resultValue("schema") == "1") { "Maximum protector result schema must be 1" }
        require(resultValue("status") == "success") { "Maximum protector result did not report success" }
        require(resultValue("profile") == "maximum-release") { "Maximum protector result profile is not maximum-release" }
        require(resultValue("component") == "cli") { "Maximum protector result component is not cli" }
        require(resultValue("releaseId") == releaseId) { "Maximum protector result release ID does not match the request" }
        require(resultValue("sourceCommit") == sourceCommit) { "Maximum protector result source commit does not match the request" }
        require(resultValue("tierInventorySha256") == maximumSha256(tierInventory)) {
            "Maximum protector result was not built from the requested tier inventory"
        }
        val expectedProvenance = linkedMapOf(
            "sourceCommit" to sourceCommit,
            "dirtyTree" to dirtyTree.toString(),
            "seed" to seed,
            "lockfileCount" to requestedProvenance.getValue("lockfileCount"),
            "lockfilesSha256" to requestedProvenance.getValue("lockfilesSha256"),
            "gradleVersion" to requestedProvenance.getValue("gradleVersion"),
            "javaRuntime" to requestedProvenance.getValue("javaRuntime"),
            "javaToolchain" to requestedProvenance.getValue("javaToolchain"),
            "nodeVersion" to requestedProvenance.getValue("nodeVersion"),
            "npmVersion" to requestedProvenance.getValue("npmVersion"),
            "nativeToolchain" to requestedProvenance.getValue("nativeToolchain"),
            "configurationSha256" to requestedProvenance.getValue("configurationSha256"),
            "keepRuleInventorySha256" to maximumSha256(keepRuleInventory),
            "acceptanceProcedureSha256" to maximumSha256(acceptanceProcedure),
        )
        expectedProvenance.forEach { (key, expected) ->
            require(resultValue(key) == expected) {
                "Maximum protector result $key does not match the request provenance"
            }
        }
        require(resultValue("protectorName").isNotBlank()) { "Maximum protector name is missing from the private result" }
        require(resultValue("protectorVersion").isNotBlank() && resultValue("protectorVersion") != "unknown") {
            "Maximum protector version must be pinned in the private result"
        }
        require(resultValue("licenseEvidenceFormat") == "properties-v1:licenseMode,licenseEvidence") {
            "Maximum protector result must identify the private license-attestation format"
        }
        require(resultValue("licenseMode").isNotBlank() && resultValue("licenseMode") != "unknown") {
            "Maximum protector license mode must be recorded without exposing license material"
        }
        require(resultValue("nativeSymbolsScope") == "owned") {
            "Maximum CLI release must report nativeSymbolsScope=owned for its Synesis-owned native launchers"
        }

        fun normalized(path: File): String = path.toPath().toAbsolutePath().normalize().toString()
        fun samePath(actual: String, expected: File): Boolean =
            normalized(project.file(actual)).equals(normalized(expected), ignoreCase = isWindows)
        fun isUnder(child: File, parent: File): Boolean {
            val childPath = normalized(child)
            val parentPath = normalized(parent)
            return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
        }
        val thirdPartyNativeAudit = project.file(resultValue("thirdPartyNativeAudit"))
        require(isUnder(thirdPartyNativeAudit, privateDirectory) && thirdPartyNativeAudit.isFile && thirdPartyNativeAudit.length() > 0L) {
            "Maximum CLI third-party native audit is missing or outside the private boundary"
        }
        val licenseEvidence = project.file(resultValue("licenseEvidence"))
        require(isUnder(licenseEvidence, privateDirectory) && licenseEvidence.isFile && licenseEvidence.length() > 0L) {
            "Maximum CLI private license attestation is missing or outside the private boundary"
        }

        require(samePath(resultValue("bundleDirectory"), outputBundle)) {
            "Maximum protector wrote its bundle outside the requested output boundary"
        }
        require(samePath(resultValue("privateDirectory"), privateDirectory)) {
            "Maximum protector wrote private records outside the requested private boundary"
        }
        require(outputBundle.isDirectory) { "Maximum protector did not produce the customer bundle: $outputBundle" }
        require(privateDirectory.isDirectory) { "Maximum protector did not produce private release records: $privateDirectory" }
        require(!isUnder(privateDirectory, outputBundle)) { "Private release records overlap the customer bundle" }
        rejectMaximumSymlinks(outputBundle, "Maximum customer bundle")
        rejectMaximumSymlinks(privateDirectory, "Maximum private release directory")

        val privateRingEvidence = linkedMapOf<String, Pair<File, String>>()
        listOf(
            "controlFlow",
            "virtualization",
            "strings",
            "analysisEnvironment",
            "protectedPayload",
            "antiDebug",
        ).forEach { ring ->
            require(resultValue("ring.$ring") == "verified") {
                "Maximum protector did not verify the required ring: $ring"
            }
            val evidence = project.file(resultValue("evidence.$ring"))
            require(isUnder(evidence, privateDirectory) && evidence.isFile && evidence.length() > 0L) {
                "Maximum protector evidence for $ring is missing or outside the private boundary"
            }
            privateRingEvidence[ring] = evidence to maximumSha256(evidence)
        }
        require(resultValue("diversification") == "verified") {
            "Maximum protector did not verify release diversification"
        }
        val diversificationEvidence = project.file(resultValue("diversificationEvidence"))
        require(isUnder(diversificationEvidence, privateDirectory) && diversificationEvidence.isFile && diversificationEvidence.length() > 0L) {
            "Maximum protector diversification evidence is missing or outside the private boundary"
        }
        require(resultValue("retraceFile").isNotBlank()) { "Private JVM retrace output is missing" }
        require(resultValue("mappingFile").isNotBlank()) { "Private JVM mapping output is missing" }
        require(resultValue("retraceAcceptanceEvidence").isNotBlank()) {
            "Private retrace acceptance evidence is missing"
        }
        require(resultValue("nativeSymbolsDirectory").isNotBlank()) { "Private native symbols output is missing" }
        require(isUnder(project.file(resultValue("retraceFile")), privateDirectory)) {
            "Private retrace output escapes the private boundary"
        }
        require(isUnder(project.file(resultValue("mappingFile")), privateDirectory)) {
            "Private mapping output escapes the private boundary"
        }
        require(isUnder(project.file(resultValue("nativeSymbolsDirectory")), privateDirectory)) {
            "Private native symbols escape the private boundary"
        }
        val retraceFile = project.file(resultValue("retraceFile"))
        val mappingFile = project.file(resultValue("mappingFile"))
        val retraceAcceptanceEvidence = project.file(resultValue("retraceAcceptanceEvidence"))
        val nativeSymbolsDirectory = project.file(resultValue("nativeSymbolsDirectory"))
        require(retraceFile.isFile && retraceFile.length() > 0L) { "Private retrace output is not a non-empty file" }
        require(mappingFile.isFile && mappingFile.length() > 0L) { "Private mapping output is not a non-empty file" }
        require(
            isUnder(retraceAcceptanceEvidence, privateDirectory) &&
                    retraceAcceptanceEvidence.isFile && retraceAcceptanceEvidence.length() > 0L
        ) { "Private retrace acceptance evidence is missing or outside the private boundary" }
        validateMaximumRetraceEvidence(retraceAcceptanceEvidence, mappingFile, "Maximum CLI")
        require(nativeSymbolsDirectory.isDirectory) {
            "Private native symbols output is not a directory"
        }
        val nativeSymbolCount = Files.walk(nativeSymbolsDirectory.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.count()
        }
        require(nativeSymbolCount > 0L) { "Private native symbols directory is empty" }

        val profileMarker = outputBundle.resolve("PROTECTION_PROFILE")
        require(profileMarker.isFile && profileMarker.readText().trim() == "maximum-release") {
            "Maximum protector output is missing PROTECTION_PROFILE=maximum-release"
        }
        listOf(
            "VERSION",
            "manifest.json",
            "app/synesis-cli.jar",
            "runtime/bin/${if (isWindows) "java.exe" else "java"}",
            "bin/${if (isWindows) "synesis.cmd" else "synesis"}",
            "bin/${if (isWindows) "synesis-installer.exe" else "synesis-installer"}",
            "bin/${if (isWindows) "synesis-mcp.exe" else "synesis-mcp"}",
        ).forEach { relative ->
            require(outputBundle.resolve(relative.replace('/', File.separatorChar)).isFile) {
                "Maximum customer bundle is missing required file: $relative"
            }
        }
        val forbidden = setOf("mapping.txt", "seeds.txt", "usage.txt", "provenance.json", "artifact-manifest.txt")
        Files.walk(outputBundle.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val name = path.fileName.toString().lowercase(Locale.ROOT)
                require(
                    name !in forbidden && !name.endsWith(".sourcemap") && !name.endsWith(".pdb") &&
                            !name.endsWith(".dSYM".lowercase(Locale.ROOT)) && !name.endsWith(".map")
                ) { "Maximum customer bundle contains private/source material: $path" }
            }
        }

        val manifestLines = Files.walk(outputBundle.toPath()).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .map { path ->
                    val relative = outputBundle.toPath().relativize(path).toString()
                        .replace(File.separatorChar, '/')
                    "$relative\t${maximumSha256(path.toFile())}"
                }
                .sorted()
                .toList()
        }
        maximumReleaseArtifactManifest.get().asFile.writeText(
            "# SYNESIS_MAXIMUM_RELEASE_MANIFEST_V1\n" + manifestLines.joinToString("\n", postfix = "\n")
        )
        val privateEvidenceProperties = linkedMapOf<String, String>()
        privateRingEvidence.forEach { (ring, evidence) ->
            privateEvidenceProperties["privateEvidence.$ring"] = evidence.first.absolutePath
            privateEvidenceProperties["privateEvidence.${ring}Sha256"] = evidence.second
        }
        privateEvidenceProperties["privateDiversificationEvidence"] = diversificationEvidence.absolutePath
        privateEvidenceProperties["privateDiversificationEvidenceSha256"] = maximumSha256(diversificationEvidence)
        privateEvidenceProperties["privateLicenseEvidenceFormat"] = resultValue("licenseEvidenceFormat")
        privateEvidenceProperties["privateLicenseMode"] = resultValue("licenseMode")
        privateEvidenceProperties["privateLicenseEvidence"] = licenseEvidence.absolutePath
        privateEvidenceProperties["privateLicenseEvidenceSha256"] = maximumSha256(licenseEvidence)
        writeMaximumProperties(
            privateDirectory.resolve("release-record.properties"),
            maximumReleaseProvenance(
                rootProject.layout.projectDirectory.asFile,
                sourceCommit,
                dirtyTree,
                releaseId,
                seed,
                resultValue("protectorName"),
                resultValue("protectorVersion"),
                configFile,
            ) + linkedMapOf(
                "schema" to "1",
                "profile" to "maximum-release",
                "component" to "cli",
                "tierInventorySha256" to maximumSha256(tierInventory),
                "keepRuleInventorySha256" to maximumSha256(keepRuleInventory),
                "acceptanceProcedureSha256" to maximumSha256(acceptanceProcedure),
                "commercialRings" to "controlFlow,virtualization,strings,analysisEnvironment,protectedPayload,antiDebug",
                "diversification" to resultValue("diversification"),
                "nativeSymbolsScope" to resultValue("nativeSymbolsScope"),
                "privateRetraceFile" to resultValue("retraceFile"),
                "privateMappingFile" to resultValue("mappingFile"),
                "privateRetraceAcceptanceEvidence" to retraceAcceptanceEvidence.absolutePath,
                "privateRetraceAcceptanceEvidenceSha256" to maximumSha256(retraceAcceptanceEvidence),
                "privateNativeSymbolsDirectory" to resultValue("nativeSymbolsDirectory"),
                "privateThirdPartyNativeAudit" to thirdPartyNativeAudit.absolutePath,
                "privateThirdPartyNativeAuditSha256" to maximumSha256(thirdPartyNativeAudit),
                "artifactManifest" to maximumReleaseArtifactManifest.get().asFile.name,
                "artifactManifestSha256" to maximumSha256(maximumReleaseArtifactManifest.get().asFile),
            ) + privateEvidenceProperties,
        )
    }
}

val maximumReleaseArchiveTask = tasks.register<Zip>("maximumReleaseArchive") {
    group = "distribution"
    description = "Archives the externally protected maximum-release CLI bundle."
    dependsOn(maximumReleasePrepare)
    archiveFileName.set("synesis-${bundleVersion.get()}-${bundlePlatform.get()}-maximum-release.zip")
    destinationDirectory.set(maximumReleaseDirectory)
    from(maximumReleaseBundleDirectory) {
        into("synesis-${bundleVersion.get()}-${bundlePlatform.get()}-maximum-release")
    }
}

val maximumReleaseNativeHardeningAudit = tasks.register("maximumReleaseNativeHardeningAudit") {
    group = "distribution"
    description = "Audits the protected CLI archive's native hardening and platform signing before manifest signing."
    notCompatibleWithConfigurationCache(
        "Maximum release invokes the archive-only native hardening audit in the release environment."
    )
    dependsOn(maximumReleaseArchiveTask)
    outputs.file(maximumNativeHardeningEvidence)
    doLast {
        val developerArchiveValue = maximumDeveloperArchive.orNull?.trim().orEmpty()
        require(developerArchiveValue.isNotBlank()) {
            "Maximum release requires SYNESIS_DEVELOPER_ARCHIVE (or -PsynesisDeveloperArchive) for the native hardening comparison."
        }
        val developerArchive = project.file(developerArchiveValue).absoluteFile
        require(developerArchive.isFile) { "Developer archive for native hardening audit is missing: $developerArchive" }
        val auditScript = rootProject.file("scripts/release-native-hardening-audit.ps1").absoluteFile
        require(auditScript.isFile) { "Native hardening audit script is missing: $auditScript" }
        val maximumArchive = maximumReleaseArchiveTask.get().archiveFile.get().asFile.absoluteFile
        val evidence = maximumNativeHardeningEvidence.get().asFile.absoluteFile
        val command = mutableListOf<String>()
        if (OperatingSystem.current().isWindows) {
            command.addAll(listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass"))
        } else {
            command.addAll(listOf("pwsh", "-NoProfile"))
        }
        command.addAll(
            listOf(
                "-File", auditScript.absolutePath,
                "-Component", "cli",
                "-DeveloperArchive", developerArchive.absolutePath,
                "-MaximumArchive", maximumArchive.absolutePath,
                "-EvidenceFile", evidence.absolutePath,
            ),
        )
        val process = ProcessBuilder(command)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val exitCode = process.waitFor()
        require(exitCode == 0 && evidence.isFile) {
            "Maximum CLI native hardening/signing audit failed with exit code $exitCode"
        }
        require(maximumNativeAuditValue(evidence, "nativeHardeningStatus") == "PASS") {
            "Maximum CLI native hardening audit did not report PASS"
        }
        require(maximumNativeAuditValue(evidence, "nativeSigningStatus") == "PASS") {
            "Maximum CLI native signing audit did not report PASS"
        }
    }
}

val maximumReleaseManifestTask = tasks.register("maximumReleaseManifest") {
    group = "distribution"
    description = "Creates the canonical signed-release manifest for the protected CLI candidate."
    dependsOn(maximumReleaseNativeHardeningAudit)
    outputs.file(maximumReleaseManifest)
    doLast {
        fun json(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")

        val keyId = project.providers.gradleProperty("synesisSigningKeyId")
            .orElse(project.providers.environmentVariable("SYNESIS_MANIFEST_SIGNING_KEY_ID")).orNull?.trim().orEmpty()
        require(keyId.isNotBlank()) {
            "Maximum release requires SYNESIS_MANIFEST_SIGNING_KEY_ID (or -PsynesisSigningKeyId); no signing key is generated here."
        }
        val publishedAt = project.providers.gradleProperty("synesisPublishedAt")
            .orElse(project.providers.environmentVariable("SYNESIS_RELEASE_PUBLISHED_AT")).orNull?.trim().orEmpty()
        require(publishedAt.isNotBlank()) {
            "Maximum release requires SYNESIS_RELEASE_PUBLISHED_AT (or -PsynesisPublishedAt) for reproducible release metadata."
        }
        val minimumBootstrapVersion = project.providers.gradleProperty("synesisMinimumBootstrapVersion")
            .orElse(project.providers.environmentVariable("SYNESIS_MINIMUM_BOOTSTRAP_VERSION"))
            .orElse("0.1.0-dev.local").get()
        val archive = maximumReleaseArchiveTask.get().archiveFile.get().asFile
        maximumReleaseManifest.get().asFile.writeText(
            """{
  "schemaVersion": 1,
  "channel": "maximum-release",
  "version": "${json(bundleVersion.get())}",
  "publishedAt": "${json(publishedAt)}",
  "minimumBootstrapVersion": "${json(minimumBootstrapVersion)}",
  "developmentOnly": false,
  "signingKeyId": "${json(keyId)}",
  "releaseId": "${json(protectionReleaseId.get())}",
  "artifacts": {
    "${json(bundlePlatform.get())}": {
      "url": "${json(archive.name)}",
      "sha256": "${maximumSha256(archive)}",
      "size": ${archive.length()}
    }
  }
}
""".trimIndent() + "\n"
        )
    }
}

val maximumReleaseSignTask = tasks.register("maximumReleaseSign") {
    group = "distribution"
    description = "Signs the maximum-release manifest with the existing bootstrap signer and an injected CI secret."
    dependsOn(maximumReleaseManifestTask)
    outputs.file(maximumReleaseSignature)
    doLast {
        require(!System.getenv("SYNESIS_MANIFEST_PRIVATE_KEY_B64").isNullOrBlank()) {
            "Maximum release requires SYNESIS_MANIFEST_PRIVATE_KEY_B64; production signing keys are injected, never generated or committed."
        }
        val command = listOf(
            "go", "run", "./cmd/sign-manifest",
            "--manifest", maximumReleaseManifest.get().asFile.absolutePath,
            "--signature", maximumReleaseSignature.get().asFile.absolutePath,
        )
        val process = ProcessBuilder(command)
            .directory(rootProject.file("bootstrap"))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val exitCode = process.waitFor()
        require(exitCode == 0 && maximumReleaseSignature.get().asFile.isFile) {
            "Existing bootstrap manifest signer failed with exit code $exitCode"
        }
    }
}

tasks.register("maximumRelease") {
    group = "distribution"
    description = "Builds, signs, and verifies a licensed maximum-release CLI candidate; fails closed without every release authority."
    dependsOn(maximumReleaseSignTask)
    notCompatibleWithConfigurationCache(
        "Maximum release verifies an injected signature against the bootstrap trust root."
    )
    doLast {
        val bootstrapSource = rootProject.file("bootstrap/main.go").readText()
        val publicKeyHex = Regex("""manifestPublicKeyHex\s*=\s*\"([0-9a-fA-F]+)\"""")
            .find(bootstrapSource)?.groupValues?.get(1)
            ?: error("Embedded bootstrap manifest public key is missing")
        val publicKeyBytes = maximumHexDecode(publicKeyHex)
        require(publicKeyBytes.size == 32) { "Embedded bootstrap manifest public key must be 32 bytes" }
        val bootstrapPublicKeySha256 = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes)
            .joinToString("") { "%02x".format(it) }
        val x509Prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(x509Prefix + publicKeyBytes)
        )
        val signatureText = maximumReleaseSignature.get().asFile.readText().trim()
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureText)
        } catch (exception: IllegalArgumentException) {
            throw GradleException("Maximum-release detached signature is not valid base64", exception)
        }
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        val manifestBytes = maximumReleaseManifest.get().asFile.readBytes()
        verifier.update(manifestBytes)
        require(verifier.verify(signatureBytes)) {
            "Maximum-release manifest signature does not verify against bootstrap/main.go trust root"
        }
        require(maximumReleaseManifest.get().asFile.readText().contains("\"developmentOnly\": false")) {
            "Maximum-release manifest must not be marked development-only"
        }

        val record = maximumReleasePrivateDirectory.get().asFile.resolve("release-record.properties")
        val properties = readMaximumProperties(record)
        val nativeHardeningEvidence = maximumNativeHardeningEvidence.get().asFile
        require(nativeHardeningEvidence.isFile) { "Maximum CLI native hardening evidence is missing" }
        properties.setProperty("nativeHardeningStatus", "verified")
        properties.setProperty("nativeSigningStatus", "verified")
        properties.setProperty("privateNativeHardeningEvidence", nativeHardeningEvidence.absolutePath)
        properties.setProperty("privateNativeHardeningEvidenceSha256", maximumSha256(nativeHardeningEvidence))
        val signingKeyId = project.providers.gradleProperty("synesisSigningKeyId")
            .orElse(project.providers.environmentVariable("SYNESIS_MANIFEST_SIGNING_KEY_ID"))
            .orNull?.trim().orEmpty()
        val publishedAt = project.providers.gradleProperty("synesisPublishedAt")
            .orElse(project.providers.environmentVariable("SYNESIS_RELEASE_PUBLISHED_AT"))
            .orNull?.trim().orEmpty()
        properties.setProperty("manifest", maximumReleaseManifest.get().asFile.name)
        properties.setProperty("manifestSha256", maximumSha256(maximumReleaseManifest.get().asFile))
        properties.setProperty("signature", maximumReleaseSignature.get().asFile.name)
        properties.setProperty("signatureSha256", maximumSha256(maximumReleaseSignature.get().asFile))
        properties.setProperty("signedIntegrity", "verified")
        properties.setProperty("signedAgainstBootstrapKey", "true")
        properties.setProperty("signingKeyId", signingKeyId)
        properties.setProperty("publishedAt", publishedAt)
        properties.setProperty("bootstrapPublicKeySha256", bootstrapPublicKeySha256)
        properties.setProperty("signingProvenance", "Ed25519 detached manifest signature verified against bootstrap/main.go trust root")
        record.outputStream().use { properties.store(it, "Synesis maximum-release private record") }
        logger.lifecycle(
            "Maximum-release candidate verified: ${maximumReleaseArchiveTask.get().archiveFile.get().asFile.absolutePath}"
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
