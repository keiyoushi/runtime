pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

rootProject.name = "keiyoushi-runtime"

include(":contract")
include(":host")

// Every directory under api/ and modules/ is a project; the host picks up modules/* by itself
for (group in listOf("api", "modules")) {
    File(rootDir, group)
        .listFiles()
        ?.filter { it.isDirectory && File(it, "build.gradle.kts").isFile }
        ?.sortedBy { it.name }
        ?.forEach { include(":$group:${it.name}") }
}
