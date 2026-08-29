plugins {
    `java-library`
}

// Deliberately standalone: McpToolCatalog is the shared executable MCP
// contract consumed by both :mcp and :workspace. Keeping it here avoids the
// :mcp -> :workspace -> :mcp cycle that would result from placing it in :mcp,
// and keeps MCP protocol ownership out of the lower-level workspace module.
group = "org.synesis"
version = "0.1.0-SNAPSHOT"

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
    withJavadocJar()
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

tasks.register("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in MCP contract sources."
    doLast {
        val files = layout.projectDirectory.dir("src").asFile.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
        val offenders = files.filter { source ->
            source.useLines { lines -> lines.any { it.endsWith(" ") || it.endsWith("\t") } }
        }.toList()
        require(offenders.isEmpty()) { "Trailing whitespace: ${offenders.joinToString()}" }
    }
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs strict compiler diagnostics."
    dependsOn(tasks.compileJava)
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis")
}
