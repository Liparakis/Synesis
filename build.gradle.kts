plugins {
    base
}

tasks.named("clean") {
    dependsOn(":link:clean")
    dependsOn(":cli:clean")
    dependsOn(":project-record:clean")
}

tasks.named("check") {
    dependsOn(":link:check")
    dependsOn(":cli:check")
    dependsOn(":project-record:check")
}
