import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContains

class CheckProductionAbiConfigurationCacheTest {
    @Test
    fun `checkProductionAbi stores and reuses configuration cache`() {
        val repositoryRoot = findRepositoryRoot()
        val projectCacheDirectory = createTempDirectory("check-production-abi-project-cache").toFile()
        val projectCacheArguments = listOf("--project-cache-dir", projectCacheDirectory.absolutePath)
        val configurationCacheArguments = listOf(
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            "--console=plain",
            "--rerun-tasks",
            "-Dplatform.random.idempotence.check.rate=0",
        ) + projectCacheArguments + "checkProductionAbi"

        try {
            val warmupRun = runGradle(
                repositoryRoot,
                listOf("--no-configuration-cache", "--console=plain") +
                    projectCacheArguments + "checkProductionAbi",
            )
            assertContains(warmupRun.output, "BUILD SUCCESSFUL")

            val firstRun = runGradle(repositoryRoot, configurationCacheArguments)
            assertContains(firstRun.output, "Configuration cache entry stored")
            assertContains(firstRun.output, "BUILD SUCCESSFUL")
            assertContains(firstRun.output, "Task :checkProductionAbi")
            assertFalse(firstRun.output.contains("Task :checkProductionAbi UP-TO-DATE"))
            assertCompleteProductionAbiReport(repositoryRoot)

            val secondRun = runGradle(repositoryRoot, configurationCacheArguments)
            assertContains(secondRun.output, "Reusing configuration cache")
            assertContains(secondRun.output, "BUILD SUCCESSFUL")
            assertContains(secondRun.output, "Task :checkProductionAbi")
            assertFalse(secondRun.output.contains("Task :checkProductionAbi UP-TO-DATE"))

            val noCacheRun = runGradle(
                repositoryRoot,
                listOf(
                    "--no-configuration-cache",
                    "--configuration-cache-problems=fail",
                    "--console=plain",
                ) + projectCacheArguments + listOf("--rerun-tasks", "checkProductionAbi"),
            )
            assertContains(noCacheRun.output, "BUILD SUCCESSFUL")
            assertContains(noCacheRun.output, "Task :checkProductionAbi")
            assertFalse(noCacheRun.output.contains("Task :checkProductionAbi UP-TO-DATE"))
            assertCompleteProductionAbiReport(repositoryRoot)
        } finally {
            projectCacheDirectory.deleteRecursively()
        }
    }

    private fun runGradle(repositoryRoot: File, arguments: List<String>) =
        GradleRunner.create()
            .withProjectDir(repositoryRoot)
            .withArguments(arguments)
            .build()

    private fun assertCompleteProductionAbiReport(repositoryRoot: File) {
        val report = File(repositoryRoot, "build/abi/reports/production-abi.txt").readText()
        assertContains(report, "modules=45/45")
        assertContains(report, "baselines=45/45")
        assertContains(report, "actualDumps=45/45")
        assertContains(report, "orphanBaselines=0")
        assertContains(report, "orphanActuals=0")
        assertContains(report, "emptyBaselines=0")
        assertEquals(45, report.lineSequence().drop(6).filter(String::isNotBlank).count())
    }

    private fun findRepositoryRoot(): File {
        val start = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(start) { it.parentFile }
            .first { candidate ->
                File(candidate, "settings.gradle.kts").isFile &&
                    File(candidate, "buildSrc").isDirectory
            }
    }
}
