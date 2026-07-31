import java.net.URLDecoder

plugins {
    base
}

val maintainedMarkdownRoots = listOf(
    rootProject.layout.projectDirectory.file("README.md").asFile,
    rootProject.layout.projectDirectory.file("AGENTS.md").asFile,
    rootProject.layout.projectDirectory.file("CONTRIBUTING.md").asFile,
    rootProject.layout.projectDirectory.file("SECURITY.md").asFile,
    rootProject.layout.projectDirectory.dir("docs/getting-started").asFile,
    rootProject.layout.projectDirectory.dir("docs/providers").asFile,
    rootProject.layout.projectDirectory.dir("docs/architecture").asFile,
    rootProject.layout.projectDirectory.dir("docs/operations").asFile,
    rootProject.layout.projectDirectory.dir("docs/development").asFile,
    rootProject.layout.projectDirectory.dir("docs/installation").asFile,
    rootProject.layout.projectDirectory.dir("docs/integration").asFile,
    rootProject.layout.projectDirectory.dir("docs/cli").asFile,
    rootProject.layout.projectDirectory.dir("docs/diagnostics").asFile,
    rootProject.layout.projectDirectory.dir("docs/security").asFile
)

fun maintainedMarkdownFiles(): List<File> = maintainedMarkdownRoots.flatMap { root ->
    if (root.isFile) listOf(root)
    else root.walkTopDown().filter { it.isFile && it.extension == "md" }.toList()
}

tasks.named("clean") {
    dependsOn(":link:clean")
    dependsOn(":cli:clean")
    dependsOn(":project-record:clean")
    dependsOn(":workspace:clean")
    dependsOn(":coordination:clean")
    dependsOn(":mcp:clean")
}

tasks.named("check") {
    dependsOn(":link:check")
    dependsOn(":cli:check")
    dependsOn(":project-record:check")
    dependsOn(":workspace:check")
    dependsOn(":coordination:check")
    dependsOn(":mcp:check")
    dependsOn("repositoryHygieneCheck")
}

tasks.register("repositoryHygieneCheck") {
    group = "verification"
    description = "Checks maintained Markdown links, paths, provider names, scripts, and MCP claims."
    doLast {
        val files = maintainedMarkdownFiles().distinct().sortedBy { it.relativeTo(rootDir).path }
        require(files.isNotEmpty()) { "No maintained Markdown files found" }
        val failures = mutableListOf<String>()
        val linkPattern = Regex("\\[[^]]*]\\(([^)]+)\\)")
        val scriptPattern = Regex("(?:scripts|install)/[A-Za-z0-9._/-]+\\.(?:ps1|cmd|bat|sh)")
        val countPattern = Regex("(?i)\\b(\\d+)\\s*[- ]?tools?\\b")
        val staleProviderPattern = Regex("(?i)synesis\\s+provider\\s+(?:install|status|uninstall|migrate)\\s+claude-code\\b")
        files.forEach { file ->
            val relative = file.relativeTo(rootDir).invariantSeparatorsPath
            val text = file.readText()
            text.lineSequence().forEachIndexed { index, line ->
                if (line.endsWith(" ") || line.endsWith("\t")) failures += "$relative:${index + 1}: trailing whitespace"
                if (Regex("(?i)(?:[A-Z]:[\\\\/](?:Users|home)|/(?:Users|home)/|file://)").containsMatchIn(line)) {
                    failures += "$relative:${index + 1}: machine-specific absolute path"
                }
            }
            linkPattern.findAll(text).forEach { match ->
                val raw = match.groupValues[1].trim().substringBefore(" ").substringBefore("\"")
                if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("mailto:")) return@forEach
                val target = URLDecoder.decode(raw, Charsets.UTF_8)
                val resolved = file.parentFile.resolve(target).normalize()
                if (!resolved.exists()) failures += "$relative: missing Markdown target $raw"
            }
            scriptPattern.findAll(text).forEach { match ->
                val script = rootDir.resolve(match.value.replace('/', File.separatorChar))
                if (!script.isFile) failures += "$relative: missing script ${match.value}"
            }
            if (staleProviderPattern.containsMatchIn(text)) failures += "$relative: canonical provider command uses claude-code"
            countPattern.findAll(text).forEach { match ->
                if (match.groupValues[1] != "10") failures += "$relative: MCP tool count is ${match.groupValues[1]}, expected 10"
            }
        }
        val scripts = rootProject.layout.projectDirectory.dir("scripts").asFile.listFiles()
            ?.filter { it.isFile && it.extension in setOf("ps1", "cmd", "bat", "sh") }
            ?.map { it.nameWithoutExtension.lowercase() }
            ?: emptyList()
        val duplicateNames = scripts.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .filterNot { it == "install" }
        if (duplicateNames.isNotEmpty()) failures += "duplicate active script entrypoints: ${duplicateNames.joinToString()}"
        require(failures.isEmpty()) { "Repository hygiene failures:\n${failures.joinToString("\n")}" }
        logger.lifecycle("Repository hygiene: ${files.size} maintained Markdown files checked; script references and 10-tool claims are valid.")
    }
}
