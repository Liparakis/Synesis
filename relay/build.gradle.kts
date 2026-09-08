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

dependencies {
    implementation(project(":link"))
    implementation(libs.netty.codec.native.quic)
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
