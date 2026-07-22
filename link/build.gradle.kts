import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
}

group = "org.synesis"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(libs.netty.codec.native.quic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val nativeQuicClassifier = when {
    OperatingSystem.current().isWindows -> "windows-x86_64"
    OperatingSystem.current().isMacOsX -> "osx-x86_64"
    OperatingSystem.current().isLinux -> "linux-x86_64"
    else -> null
}
nativeQuicClassifier?.let {
    dependencies.add("runtimeOnly", "io.netty:netty-codec-native-quic:${libs.versions.netty.get()}:$it")
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
    val forkOverride = project.findProperty("synesisTestForks")?.toString()?.toIntOrNull()
    maxParallelForks = forkOverride ?: (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
}

tasks.register<JavaExec>("demoCli") {
    group = "application"
    description = "Runs the source-only physical Synesis Link demonstration CLI."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.synesis.link.transport.DemoCli"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

fun filesUnder(dir: File, extensions: Set<String>): List<File> =
    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension in extensions }.toList()
    else listOfNotNull(dir.takeIf { it.isFile && it.extension in extensions })

tasks.register("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in tracked source and documentation files."
    doLast {
        val extensions = setOf("java", "kt", "kts", "md", "xml", "toml")
        val directoryRoots = listOf(project.file("src"), rootProject.file("docs"))
        val singleFileRoots = listOf(
            project.file("build.gradle.kts"),
            rootProject.file("README.md"),
            rootProject.file("AGENTS.md"),
            rootProject.file("CONTRIBUTING.md"),
            rootProject.file("SECURITY.md"),
            rootProject.file("settings.gradle.kts"),
            rootProject.file("build.gradle.kts")
        )
        val files = (directoryRoots + singleFileRoots).flatMap { filesUnder(it, extensions) }
        val offenders = files.filter { source ->
            source.useLines { lines -> lines.any { it.endsWith(" ") || it.endsWith("\t") } }
        }
        require(offenders.isEmpty()) { "Trailing whitespace: ${offenders.joinToString()}" }
    }
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs strict compiler diagnostics as the initial static analysis gate."
    dependsOn(tasks.compileJava, tasks.compileTestJava)
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis")
}