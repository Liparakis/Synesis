import org.gradle.internal.os.OperatingSystem
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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
    mainClass = "org.synesis.link.cli.DemoCli"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

fun filesUnder(dir: File, extensions: Set<String>): List<File> =
    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension in extensions }.toList()
    else listOfNotNull(dir.takeIf { it.isFile && it.extension in extensions })

val linkRepositoryRoot = rootProject.layout.projectDirectory.asFile
val linkFormatRoots = listOf(
    layout.projectDirectory.dir("src").asFile,
    linkRepositoryRoot.resolve("docs"),
    layout.projectDirectory.file("build.gradle.kts").asFile,
    linkRepositoryRoot.resolve("README.md"),
    linkRepositoryRoot.resolve("AGENTS.md"),
    linkRepositoryRoot.resolve("CONTRIBUTING.md"),
    linkRepositoryRoot.resolve("SECURITY.md"),
    linkRepositoryRoot.resolve("settings.gradle.kts"),
    linkRepositoryRoot.resolve("build.gradle.kts")
)

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

tasks.register<TrailingWhitespaceTask>("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in tracked source and documentation files."
    files.from(linkFormatRoots.map { root ->
        if (root.isDirectory) fileTree(root) {
            include("**/*.java")
            include("**/*.kt")
            include("**/*.kts")
            include("**/*.md")
            include("**/*.xml")
            include("**/*.toml")
        } else root
    })
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs strict compiler diagnostics as the initial static analysis gate."
    dependsOn(tasks.compileJava, tasks.compileTestJava)
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis")
}
