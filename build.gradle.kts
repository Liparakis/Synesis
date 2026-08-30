import java.net.URLDecoder
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    base
}

val repositoryRoot = rootProject.layout.projectDirectory.asFile
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

abstract class RepositoryHygieneTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val markdownFiles: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scriptsDirectory: DirectoryProperty

    @TaskAction
    fun check() {
        val repoRoot = repositoryRoot.get().asFile
        val files = markdownFiles.files.sortedBy { it.relativeTo(repoRoot).path }
        require(files.isNotEmpty()) { "No maintained Markdown files found" }
        val failures = mutableListOf<String>()
        val linkPattern = Regex("\\[[^]]*]\\(([^)]+)\\)")
        val scriptPattern = Regex("(?:scripts|install)/[A-Za-z0-9._/-]+\\.(?:ps1|cmd|bat|sh)")
        val countPattern = Regex("(?i)\\b(\\d+)\\s*[- ]?tools?\\b")
        val staleProviderPattern =
            Regex("(?i)synesis\\s+provider\\s+(?:install|status|uninstall|migrate)\\s+claude-code\\b")
        files.forEach { file ->
            val relative = file.relativeTo(repoRoot).invariantSeparatorsPath
            val text = file.readText()
            text.lineSequence().forEachIndexed { index, line ->
                if (line.endsWith(" ") || line.endsWith("\t")) failures += "$relative:${index + 1}: trailing whitespace"
                if (Regex("(?i)(?:[A-Z]:[\\\\/](?:Users|home)|/(?:Users|home)/|file://)").containsMatchIn(line)) {
                    failures += "$relative:${index + 1}: machine-specific absolute path"
                }
            }
            linkPattern.findAll(text).forEach { match ->
                val raw = match.groupValues[1].trim().substringBefore(" ").substringBefore("\"")
                if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith(
                        "mailto:"
                    )
                ) return@forEach
                val target = URLDecoder.decode(raw, Charsets.UTF_8)
                val resolved = file.parentFile.resolve(target).normalize()
                if (!resolved.exists()) failures += "$relative: missing Markdown target $raw"
            }
            scriptPattern.findAll(text).forEach { match ->
                val script = repoRoot.resolve(match.value.replace('/', File.separatorChar))
                if (!script.isFile) failures += "$relative: missing script ${match.value}"
            }
            if (staleProviderPattern.containsMatchIn(text)) failures += "$relative: canonical provider command uses claude-code"
            countPattern.findAll(text).forEach { match ->
                if (match.groupValues[1] != "10") failures += "$relative: MCP tool count is ${match.groupValues[1]}, expected 10"
            }
        }
        val scripts = scriptsDirectory.get().asFile.listFiles()
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

tasks.named("clean") {
    dependsOn(":link:clean")
    dependsOn(":cli:clean")
    dependsOn(":project-record:clean")
    dependsOn(":workspace:clean")
    dependsOn(":coordination:clean")
    dependsOn(":mcp:clean")
    dependsOn(":mcp-contract:clean")
}

tasks.named("check") {
    dependsOn(":link:check")
    dependsOn(":cli:check")
    dependsOn(":project-record:check")
    dependsOn(":workspace:check")
    dependsOn(":coordination:check")
    dependsOn(":mcp:check")
    dependsOn(":mcp-contract:check")
    dependsOn("repositoryHygieneCheck")
}

tasks.register<RepositoryHygieneTask>("repositoryHygieneCheck") {
    group = "verification"
    description = "Checks maintained Markdown links, paths, provider names, scripts, and MCP claims."
    repositoryRoot.set(layout.projectDirectory)
    markdownFiles.from(maintainedMarkdownRoots.map { root ->
        if (root.isDirectory) fileTree(root) { include("**/*.md") } else root
    })
    scriptsDirectory.set(layout.projectDirectory.dir("scripts"))
}
