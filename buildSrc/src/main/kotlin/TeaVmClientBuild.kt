import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

abstract class TeaVmClientBuild
@Inject
constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:Input
    abstract val expectedRevision: Property<String>

    @get:Input
    abstract val teaVmVersion: Property<String>

    @get:Input
    abstract val denoVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        doNotTrackState("Git and tool validation must run before each client build.")
    }

    @TaskAction
    fun buildClient() {
        val output = outputDirectory.get().asFile
        val script = output.resolve("void-client.js")
        val manifest = output.resolve("client.properties")
        Files.deleteIfExists(script.toPath())
        Files.deleteIfExists(manifest.toPath())
        try {
            val source = sourceDirectory.get().asFile
            val inputs =
                TeaVmClientInputs(
                    repositoryDirectory.get().asFile,
                    source,
                    expectedRevision.get(),
                    teaVmVersion.get(),
                    denoVersion.get(),
                    ::command,
                )
            inputs.validate()
            val produced = source.resolve("client-web/build/upstream/void-client.js")
            Files.deleteIfExists(produced.toPath())
            execOperations
                .exec {
                    workingDir(source)
                    commandLine(inputs.buildCommand())
                }.assertNormalExitValue()
            inputs.validate()
            if (!produced.isFile || produced.length() == 0L) {
                throw GradleException("TeaVM client: the build did not produce a non-empty void-client.js.")
            }
            Files.createDirectories(output.toPath())
            Files.copy(produced.toPath(), script.toPath(), StandardCopyOption.REPLACE_EXISTING)
            manifest.writeText(inputs.manifest(script))
        } catch (failure: Exception) {
            Files.deleteIfExists(script.toPath())
            Files.deleteIfExists(manifest.toPath())
            throw failure
        }
    }

    private fun command(
        directory: File,
        arguments: List<String>,
    ): String {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val result =
            try {
                execOperations.exec {
                    workingDir(directory)
                    commandLine(arguments)
                    standardOutput = output
                    errorOutput = errors
                    isIgnoreExitValue = true
                }
            } catch (failure: Exception) {
                throw GradleException(
                    "TeaVM client: cannot execute ${arguments.first()}. Check that the required tool is installed.",
                    failure,
                )
            }
        if (result.exitValue != 0) {
            throw GradleException(
                "TeaVM client: ${arguments.joinToString(" ")} failed with exit code ${result.exitValue}: " +
                    errors.toString(Charsets.UTF_8),
            )
        }
        return output.toString(Charsets.UTF_8)
    }
}
