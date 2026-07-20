import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "synesis"

include(":link")
include(":cli")
