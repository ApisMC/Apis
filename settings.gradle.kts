import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    error("UltraCore directory must be a valid Git repository.")
}

rootProject.name = "ultracore"
for (name in listOf("ultracore-api", "ultracore-server", "ultracore-checkstyle")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val channel = providers.gradleProperty("channel").get().trim()
    val isRelease = providers.gradleProperty("release").map { it.toBoolean() }.getOrElse(false)
    val buildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = when {
        isRelease -> "$mcVersion-release"
        buildNumber == null -> "$mcVersion.local-SNAPSHOT"
        else -> "$mcVersion.build.$buildNumber-${channel.lowercase()}"
    }
    version = versionString
}
