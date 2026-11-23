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
    error(
        """
        =====================[ ERROR ]=====================
         The Apis directory is not a properly cloned Git repository.

         Clone via Git rather than downloading a source ZIP:
         https://github.com/ApisMC/Apis

         Documentation: https://apismc.github.io/
        ===================================================
        """.trimIndent()
    )
}

rootProject.name = "apis"
for (name in listOf("apis-api", "apis-server", "apis-checkstyle", "apis-paperclip")) {
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
