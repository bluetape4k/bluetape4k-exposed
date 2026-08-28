import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

class VerifyBluetapeAssertionImportsConfigurationCacheTest {
    @Test
    fun `verifier runs and reuses configuration cache`() {
        val repositoryRoot = findRepositoryRoot()
        val cacheDirectory = createTempDirectory("verify-assertions-gradle-cache").toFile()
        val arguments = listOf(
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            "--console=plain",
            "--project-cache-dir",
            cacheDirectory.absolutePath,
            ":bluetape4k-exposed-jdbc-tests:verifyBluetapeAssertionImports",
        )

        val firstRun = GradleRunner.create()
            .withProjectDir(repositoryRoot)
            .withArguments(arguments)
            .build()
        assertContains(firstRun.output, "BUILD SUCCESSFUL")

        val secondRun = GradleRunner.create()
            .withProjectDir(repositoryRoot)
            .withArguments(arguments)
            .build()
        assertContains(secondRun.output, "Reusing configuration cache")
        assertContains(secondRun.output, "BUILD SUCCESSFUL")
        cacheDirectory.deleteRecursively()
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
