import groovy.json.JsonOutput
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.net.URI
import java.util.Properties

plugins {
    base
    distribution
    id("com.diffplug.spotless")
}

spotless {
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

evaluationDependsOn(":game")
evaluationDependsOn(":web:site")

val repository = rootProject.layout.projectDirectory
val repositoryFile = repository.asFile
val defaultProperties =
    Properties().apply {
        load(
            providers
                .fileContents(repository.file("game/src/main/resources/game.properties"))
                .asText
                .get()
                .reader(),
        )
    }
val defaults = defaultProperties.stringPropertyNames().associateWith(defaultProperties::getProperty)
val pins =
    Properties().apply {
        load(
            providers
                .fileContents(repository.file("gradle/teavm-client.properties"))
                .asText
                .get()
                .reader(),
        )
    }
val serverJar = project(":game").tasks.named<AbstractArchiveTask>("shadowJar")
val serverVersion = project(":game").version.toString()
version = serverVersion.replace(Regex("[^A-Za-z0-9._-]"), "-")

fun browserOption(
    property: String,
    environment: String,
    default: String,
) = providers.gradleProperty(property).orElse(providers.environmentVariable(environment)).orElse(default)

fun browserDirectory(path: Provider<String>): Provider<File> {
    val base = rootProject.projectDir
    return path.map {
        val directory = File(it)
        if (directory.isAbsolute) directory else File(base, it)
    }
}

fun browserNumber(
    value: Provider<String>,
    name: String,
    maximum: Int = Int.MAX_VALUE,
): Provider<String> =
    value.map {
        val number = it.toIntOrNull()
        require(number != null && number in 1..maximum) { "$name must be an integer between 1 and $maximum" }
        number.toString()
    }

val webPort =
    browserNumber(
        browserOption("browserWebPort", "VOID_BROWSER_WEB_PORT", defaults.getValue("web.server.port")),
        "browserWebPort",
        UShort.MAX_VALUE.toInt(),
    )
val gamePort =
    browserNumber(
        browserOption("browserGamePort", "VOID_BROWSER_GAME_PORT", defaults.getValue("network.port")),
        "browserGamePort",
        UShort.MAX_VALUE.toInt(),
    )
val worldNumber =
    browserNumber(
        browserOption("browserWorld", "VOID_BROWSER_WORLD", defaults.getValue("world.id")),
        "browserWorld",
    )
val worldName = browserOption("browserWorldName", "VOID_BROWSER_WORLD_NAME", defaults.getValue("world.name"))
val publicUrl = browserOption("browserPublicUrl", "VOID_BROWSER_PUBLIC_URL", "http://localhost:${webPort.get()}")
val cacheDirectory =
    browserDirectory(
        browserOption("browserCacheDir", "VOID_BROWSER_CACHE_DIR", repository.dir("data/cache").asFile.path),
    )
val worldsInput = providers.gradleProperty("browserWorlds").orElse(providers.environmentVariable("VOID_BROWSER_WORLDS"))
val assetsInput = providers.gradleProperty("browserAssetsSource").orElse(providers.environmentVariable("VOID_BROWSER_ASSETS_SOURCE"))

val buildClient =
    tasks.register<TeaVmClientBuild>("buildClient") {
        group = "build"
        description = "Build the pinned TeaVM client with its own wrapper and browser-only toolchain."
        repositoryDirectory.set(repository)
        sourceDirectory.set(repository.dir("external/teavm-client"))
        expectedRevision.set(pins.getProperty("client.revision"))
        teaVmVersion.set(pins.getProperty("teavm.version"))
        denoVersion.set(pins.getProperty("deno.version"))
        outputDirectory.set(layout.buildDirectory.dir("client"))
    }

val trackedData =
    providers
        .exec {
            workingDir(repositoryFile)
            commandLine("git", "ls-files", "-z", "--", "data/")
        }.standardOutput.asText
        .map { output -> output.split('\u0000').filter(String::isNotEmpty) }

val stageData =
    tasks.register<Sync>("stageData") {
        from(repositoryFile) {
            include(trackedData.get())
            exclude("data/void-client.js", "data/cache/**", "data/saves/**", "data/.temp/**")
            exclude("data/avatars/**", "data/errors/**", "data/reports/**", "data/grand_exchange/**")
        }
        into(layout.buildDirectory.dir("static"))
    }

val stateDirectories =
    tasks.register("stateDirectories") {
        outputs.dir(layout.buildDirectory.dir("empty/data"))
        doLast {
            val directory = outputs.files.singleFile
            listOf("cache", "saves", "errors", "avatars", "reports", "grand_exchange", ".temp").forEach {
                directory.resolve(it).mkdirs()
            }
        }
    }

val writeWorlds =
    tasks.register("writeWorlds") {
        inputs.property("publicUrl", publicUrl)
        inputs.property("number", worldNumber)
        inputs.property("name", worldName)
        inputs.property("gamePort", gamePort)
        if (worldsInput.isPresent) {
            inputs.file(repository.file(worldsInput.get()))
        }
        outputs.file(layout.buildDirectory.file("configuration/worlds.json"))
        doLast {
            val destination = outputs.files.singleFile
            destination.parentFile.mkdirs()
            val supplied = inputs.files.files.singleOrNull()
            if (supplied != null) {
                supplied.copyTo(destination, overwrite = true)
            } else {
                // URI parser errors can contain credentials, so keep the configuration error value-free.
                val uri =
                    runCatching { URI(inputs.properties.getValue("publicUrl").toString()) }.getOrElse {
                        throw GradleException("browserPublicUrl must be a valid absolute HTTP(S) base URL")
                    }
                require(uri.scheme in setOf("http", "https") && uri.host != null) { "Use an absolute HTTP(S) browserPublicUrl" }
                require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                    "browserPublicUrl must not contain credentials, query, or fragment"
                }
                val number =
                    inputs.properties
                        .getValue("number")
                        .toString()
                        .toInt()
                require(number > 0) { "browserWorld must be positive" }
                val world =
                    mapOf(
                        "number" to number,
                        "name" to inputs.properties.getValue("name").toString(),
                        "region" to "Local",
                        "mode" to "Normal",
                        "address" to "${uri.host}:${inputs.properties.getValue("gamePort")}",
                        "web" to uri.toString().trimEnd('/'),
                    )
                destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(listOf(world))) + "\n")
            }
        }
    }

val runtimeProperties =
    tasks.register<WriteProperties>("runtimeProperties") {
        destinationFile.set(layout.buildDirectory.file("configuration/game.properties"))
        properties(defaults)
        property("network.port", gamePort)
        property("world.id", worldNumber)
        property("world.name", worldName)
        property("web.server.enabled", "true")
        property("web.server.port", webPort)
        property("web.client.path", "./data/void-client.js")
        property("web.server.pages", "./pages/")
        property("web.api.enabled", "false")
        property("web.api.registration", "false")
        property("web.api.token", "")
        property("development.admin.name", "")
        property("storage.data", "./data/")
        property("storage.cache.path", "./data/cache/")
        property("storage.players.path", "./data/saves/")
        property("storage.players.logs", "./data/saves/logs/")
        property("storage.players.errors", "./data/errors/")
        property("storage.bots.path", "./data/saves/bots/")
        property("storage.wildcards", "./data/.temp/wildcards.txt")
        property("storage.caching.path", "./data/.temp/")
        property("storage.scripts.path", "./data/.temp/scripts/")
        property("web.avatars.path", "./data/avatars/")
    }

val siteProperties =
    tasks.register<WriteProperties>("siteProperties") {
        dependsOn(stageData, writeWorlds)
        destinationFile.set(layout.buildDirectory.file("configuration/site.properties"))
        properties(defaults)
        property(
            "storage.data",
            layout.buildDirectory
                .dir("static/data")
                .get()
                .asFile.path + "/",
        )
        property("storage.cache.path", cacheDirectory.map(File::getPath))
        property(
            "storage.players.path",
            layout.buildDirectory
                .dir("empty/data/saves")
                .get()
                .asFile.path + "/",
        )
        property(
            "web.server.pages",
            layout.buildDirectory
                .dir("site")
                .get()
                .asFile.path,
        )
        property(
            "web.site.worlds",
            layout.buildDirectory
                .file("configuration/worlds.json")
                .get()
                .asFile.path,
        )
        property("web.site.version", serverVersion)
        property("web.site.assets", "true")
        if (assetsInput.isPresent) {
            property("web.site.assets.source", repository.file(assetsInput.get()).asFile.path)
        }
    }

val siteSources = project(":web:site").extensions.getByType<SourceSetContainer>()
val generateSite =
    tasks.register<JavaExec>("generateSite") {
        dependsOn(":web:site:classes", stageData, stateDirectories, siteProperties)
        classpath = siteSources.getByName("main").runtimeClasspath
        mainClass.set("world.gregs.voidps.web.site.Site")
        workingDir(repositoryFile)
        systemProperty(
            "void.site.properties",
            siteProperties
                .get()
                .destinationFile
                .get()
                .asFile.path,
        )
        inputs.file(siteProperties.flatMap { it.destinationFile })
        inputs.files(writeWorlds)
        inputs.files(stageData)
        inputs.dir(repository.dir("docs"))
        inputs.dir(repository.dir("web/site/src/main/resources/static"))
        inputs.file(cacheDirectory.map { it.resolve("main_file_cache.dat2") })
        inputs.files(
            cacheDirectory.map { directory ->
                directory.listFiles().orEmpty().filter {
                    it.isFile && (it.name == "main_file_cache.dat2" || it.name.startsWith("main_file_cache.idx"))
                }
            },
        )
        if (assetsInput.isPresent) {
            inputs.dir(repository.dir(assetsInput.get()))
        }
        outputs.dir(layout.buildDirectory.dir("site"))
    }

distributions {
    create("browser") {
        distributionBaseName.set("void-browser")
        contents {
            duplicatesStrategy = DuplicatesStrategy.FAIL
            from(serverJar) { rename { "void-server.jar" } }
            from(repository.file("LICENSE"))
            from("src/main/resources")
            from(stageData)
            from(stateDirectories) { into("data") }
            from(runtimeProperties)
            from(generateSite) { into("pages") }
            from(buildClient.flatMap { it.outputDirectory }) {
                include("void-client.js")
                into("data")
            }
            from(buildClient.flatMap { it.outputDirectory }) {
                include("client.properties")
            }
            from("src/main/scripts") {
                filter<ReplaceTokens>("tokens" to mapOf("serverJar" to "void-server.jar"))
                filePermissions { unix("rwxr-xr-x") }
            }
        }
    }
}

// Browser delivery is explicit: a normal server build must not require the client source or Deno.
tasks.named("assemble") { setDependsOn(emptyList<Any>()) }
listOf("distZip", "distTar", "installDist").forEach { tasks.named(it) { enabled = false } }

val installBrowser = tasks.named<Sync>("installBrowserDist")
val browserInstanceDirectory =
    browserDirectory(
        browserOption("browserRunDir", "VOID_BROWSER_RUN_DIR", repository.dir("instances/browser").asFile.path),
    )
val prepareInstance =
    tasks.register<BrowserInstance>("prepareInstance") {
        group = "application"
        description = "Update only an owned durable instance, preserving its configuration and runtime data."
        dependsOn(installBrowser)
        repositoryDirectory.set(repository)
        distributionDirectory.set(layout.dir(installBrowser.map { it.destinationDir }))
        instanceDirectory.set(layout.dir(browserInstanceDirectory))
    }
tasks.register<Exec>("runBrowser") {
    group = "application"
    description = "Build and run the complete browser distribution; its cache downloads on first start."
    dependsOn(prepareInstance)
    workingDir(browserInstanceDirectory.get())
    if (System.getProperty("os.name").startsWith("Windows")) {
        commandLine("cmd", "/c", "run-browser.bat")
    } else {
        commandLine("sh", "run-browser")
    }
}

tasks.register<Exec>("checkBrowser") {
    group = "verification"
    description = "Check the actual browser package with isolated synthetic gameplay and durable-state assertions."
    dependsOn(installBrowser)
    workingDir(repositoryFile)
    commandLine(
        "deno",
        "run",
        "-A",
        "--config",
        "external/teavm-client/tools/e2e/deno.json",
        "web/browser/src/test/browser_test.ts",
    )
    environment("VOID_BROWSER_DISTRIBUTION", installBrowser.get().destinationDir.absolutePath)
    environment("VOID_BROWSER_CACHE", cacheDirectory.get().absolutePath)
    environment(
        "VOID_BROWSER_REPORT_DIR",
        providers
            .environmentVariable("VOID_BROWSER_REPORT_DIR")
            .orElse(
                layout.buildDirectory
                    .dir("reports/browser")
                    .get()
                    .asFile.absolutePath,
            ).get(),
    )
}
