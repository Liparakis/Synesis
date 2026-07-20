plugins {
    base
}

tasks.named("clean") {
    dependsOn(":link:clean")
    dependsOn(":cli:clean")
}

tasks.named("check") {
    dependsOn(":link:check")
    dependsOn(":cli:check")
}
