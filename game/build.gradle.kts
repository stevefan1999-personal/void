import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("tasks.metadata")
    id("shared")
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.jib)
    // Integrates jlink (generates a minimal JRE matching this module's needs)
    // with jib (daemonless, multi-arch container build).
    alias(libs.plugins.jlink.jib)
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":cache"))
    implementation(project(":network"))
    implementation(project(":types"))
    implementation(project(":config"))
    if (findProperty("includeDb") != null) {
        implementation(project(":database"))
    }
    implementation(libs.fastutil)
    implementation(libs.kasechange)
    implementation(libs.rsmod.pathfinder)

    implementation(kotlin("script-runtime"))
    implementation(libs.bundles.kotlinx)

    implementation(libs.koin)
    implementation(libs.bundles.logging)

    testImplementation(libs.bundles.testing)
}

application {
    mainClass.set("Main")
    tasks.run.get().workingDir = rootProject.projectDir
}

tasks {
    processResources {
        dependsOn("scriptMetadata")
    }

    named("build") {
        dependsOn("scriptMetadata")
    }

    named("classes") {
        dependsOn("scriptMetadata")
    }

    register("scriptMetadata", ScriptMetadataTask::class.java) {
        val main = sourceSets.getByName("main")
        val resources = main.resources.srcDirs.first { it.name == "resources" }
        inputDirectory.set(layout.projectDirectory.dir("src/main/kotlin/content"))
        scriptsFile = resources.resolve("scripts.txt")
        resourceDirectory = resources
    }

    named<ShadowJar>("shadowJar") {
        dependsOn("scriptMetadata")
        from(layout.buildDirectory.file("scripts.txt"))
        val db = findProperty("includeDb") != null
        val includeOracle = findProperty("includeOracle") != null
        if (db) {
            // Each JDBC driver ships its own `META-INF/services/java.sql.Driver`.
            // HikariCP explicitly loads `driverClassName` via Class.forName, so
            // a missing ServiceLoader entry is not fatal – but merging lets
            // `DriverManager.getConnection(url)` auto-detect all supported
            // backends.
            mergeServiceFiles()
            // sqlite-jdbc ships ~14 MB of cross-platform native `.so/.dylib/.dll`
            // binaries. We keep the pure-Java driver but expect operators to
            // install a matching native via `-Dorg.sqlite.lib.path` or a system
            // package. Strip the bundled binaries to keep the fat jar slim.
            exclude("org/sqlite/native/**")
        }
        minimize {
            if (db) {
                exclude(project(":database"))
                exclude(dependency("org.postgresql:postgresql:.*"))
                exclude(dependency("org.jetbrains.exposed:exposed-jdbc:.*"))
                // JDBC drivers are discovered via ServiceLoader at runtime, so
                // the shadow minimizer can't see they're used. Keep them.
                exclude(dependency("com.mysql:mysql-connector-j:.*"))
                exclude(dependency("org.mariadb.jdbc:mariadb-java-client:.*"))
                exclude(dependency("org.xerial:sqlite-jdbc:.*"))
                if (includeOracle) {
                    exclude(dependency("com.oracle.database.jdbc:ojdbc11:.*"))
                }
            }
            exclude("world/gregs/voidps/engine/log/**")
            exclude(dependency("ch.qos.logback:logback-classic:.*"))
        }
        if (db) {
            archiveBaseName.set("void-server-db-$version")
        } else {
            archiveBaseName.set("void-server-$version")
        }
        archiveClassifier.set("")
        archiveVersion.set("")
        // Replace logback file as the custom colour classes can't be individually excluded from minimization
        // https://github.com/GradleUp/shadow/issues/638
        exclude("logback.xml")
        val resourcesDir = layout.projectDirectory.dir("src/main/resources")
        val logback =
            resourcesDir
                .file("logback.xml")
                .asFile
                .readText()
                .replace("%colour", "%highlight")
                .replace("%message(%msg){}", "%msg")
        val replacement =
            layout.buildDirectory
                .file("logback-test.xml")
                .get()
                .asFile
        replacement.parentFile.mkdirs()
        replacement.writeText(logback)
        from(replacement)
    }

    register("printVersion") {
        val version = project.version.toString()
        doLast {
            println(version)
        }
    }

    register("printCacheVersion") {
        val message = libs.versions.cacheVersion.get()
        doLast {
            println(message)
        }
    }

    test {
        jvmArgs("-XX:-OmitStackTraceInFastThrow")
    }
}

distributions {
    create("bundle") {
        distributionBaseName = "void"
        contents {
            from(tasks["shadowJar"])

            val emptyDirs = setOf("cache", "saves")
            val data =
                parent!!
                    .rootDir
                    .resolve("data")
            for (config in data.list()) {
                if (emptyDirs.contains(config) || config == ".temp") {
                    continue
                }
                if (config.contains(".")) {
                    from("../data/$config") {
                        into("data")
                    }
                } else {
                    from("../data/$config/") {
                        into("data/$config")
                    }
                }
            }
            for (dir in emptyDirs) {
                val file =
                    layout.buildDirectory
                        .get()
                        .dir("tmp/empty/$dir/")
                        .asFile
                file.mkdirs()
            }
            from(layout.buildDirectory.dir("tmp/empty/")) {
                into("data")
            }
            val tempDir =
                layout.buildDirectory
                    .dir("tmp/scripts")
                    .get()
                    .asFile
            tempDir.mkdirs()
            val resourcesDir = layout.projectDirectory.dir("src/main/resources")
            from(resourcesDir.file("game.properties"))
            val bat = resourcesDir.file("run-server.bat").asFile
            val tempBat = File(tempDir, "run-server.bat")
            tempBat.writeText(bat.readText().replace("-dev.jar", "-$version.jar"))
            from(tempBat)
            val shell = resourcesDir.file("run-server.sh").asFile
            val tempShell = File(tempDir, "run-server.sh")
            tempShell.writeText(shell.readText().replace("-dev.jar", "-$version.jar"))
            from(tempShell)
        }
    }
}

dependencies {
    allprojects.filter { it.name != "tools" }.forEach {
        jacocoAggregation(it)
    }
}

// --- Container image (jib + jlink) -------------------------------------
//
// Use `./gradlew :game:jib -PincludeDb` to push a multi-arch OCI image directly
// to a registry, or `./gradlew :game:jibDockerBuild -PincludeDb` for a local
// daemon load. The `com.ryandens.jlink-jib-application` plugin runs `jlink` on
// the JDK first so the image ships a minimal runtime (typically ~40 MB) instead
// of a full JRE. No Docker daemon is required for the registry push path.
val jibImage: String = (findProperty("jib.image") as String?) ?: "ghcr.io/greghib/void"
val jibTag: String = (findProperty("jib.tag") as String?) ?: "latest"

// Modules the jdeps-driven default omits because they are only wired up
// reflectively at runtime (logback's SAX parser, JDBC SPI, JMX, sun.misc.Unsafe,
// TLS, compression, …).
jlinkJre {
    modules.addAll(
        "java.base",
        "java.xml",
        "java.naming",
        "java.sql",
        "java.logging",
        "java.management",
        "java.security.jgss",
        "java.rmi",
        "java.net.http",
        "java.instrument",
        "jdk.crypto.ec",
        "jdk.unsupported",
        "jdk.naming.dns",
        "jdk.zipfs",
    )
}

jib {
    from {
        // `java-base-debian12` ships glibc + libz etc. but no JDK, so jlink's
        // custom JRE from the `com.ryandens.jlink-jib` plugin runs against the
        // runtime libraries it expects without the bloat of a full JRE image.
        image = "gcr.io/distroless/java-base-debian12:nonroot"
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
            platform {
                architecture = "arm64"
                os = "linux"
            }
        }
    }
    to {
        image = jibImage
        tags = setOf(jibTag, project.version.toString())
    }
    container {
        mainClass = "Main"
        ports = listOf("43594", "8080")
        workingDirectory = "/app"
        // Override the distroless `nonroot` UID – the server writes to
        // `/app/data/.temp` + `/app/data/saves` at start-up and those paths
        // come out of the image as root-owned. For production, switch back to
        // `nonroot` and mount the writable subdirs as volumes owned by 65532.
        user = "0:0"
        labels.set(
            mapOf(
                "org.opencontainers.image.title" to "Void RSPS",
                "org.opencontainers.image.source" to "https://github.com/GregHib/void",
                "org.opencontainers.image.licenses" to "ISC",
            ),
        )
    }
    extraDirectories {
        paths {
            path {
                setFrom(rootProject.layout.projectDirectory.dir("data"))
                into = "/app/data"
            }
        }
    }
}

spotless {
    kotlin {
        target("**/*.kt", "**/*.kts")
        targetExclude("temp/", "**/build/**", "**/out/**")
        ktlint()
            .editorConfigOverride(
                mapOf(
                    "ktlint_code_style" to "intellij_idea",
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_package-name" to "disabled",
                ),
            )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
    flexmark {
        target("**/*.md")
        flexmark()
    }
}
