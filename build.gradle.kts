import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.24"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    upstreams.paper {
        repo = github("PaperMC", "Paper")
        ref = providers.gradleProperty("paperCommit")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("ultracore-server/build.gradle.kts")
            patchFile = file("ultracore-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("ultracore-api/build.gradle.kts")
            patchFile = file("ultracore-api/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-checkstyle/build.gradle.kts"
            outputFile = file("ultracore-checkstyle/build.gradle.kts")
            patchFile = file("ultracore-checkstyle/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("ultracore-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("paperCheckstyle") {
            upstreamPath = "paper-checkstyle"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("ultracore-checkstyle/paper-patches")
            outputDir = file("paper-checkstyle")
        }
        patchDir("paperCheckstyleConfig") {
            upstreamPath = ".checkstyle"
            patchesDir = file("ultracore-checkstyle/config-patches")
            outputDir = file(".checkstyle")
        }
    }
}

subprojects {
    apply {
        plugin("java-library")
        plugin("maven-publish")
        plugin("io.papermc.paperweight.paper-checkstyle")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        "checkstyle"(project(":ultracore-checkstyle"))
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}
