import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.19"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    upstreams.paper {
        repo = github("PaperMC", "Paper")
        ref = providers.gradleProperty("paperCommit")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("apis-server/build.gradle.kts")
            patchFile = file("apis-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("apis-api/build.gradle.kts")
            patchFile = file("apis-api/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("apis-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

subprojects {
    apply {
        plugin("java-library")
        plugin("maven-publish")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    val isRelease = providers.gradleProperty("release").map { it.toBoolean() }.getOrElse(false)
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21
        options.isFork = true
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:-deprecation",
                "-Xlint:-removal",
                if (isRelease) "-g:lines,source" else "-g",
            )
        )
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

    /* extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.apismc.org/snapshots") {
                name = "apismc"
                credentials(PasswordCredentials::class)
            }
        }
    } */
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}

tasks.register("quickCheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs Apis unit tests (ApisTestSuite) without upstream Paper tests."
    dependsOn(":apis-server:apisTest")
}
