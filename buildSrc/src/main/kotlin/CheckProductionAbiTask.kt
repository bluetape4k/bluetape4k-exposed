import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Gradle script 객체를 캡처하지 않고 production ABI inventory를 검사합니다. */
abstract class CheckProductionAbiTask : DefaultTask() {
    @get:Input
    abstract val expectedProjects: SetProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val actualDumpFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun checkInventory() {
        val baselineApiFiles = baselineFiles.files
            .filter { it.isFile && it.extension == "api" }
        val baselineProjects = baselineApiFiles
            .map { it.name.removeSuffix(".api") }
            .toSet()
        val emptyBaselineProjects = baselineApiFiles
            .filter { it.length() == 0L }
            .map { it.name.removeSuffix(".api") }
            .toSet()
        val actualProjects = actualDumpFiles.files
            .filter { it.isFile && it.length() > 0L }
            .map { it.name.removeSuffix(".api") }
            .toSet()

        val result = validateProductionAbiInventory(
            expectedProjects = expectedProjects.get(),
            baselineProjects = baselineProjects,
            actualProjects = actualProjects,
            emptyBaselineProjects = emptyBaselineProjects,
        )
        result.requireValid()

        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("modules=${result.expectedProjects.size}/${result.expectedProjects.size}")
                    appendLine("baselines=${result.baselineProjects.size}/${result.expectedProjects.size}")
                    appendLine("actualDumps=${result.actualProjects.size}/${result.expectedProjects.size}")
                    appendLine("orphanBaselines=${result.orphanBaselines.size}")
                    appendLine("orphanActuals=${result.orphanActuals.size}")
                    appendLine("emptyBaselines=${result.emptyBaselineProjects.size}")
                    result.expectedProjects.sorted().forEach(::appendLine)
                },
            )
        }
    }
}
