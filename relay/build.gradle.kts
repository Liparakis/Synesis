import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.Properties
import org.gradle.internal.os.OperatingSystem

plugins {
    application
}

group = "org.synesis"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
    withJavadocJar()
}

application {
    applicationName = "synesis-relay"
    mainClass = "org.synesis.relay.RelayMain"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

val relayProtectionLiteConfiguration = configurations.create("protectionLite")

dependencies {
    implementation(project(":link"))
    implementation(libs.netty.codec.native.quic)
    add("protectionLite", libs.proguard.base)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs strict compiler diagnostics."
    dependsOn(tasks.compileJava, tasks.compileTestJava)
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

val relayFormatRoots = listOf(
    layout.projectDirectory.dir("src").asFile,
    layout.projectDirectory.file("build.gradle.kts").asFile
).filter { it.exists() }

tasks.register<TrailingWhitespaceTask>("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in relay sources."
    files.from(relayFormatRoots.map { root ->
        if (root.isDirectory) fileTree(root) {
            include("**/*.java")
            include("**/*.kt")
            include("**/*.kts")
        } else root
    })
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis")
}

// Release-only relay payload. The normal application distribution remains
// readable; this seam is exercised only by explicit protection tasks.
evaluationDependsOn(":link")

val relayProtectionJmods = providers.gradleProperty("synesisJmods")
    .orElse(providers.environmentVariable("SYNESIS_JMODS").orElse(""))
val relayProtectionVersion = providers.gradleProperty("synesisVersion")
    .orElse(providers.environmentVariable("SYNESIS_VERSION").orElse("0.1.0-dev.local"))
val relayProtectionLiteDirectory = layout.buildDirectory.dir("protection-lite")
val relayProtectionLiteBundleDirectory = relayProtectionLiteDirectory.map { it.dir("bundle") }
val relayProtectionLitePrivateDirectory = relayProtectionLiteDirectory.map { it.dir("private") }
val relayProtectionLiteJar = relayProtectionLiteDirectory.map { it.file("synesis-relay-protection-lite.jar") }
val relayProtectionLiteMapping = relayProtectionLitePrivateDirectory.map { it.file("mapping.txt") }
val relayProtectionLiteProvenance = relayProtectionLitePrivateDirectory.map { it.file("provenance.json") }
val relayProtectionLiteRules = layout.projectDirectory.file("src/release/proguard/protection-lite.pro")
val relayMaximumProtector = providers.gradleProperty("synesisMaximumProtector")
    .orElse(providers.environmentVariable("SYNESIS_MAXIMUM_PROTECTOR").orElse(""))
val relayMaximumProtectorConfig = providers.gradleProperty("synesisMaximumConfig")
    .orElse(providers.environmentVariable("SYNESIS_MAXIMUM_CONFIG").orElse(""))
val relayMaximumReleaseId = providers.gradleProperty("synesisReleaseId")
    .orElse(providers.environmentVariable("SYNESIS_RELEASE_ID").orElse("local"))
val relayMaximumSeed = providers.gradleProperty("synesisProtectionSeed")
    .orElse(providers.environmentVariable("SYNESIS_PROTECTION_SEED").orElse("UNSET"))
val relayMaximumHostArch = if (System.getProperty("os.arch").lowercase(Locale.ROOT).contains("aarch64")) "arm64" else "x64"
val relayMaximumPlatform = when {
    OperatingSystem.current().isWindows -> "windows-$relayMaximumHostArch"
    OperatingSystem.current().isMacOsX -> "macos-$relayMaximumHostArch"
    else -> "linux-$relayMaximumHostArch"
}
val relayMaximumReleaseDirectory = layout.buildDirectory.dir("maximum-release")
val relayMaximumReleaseBundleDirectory = relayMaximumReleaseDirectory.map { it.dir("bundle") }
val relayMaximumReleasePrivateDirectory = relayMaximumReleaseDirectory.map { it.dir("private") }
val relayMaximumReleaseRequest = relayMaximumReleaseDirectory.map { it.file("request.properties") }
val relayMaximumReleaseResult = relayMaximumReleaseDirectory.map { it.file("result.properties") }
val relayMaximumReleaseArtifactManifest = relayMaximumReleasePrivateDirectory.map { it.file("artifact-manifest.txt") }
val relayMaximumReleaseManifest = relayMaximumReleaseDirectory.map { it.file("manifest.json") }
val relayMaximumReleaseSignature = relayMaximumReleaseDirectory.map { it.file("manifest.json.sig") }
val relayMaximumDeveloperArchive = providers.gradleProperty("synesisDeveloperArchive")
    .orElse(providers.environmentVariable("SYNESIS_DEVELOPER_ARCHIVE"))
val relayMaximumNativeHardeningEvidence = relayMaximumReleasePrivateDirectory.map { it.file("native-hardening.json") }

fun writeRelayMaximumProperties(file: File, values: Map<String, String>) {
    val properties = Properties()
    values.forEach { (key, value) -> properties.setProperty(key, value) }
    file.parentFile.mkdirs()
    file.outputStream().use { properties.store(it, "Synesis maximum-release adapter contract") }
}

fun readRelayMaximumProperties(file: File): Properties {
    require(file.isFile) { "Maximum-release adapter result is missing: $file" }
    return Properties().also { properties ->
        file.inputStream().use { input -> properties.load(input) }
    }
}

fun relayMaximumProperty(properties: Properties, key: String): String = properties.getProperty(key)?.trim().orEmpty()

fun relayMaximumSha256(file: File): String {
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

fun relayMaximumNativeAuditValue(file: File, key: String): String {
    val pattern = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    return pattern.find(file.readText())?.groupValues?.get(1).orEmpty()
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
        "${file.relativeTo(root).invariantSeparatorsPath}\t${relayMaximumSha256(file)}"
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
        "configurationSha256" to relayMaximumSha256(configuration),
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

fun relayMaximumHexDecode(value: String): ByteArray {
    require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]+"))) {
        "Expected an even-length hexadecimal value"
    }
    return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

val relayProtectionLiteOwnedJarProviders = listOf(
    project(":link").tasks.named<Jar>("jar").flatMap { it.archiveFile },
    tasks.named<Jar>("jar").flatMap { it.archiveFile },
)
val relayProtectionLiteInputTasks = listOf(
    project(":link").tasks.named<Jar>("jar"),
    tasks.named<Jar>("jar"),
)

val relayProtectionLite = tasks.register<JavaExec>("protectionLite") {
    group = "distribution"
    description = "Builds the explicit ProGuard protection-lite relay payload."
    notCompatibleWithConfigurationCache(
        "Protection-lite resolves toolchain/library paths and invokes an external protector."
    )
    dependsOn(relayProtectionLiteInputTasks)
    classpath = relayProtectionLiteConfiguration
    mainClass.set("proguard.ProGuard")
    inputs.files(relayProtectionLiteOwnedJarProviders)
    inputs.file(relayProtectionLiteRules)
    outputs.files(relayProtectionLiteJar, relayProtectionLiteMapping)
    doFirst {
        val outputJar = relayProtectionLiteJar.get().asFile
        val privateDirectory = relayProtectionLitePrivateDirectory.get().asFile
        delete(relayProtectionLiteDirectory.get().asFile)
        outputJar.parentFile.mkdirs()
        privateDirectory.mkdirs()
        val ownedJars = relayProtectionLiteOwnedJarProviders.map { it.get().asFile }.distinct()
        require(ownedJars.all { it.isFile }) { "Relay protection-lite input JAR missing: $ownedJars" }
        val ownedPaths = ownedJars.map { it.absoluteFile.normalize() }.toSet()
        val externalJars = configurations.runtimeClasspath.get().files
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .filterNot { it.absoluteFile.normalize() in ownedPaths }
            .distinctBy { it.absoluteFile.normalize() }
        val javaHome = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().metadata.installationPath.asFile
        val configuredJmods = relayProtectionJmods.get().trim()
        val jmods = if (configuredJmods.isBlank()) javaHome.resolve("jmods") else file(configuredJmods)
        require(jmods.isDirectory) { "Relay protection Java library JMOD directory missing: $jmods" }
        val requiredJmods = listOf(
            "java.base.jmod", "java.logging.jmod", "java.naming.jmod", "java.net.http.jmod",
            "jdk.httpserver.jmod", "jdk.jfr.jmod", "jdk.unsupported.jmod",
        ).map { name -> jmods.resolve(name).also { require(it.isFile) { "Required Java JMOD missing: $it" } } }
        setArgs(
            buildList {
                addAll(listOf("-include", relayProtectionLiteRules.asFile.absolutePath))
                ownedJars.forEach { addAll(listOf("-injars", it.absolutePath + "(!META-INF/MANIFEST.MF)")) }
                addAll(listOf("-outjars", outputJar.absolutePath))
                requiredJmods.forEach { addAll(listOf("-libraryjars", it.absolutePath + "(!**.jar;!module-info.class)")) }
                externalJars.forEach { addAll(listOf("-libraryjars", it.absolutePath)) }
                addAll(listOf("-printmapping", relayProtectionLiteMapping.get().asFile.absolutePath))
            },
        )
    }
}

val relayProtectionLiteBundle = tasks.register<Sync>("protectionLiteBundle") {
    group = "distribution"
    description = "Stages the protected standalone relay distribution."
    dependsOn(tasks.installDist, relayProtectionLite)
    into(relayProtectionLiteBundleDirectory)
    from(layout.buildDirectory.dir("install/synesis-relay"))
    doLast {
        val root = relayProtectionLiteBundleDirectory.get().asFile
        val lib = root.resolve("lib")
        require(lib.isDirectory) { "Protected relay lib directory missing: $lib" }
        val ownedNames = relayProtectionLiteOwnedJarProviders.map { it.get().asFile.name }.toSet()
        lib.listFiles()?.filter { it.name in ownedNames }?.forEach { delete(it) }
        copy {
            from(relayProtectionLiteJar)
            into(lib)
            rename { "synesis-relay-protection-lite.jar" }
        }
        root.resolve("bin/synesis-relay.bat").writeText(
            "@echo off\r\n" +
                    "setlocal\r\n" +
                    "set \"APP_HOME=%~dp0..\"\r\n" +
                    "java.exe --enable-native-access=ALL-UNNAMED -cp \"%APP_HOME%\\lib\\*\" org.synesis.relay.RelayMain %*\r\n" +
                    "exit /b %ERRORLEVEL%\r\n"
        )
        val unixLauncher = root.resolve("bin/synesis-relay")
        unixLauncher.writeText(
            "#!/bin/sh\n" +
                    "set -eu\n" +
                    "APP_HOME=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")/..\" && pwd)\"\n" +
                    "exec java --enable-native-access=ALL-UNNAMED -cp \"\$APP_HOME/lib/*\" org.synesis.relay.RelayMain \"\$@\"\n"
        )
        unixLauncher.setExecutable(true)
        root.resolve("PROTECTION_PROFILE").writeText("protection-lite\n")
    }
}

val relayProtectionLiteArchive = tasks.register<Zip>("protectionLiteArchive") {
    group = "distribution"
    description = "Archives the protected standalone relay distribution."
    dependsOn(relayProtectionLiteBundle)
    archiveFileName.set("synesis-relay-${relayProtectionVersion.get()}-protection-lite.zip")
    destinationDirectory.set(relayProtectionLiteDirectory)
    from(relayProtectionLiteBundleDirectory) { into("synesis-relay-protection-lite") }
}

tasks.register("protectionLiteSmokeTest") {
    group = "verification"
    description = "Exercises the transformed relay entrypoint from the protected archive."
    notCompatibleWithConfigurationCache(
        "Protection-lite relay smoke testing extracts an archive and launches an external process."
    )
    dependsOn(relayProtectionLiteArchive)
    doLast {
        val smokeRoot = Files.createTempDirectory("synesis-relay-protection-lite-smoke-").toFile()
        val archive = relayProtectionLiteArchive.get().archiveFile.get().asFile
        val extractedRoot = smokeRoot.resolve("bundle")
        copy { from(zipTree(archive)); into(extractedRoot) }
        val bundleRoot = extractedRoot.resolve("synesis-relay-protection-lite")
        val launcher = bundleRoot.resolve("bin").resolve(
            if (OperatingSystem.current().isWindows) "synesis-relay.bat" else "synesis-relay"
        )
        require(launcher.isFile) { "Protected relay launcher missing: $launcher" }
        val command = if (OperatingSystem.current().isWindows) {
            mutableListOf("cmd.exe", "/c", launcher.absolutePath, "--not-an-option")
        } else {
            mutableListOf(launcher.absolutePath, "--not-an-option")
        }
        val process = ProcessBuilder(command).directory(smokeRoot).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        require(process.waitFor() != 0 && output.contains("usage: synesis-relay")) {
            "Protected relay entrypoint smoke did not reach the guarded parser:\n$output"
        }
        delete(smokeRoot)
    }
}

val relayArtifactAcceptanceLauncher = providers.gradleProperty("synesisRelayAcceptanceLauncher")
    .orElse("")

tasks.register<JavaExec>("relayArtifactAcceptanceClient") {
    group = "verification"
    description = "Exercises an extracted relay artifact through authenticated forwarding."
    notCompatibleWithConfigurationCache(
        "The acceptance client starts an external extracted relay process."
    )
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.synesis.relay.RelayArtifactAcceptanceMain")
    doFirst {
        val launcher = relayArtifactAcceptanceLauncher.get().trim()
        require(launcher.isNotBlank()) {
            "Relay artifact acceptance requires -PsynesisRelayAcceptanceLauncher=PATH"
        }
        args("--launcher", launcher)
    }
}

tasks.register("protectionLiteProvenance") {
    group = "distribution"
    description = "Writes private protected-relay provenance."
    dependsOn(relayProtectionLite, relayProtectionLiteArchive)
    outputs.file(relayProtectionLiteProvenance)
    doLast {
        fun sha256(file: File): String {
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
        val record = """{
  "schema": 1,
  "profile": "protection-lite",
  "artifact": "${relayProtectionLiteJar.get().asFile.name}",
  "artifactSha256": "${sha256(relayProtectionLiteJar.get().asFile)}",
  "archive": "${relayProtectionLiteArchive.get().archiveFile.get().asFile.name}",
  "archiveSha256": "${sha256(relayProtectionLiteArchive.get().archiveFile.get().asFile)}",
  "tool": "ProGuard",
  "toolVersion": "${libs.versions.proguard.get()}",
  "mapping": "${relayProtectionLiteMapping.get().asFile.name}"
}
""".trimIndent() + "\n"
        relayProtectionLiteProvenance.get().asFile.parentFile.mkdirs()
        relayProtectionLiteProvenance.get().asFile.writeText(record)
    }
}

val relayMaximumReleasePrepare = tasks.register("maximumReleasePrepare") {
    group = "distribution"
    description = "Invokes the supplied licensed maximum-protection adapter and validates the protected relay boundary."
    notCompatibleWithConfigurationCache(
        "Maximum protection invokes a release-environment adapter and inspects customer/private output boundaries."
    )
    dependsOn(tasks.installDist)
    inputs.dir(layout.buildDirectory.dir("install/synesis-relay"))
    outputs.dir(relayMaximumReleaseBundleDirectory)
    outputs.dir(relayMaximumReleasePrivateDirectory)
    doLast {
        val protectorValue = relayMaximumProtector.get().trim()
        require(protectorValue.isNotBlank()) {
            "Maximum relay release is blocked: set -PsynesisMaximumProtector or SYNESIS_MAXIMUM_PROTECTOR " +
                    "to a licensed, version-pinned protector adapter."
        }
        val configValue = relayMaximumProtectorConfig.get().trim()
        require(configValue.isNotBlank()) {
            "Maximum relay release is blocked: set -PsynesisMaximumConfig or SYNESIS_MAXIMUM_CONFIG " +
                    "to the private, version-pinned protector configuration."
        }
        val protectorFile = project.file(protectorValue).absoluteFile
        val configFile = project.file(configValue).absoluteFile
        require(protectorFile.isFile) { "Maximum protector adapter is not a file: $protectorFile" }
        require(configFile.isFile) { "Maximum protector configuration is not a file: $configFile" }

        val releaseId = relayMaximumReleaseId.get().trim()
        val seed = relayMaximumSeed.get().trim()
        require(releaseId.isNotBlank() && !releaseId.equals("local", ignoreCase = true)) {
            "Maximum relay release requires an explicit non-local release ID."
        }
        require(seed.isNotBlank() && !seed.equals("UNSET", ignoreCase = true)) {
            "Maximum relay release requires a release-specific protection seed."
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

        val sourceCommit = gitValue("rev-parse", "HEAD")
        val dirtyTree = gitValue("status", "--porcelain", "--untracked-files=all").isNotBlank()
        require(!dirtyTree) {
            "Maximum relay release requires a clean source checkout; run it from a reviewed release commit, not a dirty developer workspace."
        }
        val tierInventory = rootProject.file("docs/release/protection-tier-inventory.md")
        val keepRuleInventory = rootProject.file("docs/release/protection-keep-rules.md")
        val acceptanceProcedure = rootProject.file("docs/release/protected-acceptance.md")
        require(tierInventory.isFile && keepRuleInventory.isFile && acceptanceProcedure.isFile) {
            "Maximum relay release source/acceptance inventories are incomplete"
        }
        val inputBundle = layout.buildDirectory.dir("install/synesis-relay").get().asFile.absoluteFile
        val root = relayMaximumReleaseDirectory.get().asFile
        delete(root)
        val outputBundle = relayMaximumReleaseBundleDirectory.get().asFile.absoluteFile
        val privateDirectory = relayMaximumReleasePrivateDirectory.get().asFile.absoluteFile
        outputBundle.mkdirs()
        privateDirectory.mkdirs()
        val requestFile = relayMaximumReleaseRequest.get().asFile.absoluteFile
        val resultFile = relayMaximumReleaseResult.get().asFile.absoluteFile
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
        writeRelayMaximumProperties(
            requestFile,
            linkedMapOf(
                "schema" to "1",
                "profile" to "maximum-release",
                "component" to "relay",
                "releaseId" to releaseId,
                "version" to relayProtectionVersion.get(),
                "platform" to relayMaximumPlatform,
                "sourceCommit" to sourceCommit,
                "dirtyTree" to dirtyTree.toString(),
                "seed" to seed,
                "inputBundle" to inputBundle.path,
                "outputBundle" to outputBundle.path,
                "privateDirectory" to privateDirectory.path,
                "configuration" to configFile.path,
                "tierInventory" to tierInventory.absolutePath,
                "tierInventorySha256" to relayMaximumSha256(tierInventory),
                "keepRuleInventory" to keepRuleInventory.absolutePath,
                "keepRuleInventorySha256" to relayMaximumSha256(keepRuleInventory),
                "acceptanceProcedure" to acceptanceProcedure.absolutePath,
                "acceptanceProcedureSha256" to relayMaximumSha256(acceptanceProcedure),
                "requiredRings" to "controlFlow,virtualization,strings,analysisEnvironment,protectedPayload,antiDebug",
                "nativeSymbolsScopePolicy" to "owned-or-not-applicable",
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

        val command = if (OperatingSystem.current().isWindows && protectorFile.extension.lowercase(Locale.ROOT) in setOf("cmd", "bat")) {
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
            "Maximum relay protector adapter failed with exit code $exitCode; adapter output was discarded to avoid leaking release secrets."
        }

        val result = readRelayMaximumProperties(resultFile)
        fun resultValue(key: String): String = relayMaximumProperty(result, key)

        require(resultValue("schema") == "1") { "Maximum relay protector result schema must be 1" }
        require(resultValue("status") == "success") { "Maximum relay protector result did not report success" }
        require(resultValue("profile") == "maximum-release") { "Maximum relay protector result profile is not maximum-release" }
        require(resultValue("component") == "relay") { "Maximum relay protector result component is not relay" }
        require(resultValue("releaseId") == releaseId) { "Maximum relay protector result release ID does not match the request" }
        require(resultValue("sourceCommit") == sourceCommit) { "Maximum relay protector result source commit does not match the request" }
        require(resultValue("tierInventorySha256") == relayMaximumSha256(tierInventory)) {
            "Maximum relay protector result was not built from the requested tier inventory"
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
            "keepRuleInventorySha256" to relayMaximumSha256(keepRuleInventory),
            "acceptanceProcedureSha256" to relayMaximumSha256(acceptanceProcedure),
        )
        expectedProvenance.forEach { (key, expected) ->
            require(resultValue(key) == expected) {
                "Maximum relay protector result $key does not match the request provenance"
            }
        }
        require(resultValue("protectorName").isNotBlank()) { "Maximum relay protector name is missing" }
        require(resultValue("protectorVersion").isNotBlank() && resultValue("protectorVersion") != "unknown") {
            "Maximum relay protector version must be pinned"
        }

        fun normalized(path: File): String = path.toPath().toAbsolutePath().normalize().toString()
        fun samePath(actual: String, expected: File): Boolean =
            normalized(project.file(actual)).equals(normalized(expected), ignoreCase = OperatingSystem.current().isWindows)
        fun isUnder(child: File, parent: File): Boolean {
            val childPath = normalized(child)
            val parentPath = normalized(parent)
            return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
        }
        val nativeSymbolsScope = resultValue("nativeSymbolsScope")
        require(nativeSymbolsScope in setOf("owned", "not-applicable")) {
            "Maximum relay nativeSymbolsScope must be owned or not-applicable"
        }
        val thirdPartyNativeAudit = project.file(resultValue("thirdPartyNativeAudit"))
        require(isUnder(thirdPartyNativeAudit, privateDirectory) && thirdPartyNativeAudit.isFile && thirdPartyNativeAudit.length() > 0L) {
            "Maximum relay third-party native audit is missing or outside the private boundary"
        }

        require(samePath(resultValue("bundleDirectory"), outputBundle)) {
            "Maximum relay protector wrote its bundle outside the requested output boundary"
        }
        require(samePath(resultValue("privateDirectory"), privateDirectory)) {
            "Maximum relay protector wrote private records outside the requested private boundary"
        }
        require(outputBundle.isDirectory) { "Maximum relay protector did not produce the customer bundle: $outputBundle" }
        require(privateDirectory.isDirectory) { "Maximum relay protector did not produce private records: $privateDirectory" }
        require(!isUnder(privateDirectory, outputBundle)) { "Private relay records overlap the customer bundle" }

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
                "Maximum relay protector did not verify the required ring: $ring"
            }
            val evidence = project.file(resultValue("evidence.$ring"))
            require(isUnder(evidence, privateDirectory) && evidence.isFile && evidence.length() > 0L) {
                "Maximum relay evidence for $ring is missing or outside the private boundary"
            }
            privateRingEvidence[ring] = evidence to relayMaximumSha256(evidence)
        }
        require(resultValue("diversification") == "verified") {
            "Maximum relay protector did not verify release diversification"
        }
        val diversificationEvidence = project.file(resultValue("diversificationEvidence"))
        require(isUnder(diversificationEvidence, privateDirectory) && diversificationEvidence.isFile && diversificationEvidence.length() > 0L) {
            "Maximum relay diversification evidence is missing or outside the private boundary"
        }
        val retraceFile = project.file(resultValue("retraceFile"))
        val mappingFile = project.file(resultValue("mappingFile"))
        require(isUnder(retraceFile, privateDirectory) && retraceFile.isFile && retraceFile.length() > 0L) {
            "Maximum relay private retrace output is missing or outside the private boundary"
        }
        require(isUnder(mappingFile, privateDirectory) && mappingFile.isFile && mappingFile.length() > 0L) {
            "Maximum relay private mapping output is missing or outside the private boundary"
        }
        val nativeSymbolsLocation = resultValue("nativeSymbolsDirectory")
        if (nativeSymbolsScope == "owned") {
            val nativeSymbolsDirectory = project.file(nativeSymbolsLocation)
            require(isUnder(nativeSymbolsDirectory, privateDirectory) && nativeSymbolsDirectory.isDirectory) {
                "Maximum relay owned native symbols are missing or outside the private boundary"
            }
            val nativeSymbolCount = Files.walk(nativeSymbolsDirectory.toPath()).use { paths ->
                paths.filter { Files.isRegularFile(it) }.count()
            }
            require(nativeSymbolCount > 0L) { "Maximum relay owned native symbols directory is empty" }
        } else {
            require(nativeSymbolsLocation == "not-applicable") {
                "Maximum relay result must not claim a native-symbol directory when its native scope is not-applicable"
            }
        }

        require(outputBundle.resolve("PROTECTION_PROFILE").run { isFile && readText().trim() == "maximum-release" }) {
            "Maximum relay output is missing PROTECTION_PROFILE=maximum-release"
        }
        val launcherName = if (OperatingSystem.current().isWindows) "synesis-relay.bat" else "synesis-relay"
        require(outputBundle.resolve("bin").resolve(launcherName).isFile) {
            "Maximum relay customer bundle is missing bin/$launcherName"
        }
        require(outputBundle.resolve("lib").listFiles()?.any { it.isFile && it.extension == "jar" } == true) {
            "Maximum relay customer bundle contains no application JAR"
        }
        val forbidden = setOf("mapping.txt", "seeds.txt", "usage.txt", "provenance.json", "artifact-manifest.txt")
        Files.walk(outputBundle.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val name = path.fileName.toString().lowercase(Locale.ROOT)
                require(
                    name !in forbidden && !name.endsWith(".sourcemap") && !name.endsWith(".pdb") &&
                            !name.endsWith(".dsym") && !name.endsWith(".map")
                ) { "Maximum relay bundle contains private/source material: $path" }
            }
        }

        val manifestLines = Files.walk(outputBundle.toPath()).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .map { path ->
                    val relative = outputBundle.toPath().relativize(path).toString()
                        .replace(File.separatorChar, '/')
                    "$relative\t${relayMaximumSha256(path.toFile())}"
                }
                .sorted()
                .toList()
        }
        relayMaximumReleaseArtifactManifest.get().asFile.writeText(
            "# SYNESIS_MAXIMUM_RELAY_MANIFEST_V1\n" + manifestLines.joinToString("\n", postfix = "\n")
        )
        val privateEvidenceProperties = linkedMapOf<String, String>()
        privateRingEvidence.forEach { (ring, evidence) ->
            privateEvidenceProperties["privateEvidence.$ring"] = evidence.first.absolutePath
            privateEvidenceProperties["privateEvidence.${ring}Sha256"] = evidence.second
        }
        privateEvidenceProperties["privateDiversificationEvidence"] = diversificationEvidence.absolutePath
        privateEvidenceProperties["privateDiversificationEvidenceSha256"] = relayMaximumSha256(diversificationEvidence)
        writeRelayMaximumProperties(
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
                "component" to "relay",
                "tierInventorySha256" to relayMaximumSha256(tierInventory),
                "keepRuleInventorySha256" to relayMaximumSha256(keepRuleInventory),
                "acceptanceProcedureSha256" to relayMaximumSha256(acceptanceProcedure),
                "commercialRings" to "controlFlow,virtualization,strings,analysisEnvironment,protectedPayload,antiDebug",
                "diversification" to resultValue("diversification"),
                "nativeSymbolsScope" to resultValue("nativeSymbolsScope"),
                "privateRetraceFile" to resultValue("retraceFile"),
                "privateMappingFile" to resultValue("mappingFile"),
                "privateNativeSymbolsDirectory" to if (nativeSymbolsScope == "owned") nativeSymbolsLocation else "not-applicable",
                "privateThirdPartyNativeAudit" to thirdPartyNativeAudit.absolutePath,
                "privateThirdPartyNativeAuditSha256" to relayMaximumSha256(thirdPartyNativeAudit),
                "artifactManifest" to relayMaximumReleaseArtifactManifest.get().asFile.name,
                "artifactManifestSha256" to relayMaximumSha256(relayMaximumReleaseArtifactManifest.get().asFile),
            ) + privateEvidenceProperties,
        )
    }
}

val relayMaximumReleaseArchiveTask = tasks.register<Zip>("maximumReleaseArchive") {
    group = "distribution"
    description = "Archives the externally protected maximum-release relay bundle."
    dependsOn(relayMaximumReleasePrepare)
    archiveFileName.set("synesis-relay-${relayProtectionVersion.get()}-$relayMaximumPlatform-maximum-release.zip")
    destinationDirectory.set(relayMaximumReleaseDirectory)
    from(relayMaximumReleaseBundleDirectory) {
        into("synesis-relay-$relayMaximumPlatform-maximum-release")
    }
}

val relayMaximumReleaseNativeHardeningAudit = tasks.register("maximumReleaseNativeHardeningAudit") {
    group = "distribution"
    description = "Audits the protected relay archive's native hardening and platform signing before manifest signing."
    notCompatibleWithConfigurationCache(
        "Maximum relay release invokes the archive-only native hardening audit in the release environment."
    )
    dependsOn(relayMaximumReleaseArchiveTask)
    outputs.file(relayMaximumNativeHardeningEvidence)
    doLast {
        val developerArchiveValue = relayMaximumDeveloperArchive.orNull?.trim().orEmpty()
        require(developerArchiveValue.isNotBlank()) {
            "Maximum relay release requires SYNESIS_DEVELOPER_ARCHIVE (or -PsynesisDeveloperArchive) for the native hardening comparison."
        }
        val developerArchive = project.file(developerArchiveValue).absoluteFile
        require(developerArchive.isFile) { "Developer archive for relay native hardening audit is missing: $developerArchive" }
        val auditScript = rootProject.file("scripts/release-native-hardening-audit.ps1").absoluteFile
        require(auditScript.isFile) { "Native hardening audit script is missing: $auditScript" }
        val maximumArchive = relayMaximumReleaseArchiveTask.get().archiveFile.get().asFile.absoluteFile
        val evidence = relayMaximumNativeHardeningEvidence.get().asFile.absoluteFile
        val command = mutableListOf<String>()
        if (OperatingSystem.current().isWindows) {
            command.addAll(listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass"))
        } else {
            command.addAll(listOf("pwsh", "-NoProfile"))
        }
        command.addAll(
            listOf(
                "-File", auditScript.absolutePath,
                "-Component", "relay",
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
            "Maximum relay native hardening/signing audit failed with exit code $exitCode"
        }
        require(relayMaximumNativeAuditValue(evidence, "nativeHardeningStatus") == "PASS") {
            "Maximum relay native hardening audit did not report PASS"
        }
        require(relayMaximumNativeAuditValue(evidence, "nativeSigningStatus") in setOf("PASS", "NOT_APPLICABLE")) {
            "Maximum relay native signing audit did not report PASS or NOT_APPLICABLE"
        }
    }
}

val relayMaximumReleaseManifestTask = tasks.register("maximumReleaseManifest") {
    group = "distribution"
    description = "Creates the canonical signed-release manifest for the protected relay candidate."
    dependsOn(relayMaximumReleaseNativeHardeningAudit)
    outputs.file(relayMaximumReleaseManifest)
    doLast {
        fun json(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")

        val keyId = project.providers.gradleProperty("synesisSigningKeyId")
            .orElse(project.providers.environmentVariable("SYNESIS_MANIFEST_SIGNING_KEY_ID")).orNull?.trim().orEmpty()
        require(keyId.isNotBlank()) {
            "Maximum relay release requires SYNESIS_MANIFEST_SIGNING_KEY_ID; no signing key is generated here."
        }
        val publishedAt = project.providers.gradleProperty("synesisPublishedAt")
            .orElse(project.providers.environmentVariable("SYNESIS_RELEASE_PUBLISHED_AT")).orNull?.trim().orEmpty()
        require(publishedAt.isNotBlank()) {
            "Maximum relay release requires SYNESIS_RELEASE_PUBLISHED_AT for reproducible release metadata."
        }
        val minimumBootstrapVersion = project.providers.gradleProperty("synesisMinimumBootstrapVersion")
            .orElse(project.providers.environmentVariable("SYNESIS_MINIMUM_BOOTSTRAP_VERSION"))
            .orElse("0.1.0-dev.local").get()
        val archive = relayMaximumReleaseArchiveTask.get().archiveFile.get().asFile
        relayMaximumReleaseManifest.get().asFile.writeText(
            """{
  "schemaVersion": 1,
  "channel": "maximum-release",
  "component": "relay",
  "version": "${json(relayProtectionVersion.get())}",
  "publishedAt": "${json(publishedAt)}",
  "minimumBootstrapVersion": "${json(minimumBootstrapVersion)}",
  "developmentOnly": false,
  "signingKeyId": "${json(keyId)}",
  "releaseId": "${json(relayMaximumReleaseId.get())}",
  "artifacts": {
    "${json(relayMaximumPlatform)}": {
      "url": "${json(archive.name)}",
      "sha256": "${relayMaximumSha256(archive)}",
      "size": ${archive.length()}
    }
  }
}
""".trimIndent() + "\n"
        )
    }
}

val relayMaximumReleaseSignTask = tasks.register("maximumReleaseSign") {
    group = "distribution"
    description = "Signs the maximum-release relay manifest with the existing bootstrap signer."
    dependsOn(relayMaximumReleaseManifestTask)
    outputs.file(relayMaximumReleaseSignature)
    doLast {
        require(!System.getenv("SYNESIS_MANIFEST_PRIVATE_KEY_B64").isNullOrBlank()) {
            "Maximum relay release requires SYNESIS_MANIFEST_PRIVATE_KEY_B64; production signing keys are injected, never generated or committed."
        }
        val process = ProcessBuilder(
            "go", "run", "./cmd/sign-manifest",
            "--manifest", relayMaximumReleaseManifest.get().asFile.absolutePath,
            "--signature", relayMaximumReleaseSignature.get().asFile.absolutePath,
        )
            .directory(rootProject.file("bootstrap"))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val exitCode = process.waitFor()
        require(exitCode == 0 && relayMaximumReleaseSignature.get().asFile.isFile) {
            "Existing bootstrap manifest signer failed for the relay with exit code $exitCode"
        }
    }
}

tasks.register("maximumRelease") {
    group = "distribution"
    description = "Builds, signs, and verifies a licensed maximum-release relay candidate."
    dependsOn(relayMaximumReleaseSignTask)
    notCompatibleWithConfigurationCache(
        "Maximum relay release verifies an injected signature against the bootstrap trust root."
    )
    doLast {
        val bootstrapSource = rootProject.file("bootstrap/main.go").readText()
        val publicKeyHex = Regex("""manifestPublicKeyHex\s*=\s*\"([0-9a-fA-F]+)\"""")
            .find(bootstrapSource)?.groupValues?.get(1)
            ?: error("Embedded bootstrap manifest public key is missing")
        val publicKeyBytes = relayMaximumHexDecode(publicKeyHex)
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
        val signatureText = relayMaximumReleaseSignature.get().asFile.readText().trim()
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureText)
        } catch (exception: IllegalArgumentException) {
            throw GradleException("Maximum relay detached signature is not valid base64", exception)
        }
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        val manifestBytes = relayMaximumReleaseManifest.get().asFile.readBytes()
        verifier.update(manifestBytes)
        require(verifier.verify(signatureBytes)) {
            "Maximum relay manifest signature does not verify against bootstrap/main.go trust root"
        }
        require(relayMaximumReleaseManifest.get().asFile.readText().contains("\"developmentOnly\": false")) {
            "Maximum relay manifest must not be marked development-only"
        }

        val record = relayMaximumReleasePrivateDirectory.get().asFile.resolve("release-record.properties")
        val properties = readRelayMaximumProperties(record)
        val nativeHardeningEvidence = relayMaximumNativeHardeningEvidence.get().asFile
        require(nativeHardeningEvidence.isFile) { "Maximum relay native hardening evidence is missing" }
        val nativeSigningStatus = relayMaximumNativeAuditValue(nativeHardeningEvidence, "nativeSigningStatus")
        require(nativeSigningStatus in setOf("PASS", "NOT_APPLICABLE")) {
            "Maximum relay native signing audit did not report PASS or NOT_APPLICABLE"
        }
        properties.setProperty("nativeHardeningStatus", "verified")
        properties.setProperty(
            "nativeSigningStatus",
            if (nativeSigningStatus == "PASS") "verified" else "not-applicable",
        )
        properties.setProperty("privateNativeHardeningEvidence", nativeHardeningEvidence.absolutePath)
        properties.setProperty("privateNativeHardeningEvidenceSha256", relayMaximumSha256(nativeHardeningEvidence))
        val signingKeyId = project.providers.gradleProperty("synesisSigningKeyId")
            .orElse(project.providers.environmentVariable("SYNESIS_MANIFEST_SIGNING_KEY_ID"))
            .orNull?.trim().orEmpty()
        val publishedAt = project.providers.gradleProperty("synesisPublishedAt")
            .orElse(project.providers.environmentVariable("SYNESIS_RELEASE_PUBLISHED_AT"))
            .orNull?.trim().orEmpty()
        properties.setProperty("manifest", relayMaximumReleaseManifest.get().asFile.name)
        properties.setProperty("manifestSha256", relayMaximumSha256(relayMaximumReleaseManifest.get().asFile))
        properties.setProperty("signature", relayMaximumReleaseSignature.get().asFile.name)
        properties.setProperty("signatureSha256", relayMaximumSha256(relayMaximumReleaseSignature.get().asFile))
        properties.setProperty("signedIntegrity", "verified")
        properties.setProperty("signedAgainstBootstrapKey", "true")
        properties.setProperty("signingKeyId", signingKeyId)
        properties.setProperty("publishedAt", publishedAt)
        properties.setProperty("bootstrapPublicKeySha256", bootstrapPublicKeySha256)
        properties.setProperty("signingProvenance", "Ed25519 detached manifest signature verified against bootstrap/main.go trust root")
        record.outputStream().use { properties.store(it, "Synesis maximum-relay private record") }
        logger.lifecycle(
            "Maximum relay candidate verified: ${relayMaximumReleaseArchiveTask.get().archiveFile.get().asFile.absolutePath}"
        )
    }
}
