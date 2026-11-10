import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import java.util.Properties

internal class TeaVmClientInputsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var repository: File
    private lateinit var source: File
    private lateinit var replies: MutableMap<List<String>, String>
    private lateinit var topLevels: MutableMap<File, String>
    private val calls = mutableListOf<Pair<File, List<String>>>()

    @BeforeEach
    fun setup() {
        repository = directory.toFile().canonicalFile
        source = repository.resolve("external/teavm-client").apply { mkdirs() }
        source.resolve(".git").writeText("gitdir: ../../.git/modules/teavm-client\n")
        source.resolve("gradlew").writeText("unused wrapper")
        source.resolve("gradlew.bat").writeText("unused wrapper")
        source.resolve("gradle.properties").writeText("teavmVersion=0.16.0\n")
        source.resolve("gradle/wrapper").mkdirs()
        source
            .resolve(
                "gradle/wrapper/gradle-wrapper.properties",
            ).writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.0-bin.zip\n")
        source.resolve("gradle/wrapper/gradle-wrapper.jar").writeText("abc")
        topLevels = mutableMapOf(repository to repository.path, source to source.path)
        replies =
            mutableMapOf(
                listOf("git", "rev-parse", "--show-superproject-working-tree") to repository.path,
                listOf("git", "ls-files", "--stage", "--", "external/teavm-client") to "160000 $REVISION 0\texternal/teavm-client",
                listOf("git", "rev-parse", "HEAD") to REVISION,
                listOf("git", "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=none") to "",
                listOf("git", "show", "HEAD:gradle/wrapper/gradle-wrapper.jar") to
                    "version https://git-lfs.github.com/spec/v1\noid sha256:$ABC_SHA256\nsize 3\n",
                listOf("deno", "--version") to "deno 2.9.7 (stable, release, x86_64-unknown-linux-gnu)\nv8 example\ntypescript example\n",
            )
        calls.clear()
    }

    private fun inputs(
        revision: String = REVISION,
        teaVm: String = "0.16.0",
        deno: String = "2.9.7",
    ) = TeaVmClientInputs(repository, source, revision, teaVm, deno, ::toolOutput)

    private fun toolOutput(workingDirectory: File, arguments: List<String>): String {
        calls.add(workingDirectory to arguments)
        return if (arguments == listOf("git", "rev-parse", "--show-toplevel")) {
            topLevels.getValue(workingDirectory)
        } else {
            replies[arguments] ?: error("Unexpected command: $arguments")
        }
    }

    private fun task(build: (File) -> Int): TeaVmClientBuild {
        val operations = mockk<ExecOperations>()
        every { operations.exec(any<Action<ExecSpec>>()) } answers {
            val spec = mockk<ExecSpec>()
            val workingDirectory = slot<File>()
            val arguments = slot<List<String>>()
            val output = slot<OutputStream>()
            every { spec.workingDir(capture(workingDirectory)) } returns spec
            every { spec.commandLine(capture(arguments)) } returns spec
            every { spec.setStandardOutput(capture(output)) } returns spec
            every { spec.setErrorOutput(any()) } returns spec
            every { spec.setIgnoreExitValue(any()) } returns spec
            firstArg<Action<ExecSpec>>().execute(spec)
            val exit = if (arguments.captured.first() == "git" || arguments.captured.first() == "deno") {
                output.captured.write(toolOutput(workingDirectory.captured, arguments.captured).toByteArray())
                0
            } else {
                build(workingDirectory.captured)
            }
            val result = mockk<ExecResult>()
            every { result.exitValue } returns exit
            every { result.assertNormalExitValue() } answers {
                if (exit != 0) {
                    throw GradleException("Fixture child exited with code $exit")
                }
                result
            }
            result
        }
        val project = ProjectBuilder.builder().withProjectDir(repository).build()
        return project.tasks.register("client", TeaVmClientBuild::class.java, operations).get().apply {
            repositoryDirectory.set(repository)
            sourceDirectory.set(source)
            expectedRevision.set(REVISION)
            teaVmVersion.set("0.16.0")
            denoVersion.set("2.9.7")
            outputDirectory.set(repository.resolve("staged"))
        }
    }

    private fun stagedFiles(): Pair<File, File> {
        val output = repository.resolve("staged").apply { mkdir() }
        return output.resolve("void-client.js").apply { writeText("old client") } to
            output.resolve("client.properties").apply { writeText("old manifest") }
    }

    private fun producedScript(bytes: String = "abc"): File = source.resolve("client-web/build/upstream/void-client.js").apply {
        parentFile.mkdirs()
        writeText(bytes)
    }

    private fun rejection(
        message: String,
        validate: () -> Unit = { inputs().validate() },
    ) {
        val failure = assertThrows(GradleException::class.java, validate)
        assertTrue(failure.message.orEmpty().contains(message), failure.message)
        assertTrue(calls.none { (_, arguments) -> arguments.first().endsWith("gradlew") || arguments.first().endsWith("gradlew.bat") })
    }

    @Test
    fun `Clean initialized pinned source and exact tools are accepted`() {
        inputs().validate()

        assertEquals(listOf("deno", "--version"), calls.last().second)
        assertEquals(repository, calls.last().first)
        assertTrue(calls.contains(source to listOf("git", "show", "HEAD:gradle/wrapper/gradle-wrapper.jar")))
    }

    @Test
    fun `Missing submodule is rejected before commands run`() {
        source.resolve(".git").delete()

        rejection("git submodule update --init")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `Short revision is rejected before commands run`() {
        rejection("full Git revision") { inputs(revision = "d949b47").validate() }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `Development TeaVM and Deno versions are rejected`() {
        rejection("stable release") { inputs(teaVm = "0.17.0-SNAPSHOT").validate() }
        rejection("stable release") { inputs(deno = "2.9.7-canary").validate() }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `Void checkout cannot be mistaken for initialized client`() {
        val inputs = TeaVmClientInputs(repository, repository, REVISION, "0.16.0", "2.9.7") { _, _ -> error("No command expected") }
        repository.resolve(".git").mkdir()

        rejection("must belong to this Void checkout") { inputs.validate() }
    }

    @Test
    fun `Independent client checkout is rejected`() {
        replies[listOf("git", "rev-parse", "--show-superproject-working-tree")] = ""

        rejection("must be a submodule")
    }

    @Test
    fun `Parent directory must be the actual Void Git top-level`() {
        topLevels[repository] = repository.parent

        rejection("Void Git top-level")
    }

    @Test
    fun `Client directory must be its own Git top-level`() {
        topLevels[source] = repository.path

        rejection("not an initialized Git worktree")
    }

    @Test
    fun `Client owned by another checkout is rejected`() {
        replies[listOf("git", "rev-parse", "--show-superproject-working-tree")] = directory.resolve("another-void").toString()

        rejection("must be a submodule")
    }

    @Test
    fun `Parent Gitlink must match the explicit revision`() {
        replies[listOf("git", "ls-files", "--stage", "--", "external/teavm-client")] = "160000 ${"a".repeat(40)} 0\texternal/teavm-client"

        rejection("Gitlink does not match")
    }

    @Test
    fun `Client HEAD must match the explicit revision`() {
        replies[listOf("git", "rev-parse", "HEAD")] = "a".repeat(40)

        rejection("HEAD does not match")
    }

    @Test
    fun `Tracked source changes are rejected`() {
        replies[listOf("git", "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=none")] =
            " M client-web/web/void-client-core.js\n"

        rejection("tracked changes or untracked files")
    }

    @Test
    fun `Untracked source is rejected`() {
        replies[listOf("git", "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=none")] =
            "?? client-web/src/main/java/Unexpected.java\n"

        rejection("tracked changes or untracked files")
    }

    @Test
    fun `Alternate wrapper distribution is rejected`() {
        source.resolve("gradle/wrapper/gradle-wrapper.properties").writeText("distributionUrl=https\\://example.org/gradle-9.5.0-bin.zip\n")

        rejection("official Gradle 9.5.0 wrapper")
    }

    @Test
    fun `Source TeaVM version must match the declared pin`() {
        source.resolve("gradle.properties").writeText("teavmVersion=0.17.0-SNAPSHOT\n")

        rejection("teavmVersion does not match")
    }

    @Test
    fun `Unhydrated wrapper LFS pointer is rejected`() {
        source
            .resolve(
                "gradle/wrapper/gradle-wrapper.jar",
            ).writeText(replies.getValue(listOf("git", "show", "HEAD:gradle/wrapper/gradle-wrapper.jar")))

        rejection("lfs pull")
    }

    @Test
    fun `Missing wrapper LFS object is rejected`() {
        source.resolve("gradle/wrapper/gradle-wrapper.jar").delete()

        rejection("lfs pull")
    }

    @Test
    fun `Hydrated wrapper with wrong bytes is rejected even at the same size`() {
        source.resolve("gradle/wrapper/gradle-wrapper.jar").writeText("abd")

        rejection("lfs pull")
    }

    @Test
    fun `Malformed pinned LFS pointer is rejected`() {
        replies[listOf("git", "show", "HEAD:gradle/wrapper/gradle-wrapper.jar")] = "not an LFS pointer"

        rejection("not a valid Git LFS pointer")
    }

    @Test
    fun `Different installed Deno release is rejected`() {
        replies[listOf("deno", "--version")] = "deno 2.9.70 (stable, release)\n"

        rejection("Deno 2.9.7 is required")
    }

    @Test
    fun `Child command uses its own wrapper official pin and browser only flags`() {
        val command = inputs().buildCommand()

        assertTrue(File(command.first()).parentFile.canonicalFile == source)
        assertEquals(":client-web:packageUpstreamClient", command.last())
        assertTrue(
            command.containsAll(
                listOf(
                    "-PnativeDesktop=false",
                    "-PjavaDesktop=false",
                    "-PteavmVersion=0.16.0",
                    "--rerun-tasks",
                    "--no-build-cache",
                    "--no-configuration-cache",
                ),
            ),
        )
        assertFalse(command.any { it.contains("mavenLocal") || it.contains("nativeDesktop=true") || it.contains("javaDesktop=true") })
    }

    @Test
    fun `Manifest records actual artifact hash and pinned provenance deterministically`() {
        val script = directory.resolve("void-client.js").toFile().apply { writeText("abc") }
        val text = inputs().manifest(script)
        val manifest = Properties().apply { text.reader().use(::load) }

        assertEquals(REVISION, manifest.getProperty("client.revision"))
        assertEquals(ABC_SHA256, manifest.getProperty("client.sha256"))
        assertEquals("0.16.0", manifest.getProperty("teavm.version"))
        assertEquals("2.9.7", manifest.getProperty("deno.version"))
        assertEquals("9.5.0", manifest.getProperty("gradle.version"))
        assertEquals(text, inputs().manifest(script))
    }

    @Test
    fun `Failed validation removes only owned staged files and never uses an old artifact`() {
        val output = repository.resolve("staged").apply { mkdir() }
        val script = output.resolve("void-client.js").apply { writeText("old client") }
        val manifest = output.resolve("client.properties").apply { writeText("old manifest") }
        val unrelated = output.resolve("keep.txt").apply { writeText("keep") }
        source.resolve(".git").delete()
        val project = ProjectBuilder.builder().withProjectDir(repository).build()
        val task = project.tasks.register("client", TeaVmClientBuild::class.java).get()
        task.repositoryDirectory.set(repository)
        task.sourceDirectory.set(source)
        task.expectedRevision.set(REVISION)
        task.teaVmVersion.set("0.16.0")
        task.denoVersion.set("2.9.7")
        task.outputDirectory.set(output)

        val failure = assertThrows(GradleException::class.java) { task.buildClient() }

        assertTrue(failure.message.orEmpty().contains("git submodule update --init"))
        assertFalse(script.exists())
        assertFalse(manifest.exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun `Successful child stages rebuilt bytes with matching provenance`() {
        val (script, manifest) = stagedFiles()
        producedScript("stale generated client")
        val task = task { workingDirectory ->
            assertEquals(source, workingDirectory)
            assertFalse(source.resolve("client-web/build/upstream/void-client.js").exists())
            producedScript()
            0
        }

        task.buildClient()

        assertEquals("abc", script.readText())
        val provenance = Properties().apply { manifest.inputStream().use(::load) }
        assertEquals(ABC_SHA256, provenance.getProperty("client.sha256"))
        assertEquals(REVISION, provenance.getProperty("client.revision"))
        assertEquals(2, calls.count { (_, arguments) -> arguments == listOf("git", "rev-parse", "HEAD") })
    }

    @Test
    fun `Nonzero child exit fails and removes old staged files`() {
        val (script, manifest) = stagedFiles()
        val task = task { 7 }

        val failure = assertThrows(GradleException::class.java) { task.buildClient() }

        assertTrue(failure.message.orEmpty().contains("exited with code 7"))
        assertFalse(script.exists())
        assertFalse(manifest.exists())
    }

    @Test
    fun `Successful child without an artifact cannot reuse an old generated client`() {
        val (script, manifest) = stagedFiles()
        producedScript("stale generated client")
        val task = task { 0 }

        val failure = assertThrows(GradleException::class.java) { task.buildClient() }

        assertTrue(failure.message.orEmpty().contains("did not produce a non-empty"))
        assertFalse(script.exists())
        assertFalse(manifest.exists())
    }

    @Test
    fun `Empty child artifact fails instead of publishing a script`() {
        val (script, manifest) = stagedFiles()
        val task = task {
            producedScript("")
            0
        }

        val failure = assertThrows(GradleException::class.java) { task.buildClient() }

        assertTrue(failure.message.orEmpty().contains("did not produce a non-empty"))
        assertFalse(script.exists())
        assertFalse(manifest.exists())
    }

    @Test
    fun `Post-build source drift rejects the artifact and clears staging`() {
        val (script, manifest) = stagedFiles()
        val task = task {
            producedScript()
            replies[listOf("git", "rev-parse", "HEAD")] = "a".repeat(40)
            0
        }

        val failure = assertThrows(GradleException::class.java) { task.buildClient() }

        assertTrue(failure.message.orEmpty().contains("HEAD does not match"))
        assertFalse(script.exists())
        assertFalse(manifest.exists())
    }

    companion object {
        private const val REVISION = "d949b478a95f63d6717614af84f79f40beb7c020"
        private const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
