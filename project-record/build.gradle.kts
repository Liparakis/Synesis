import org.gradle.external.javadoc.StandardJavadocDocletOptions

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
    applicationName = "synesis-project-record"
    mainClass = "org.synesis.projectrecord.DecisionRecordCli"
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
    description = "Rejects trailing whitespace in project-record sources."
    doLast {
        val roots = listOf(project.file("src"), project.file("build.gradle.kts"))
        val files = roots.flatMap { candidate ->
            if (candidate.isDirectory) candidate.walkTopDown().filter { it.isFile }.toList() else listOf(candidate)
        }.filter { it.extension in setOf("java", "kt", "kts") }
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

tasks.register("launcherSmoke") {
    group = "verification"
    description = "Checks that the project-record launcher is generated."
    dependsOn(tasks.installDist)
    doLast {
        require(layout.buildDirectory.file("install/synesis-project-record/bin/synesis-project-record.bat").get().asFile.isFile)
        require(layout.buildDirectory.file("install/synesis-project-record/bin/synesis-project-record").get().asFile.isFile)
    }
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis", "launcherSmoke")
}
