plugins {
    `java-library`
}

val upstreamPaperclip = configurations.create("upstreamPaperclip")
configurations.named("compileOnly") {
    extendsFrom(upstreamPaperclip)
}

dependencies {
    upstreamPaperclip("io.papermc:paperclip:3.0.4")
    compileOnly("org.jspecify:jspecify:1.0.0")
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "io.papermc.paperclip.Main")
    }
    from(upstreamPaperclip.elements.map { files ->
        files.map { file ->
            zipTree(file.asFile).matching {
                exclude(
                    "io/papermc/paperclip/Paperclip.class",
                    "io/papermc/paperclip/Paperclip\$*.class",
                    "io/papermc/paperclip/FileEntry.class",
                    "io/papermc/paperclip/FileEntry\$*.class",
                    "io/papermc/paperclip/Util.class",
                    "io/papermc/paperclip/Util\$*.class",
                    "META-INF/MANIFEST.MF"
                )
            }
        }
    })
}
