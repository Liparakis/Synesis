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

tasks.register("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in relay sources."
    doLast {
        val roots = listOf(layout.projectDirectory.dir("src").asFile, layout.projectDirectory.file("build.gradle.kts").asFile)
        val files = roots.flatMap { root ->
            if (root.isDirectory) root.walkTopDown().filter { it.isFile && it.extension in setOf("java", "kt", "kts") }.toList()
            else listOf(root)
        }
        val offenders = files.filter { file -> file.useLines { lines -> lines.any { it.endsWith(" ") || it.endsWith("\t") } } }
        require(offenders.isEmpty()) { "Trailing whitespace: ${offenders.joinToString()}" }
    }
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "staticAnalysis")
}
