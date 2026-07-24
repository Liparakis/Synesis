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
    implementation(project(":link"))
    implementation(project(":project-record"))
    implementation(project(":coordination"))
    implementation(project(":workspace"))
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
    val forkOverride = project.findProperty("synesisTestForks")?.toString()?.toIntOrNull()
    maxParallelForks = forkOverride ?: (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
}

fun filesUnder(dir: File, extensions: Set<String>): List<File> =
    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension in extensions }.toList()
    else listOfNotNull(dir.takeIf { it.isFile && it.extension in extensions })

fun linesContaining(dir: File, pattern: String): List<String> =
    filesUnder(dir, setOf("java")).flatMap { file -> file.readLines().filter { it.contains(pattern) } }

tasks.register("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in MCP sources."
    doLast {
        val files = filesUnder(project.file("src"), setOf("java", "kt", "kts")) +
                filesUnder(project.file("build.gradle.kts"), setOf("kts"))
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

tasks.register("architectureCheck") {
    group = "verification"
    description = "Checks MCP subproject import boundaries."
    doLast {
        val cliHits = linesContaining(
            project.file("src/main/java"),
            "import org.synesis.cli"
        )
        require(cliHits.none()) { "MCP imports CLI code: $cliHits" }

        val reverseHits = linesContaining(
            project.file("../cli/src/main/java"),
            "import org.synesis.mcp"
        )
        require(reverseHits.none()) { "CLI imports MCP code: $reverseHits" }
    }
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis", "architectureCheck")
}
