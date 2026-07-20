plugins {
    base
}

tasks.named("clean") {
    dependsOn(":link:clean")
}

tasks.named("check") {
    dependsOn(":link:check")
}
