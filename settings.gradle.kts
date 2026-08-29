@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "synesis"

include(":link")
include(":cli")
include(":project-record")
include(":workspace")
include(":coordination")
include(":mcp")
include(":mcp-contract")
