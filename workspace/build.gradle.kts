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
    implementation(project(":project-record"))
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
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all", true)
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

tasks.test {
    useJUnitPlatform()
    val forkOverride = project.findProperty("synesisTestForks")?.toString()?.toIntOrNull()
    maxParallelForks = forkOverride ?: (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
}

tasks.register("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in workspace sources."
    doLast {
        val roots = listOf(project.file("src"), project.file("build.gradle.kts"))
        val files = roots.flatMap { candidate ->
            if (candidate.isDirectory) candidate.walkTopDown().filter { it.isFile }.toList() else listOf(candidate)
        }.filter { it.extension in setOf("java", "kt", "kts") }
        val offenders = files.filter { source -> source.useLines { lines -> lines.any { it.endsWith(" ") || it.endsWith("\t") } } }
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
    description = "Checks workspace package and module import boundaries."
    doLast {
        val guardrail = project.file("src/main/java/org/synesis/workspace/guardrail")
        val providerImports = guardrail.walkTopDown().filter { it.isFile && it.extension == "java" }
            .flatMap { file -> file.readLines().filter { it.contains("org.synesis.workspace.integration") } }
        require(providerImports.none()) { "Guardrail imports provider adapter: $providerImports" }
        val recordSources = project.file("../project-record/src/main/java")
        val reverseImports = recordSources.walkTopDown().filter { it.isFile && it.extension == "java" }
            .flatMap { file -> file.readLines().filter { it.contains("org.synesis.workspace") } }
        require(reverseImports.none()) { "Project-record imports workspace code: $reverseImports" }
    }
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis", "architectureCheck")
}
