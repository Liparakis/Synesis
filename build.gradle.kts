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
    implementation(libs.netty.codec.native.quic)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val nativeQuicClassifier = when {
    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
    System.getProperty("os.name").lowercase().contains("mac") -> "osx-x86_64"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux-x86_64"
    else -> null
}
if (nativeQuicClassifier != null) {
    dependencies.add("runtimeOnly", "io.netty:netty-codec-native-quic:${libs.versions.netty.get()}:$nativeQuicClassifier")
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
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("demoCli") {
    group = "application"
    description = "Runs the source-only physical Synesis Link demonstration CLI."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.synesis.link.transport.DemoCli"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register("formatCheck") {
    group = "verification"
    description = "Rejects trailing whitespace in tracked source and documentation files."
    doLast {
        val roots = listOf("src", "docs", "README.md", "AGENTS.md")
        val files = roots.flatMap { root ->
            val candidate = file(root)
            if (candidate.isDirectory) candidate.walkTopDown().filter { it.isFile }.toList() else listOf(candidate)
        }.filter { it.extension in setOf("java", "kt", "kts", "md", "xml", "toml") }
        val offenders = files.filter { source -> source.useLines { lines -> lines.any { it.endsWith(" ") || it.endsWith("\t") } } }
        require(offenders.isEmpty()) { "Trailing whitespace: ${offenders.joinToString()}" }
    }
}

tasks.register("packageInfoCheck") {
    group = "verification"
    description = "Ensures every production Java package has package-info.java."
    doLast {
        val packageDirectories = fileTree("src/main/java").matching { include("**/*.java") }
            .files.map { it.parentFile }.distinct()
        val missing = packageDirectories.filter { !it.resolve("package-info.java").isFile }
        require(missing.isEmpty()) { "Missing package-info.java: ${missing.joinToString()}" }
    }
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs strict compiler diagnostics as the initial static analysis gate."
    dependsOn(tasks.compileJava, tasks.compileTestJava)
}

tasks.check {
    dependsOn(tasks.javadoc, "formatCheck", "packageInfoCheck", "staticAnalysis")
}
