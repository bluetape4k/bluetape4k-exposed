import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Runs the jdbc-tests assertion import guard without capturing a Gradle script object. */
abstract class VerifyBluetapeAssertionImportsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoots: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinCompileSources: ConfigurableFileCollection

    @get:Input
    abstract val projectDirectoryPath: Property<String>

    @TaskAction
    fun verify() {
        val violations = findBluetapeAssertionImportViolations(
            sourceRoots = sourceRoots.files,
            kotlinCompileSources = kotlinCompileSources.files,
            projectDirectory = File(projectDirectoryPath.get()),
        )
        check(violations.isEmpty()) {
            "Raw assertion imports are forbidden in jdbc-tests:\n${violations.joinToString("\n")}"
        }
    }
}
