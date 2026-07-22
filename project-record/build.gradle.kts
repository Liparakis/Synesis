import org.gradle.external.javadoc.StandardJavadocDocletOptions

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

tasks.register("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in project-record sources."
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

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis")
}