import org.gradle.api.GradleException
import java.io.File
import java.security.MessageDigest
import java.util.Properties

internal class TeaVmClientInputs(
    private val repositoryDirectory: File,
    private val sourceDirectory: File,
    private val expectedRevision: String,
    private val teaVmVersion: String,
    private val denoVersion: String,
    private val command: (File, List<String>) -> String,
) {
    fun validate() {
        requireInput(expectedRevision.matches(Regex("[0-9a-f]{40}")), "client.revision must be a full Git revision.")
        requireInput(teaVmVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")), "teavm.version must name a stable release.")
        requireInput(denoVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")), "deno.version must name a stable release.")
        val repository = repositoryDirectory.canonicalFile
        val source = sourceDirectory.canonicalFile
        requireInput(source.resolve(".git").exists(), "Initialize the client with git submodule update --init external/teavm-client.")
        requireInput(
            source != repository && source.toPath().startsWith(repository.toPath()),
            "The client source must belong to this Void checkout.",
        )
        requireInput(
            File(git(repository, "rev-parse", "--show-toplevel")).canonicalFile == repository,
            "repositoryDirectory must be the Void Git top-level directory.",
        )
        requireInput(
            File(git(source, "rev-parse", "--show-toplevel")).canonicalFile == source,
            "The client source is not an initialized Git worktree.",
        )
        val owner = git(source, "rev-parse", "--show-superproject-working-tree")
        requireInput(owner.isNotBlank() && File(owner).canonicalFile == repository, "The client must be a submodule of this Void checkout.")
        val relative = repository.toPath().relativize(source.toPath()).joinToString("/")
        requireInput(
            git(repository, "ls-files", "--stage", "--", relative) == "160000 $expectedRevision 0\t$relative",
            "The client Gitlink does not match client.revision.",
        )
        requireInput(
            git(source, "rev-parse", "HEAD") == expectedRevision,
            "The client HEAD does not match client.revision. Update the submodule.",
        )
        requireInput(
            git(source, "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=none").isBlank(),
            "The client has tracked changes or untracked files. Use a clean source checkout.",
        )
        val wrapper = properties(source.resolve("gradle/wrapper/gradle-wrapper.properties"))
        requireInput(
            wrapper.getProperty("distributionUrl") == "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip",
            "The client must use its official Gradle $GRADLE_VERSION wrapper.",
        )
        requireInput(
            properties(source.resolve("gradle.properties")).getProperty("teavmVersion") == teaVmVersion,
            "The client teavmVersion does not match teavm.version.",
        )
        requireHydrated(source, "gradle/wrapper/gradle-wrapper.jar")
        requireInput(source.resolve(wrapperName()).isFile, "The client Gradle wrapper script is missing.")
        val deno = command(repository, listOf("deno", "--version")).lineSequence().firstOrNull().orEmpty()
        requireInput(
            deno == "deno $denoVersion" || deno.startsWith("deno $denoVersion "),
            "Deno $denoVersion is required. Install the pinned release before building the client.",
        )
    }

    fun buildCommand(): List<String> = listOf(
        sourceDirectory.resolve(wrapperName()).absolutePath,
        "--no-daemon",
        "--console=plain",
        "--no-configuration-cache",
        "--no-build-cache",
        "--rerun-tasks",
        "-PnativeDesktop=false",
        "-PjavaDesktop=false",
        "-PteavmVersion=$teaVmVersion",
        ":client-web:packageUpstreamClient",
    )

    fun manifest(script: File): String = listOf(
        "client.revision=$expectedRevision",
        "client.sha256=${sha256(script)}",
        "deno.version=$denoVersion",
        "gradle.version=$GRADLE_VERSION",
        "teavm.version=$teaVmVersion",
    ).joinToString("\n", postfix = "\n")

    private fun requireHydrated(
        source: File,
        path: String,
    ) {
        val pointer =
            Regex("version https://git-lfs.github.com/spec/v1\\noid sha256:([0-9a-f]{64})\\nsize ([0-9]+)")
                .matchEntire(git(source, "show", "HEAD:$path"))
                ?: throw GradleException("TeaVM client: the pinned $path is not a valid Git LFS pointer.")
        val file = source.resolve(path)
        requireInput(
            file.isFile && file.length() == pointer.groupValues[2].toLong() && sha256(file) == pointer.groupValues[1],
            "Hydrate $path with git -C external/teavm-client lfs pull before building.",
        )
    }

    private fun git(
        directory: File,
        vararg arguments: String,
    ): String = command(directory, listOf("git", *arguments)).trimEnd('\r', '\n')

    private fun properties(file: File): Properties {
        requireInput(file.isFile, "Required client file is missing: ${file.name}.")
        return Properties().apply { file.inputStream().use(::load) }
    }

    private fun wrapperName(): String = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"

    private fun requireInput(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) {
            throw GradleException("TeaVM client: $message")
        }
    }

    companion object {
        const val GRADLE_VERSION = "9.5.0"

        private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }
    }
}
