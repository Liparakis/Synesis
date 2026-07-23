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

tasks.test { useJUnitPlatform() }

tasks.register("formatCheck") {
    group = "verification"
    doLast {
        val files = project.file("src").walkTopDown().filter { it.isFile && it.extension == "java" }
        require(files.none { file -> file.useLines { lines -> lines.any { it.endsWith(" ") || it.endsWith("\t") } } })
    }
}

tasks.register("staticAnalysis") { group = "verification"; dependsOn(tasks.compileJava, tasks.compileTestJava) }
tasks.check { dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis") }
