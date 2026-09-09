import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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
