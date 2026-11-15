tasks.register("build") {
    // Use task paths so subproject tasks are resolved lazily (subprojects may not be configured yet).
    dependsOn(subprojects.map { "${it.path}:build" })
}
