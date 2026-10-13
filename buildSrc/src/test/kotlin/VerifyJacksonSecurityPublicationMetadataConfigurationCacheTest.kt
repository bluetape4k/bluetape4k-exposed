import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class VerifyJacksonSecurityPublicationMetadataConfigurationCacheTest {
    @Test
    fun `metadata validator succeeds on cold and repeated default configuration cache invocations`() {
        val repositoryRoot = findRepositoryRoot()
        val projectCacheDirectory = createTempDirectory("verify-jackson-metadata-project-cache").toFile()
        val arguments = listOf(
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            "--console=plain",
            "--max-workers=2",
            "--project-cache-dir",
            projectCacheDirectory.absolutePath,
            "verifyJacksonSecurityPublicationMetadata",
        )

        try {
            val firstRun = runGradle(repositoryRoot, arguments)
            assertContains(firstRun.output, "BUILD SUCCESSFUL")
            assertContains(firstRun.output, "Configuration cache entry discarded")
            assertFalse(firstRun.output.contains("Configuration cache entry stored"))
            assertFalse(firstRun.output.contains("Reusing configuration cache"))

            val secondRun = runGradle(repositoryRoot, arguments)
            assertContains(secondRun.output, "BUILD SUCCESSFUL")
            assertContains(secondRun.output, "Configuration cache entry discarded")
            assertFalse(secondRun.output.contains("Configuration cache entry stored"))
            assertFalse(secondRun.output.contains("Reusing configuration cache"))
        } finally {
            projectCacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `metadata validator rejects outdated Jackson BOM versions in module and Maven metadata`() {
        val repositoryRoot = findRepositoryRoot()
        val initScript = Files.createTempFile("verify-jackson-metadata-no-generation", ".gradle").toFile()
        val moduleMetadata = File(
            repositoryRoot,
            "exposed/jackson2/build/publications/BluetapeExposed/module.json",
        )
        val mavenPom = File(
            repositoryRoot,
            "exposed/jackson2/build/publications/BluetapeExposed/pom-default.xml",
        )
        val moduleMetadataOriginal = moduleMetadata.takeIf(File::isFile)?.readText()
        val mavenPomOriginal = mavenPom.takeIf(File::isFile)?.readText()
        initScript.writeText(
            """
            gradle.projectsEvaluated {
                rootProject.tasks.findByName('verifyJacksonSecurityPublicationMetadata')?.setDependsOn([])
            }
            """.trimIndent(),
        )

        try {
            val generationRun = runGradle(
                repositoryRoot,
                listOf("--no-configuration-cache", "--console=plain", "verifyJacksonSecurityPublicationMetadata"),
            )
            assertContains(generationRun.output, "BUILD SUCCESSFUL")

            assertPublicationMetadataVersionIsRejected(
                repositoryRoot = repositoryRoot,
                initScript = initScript,
                file = moduleMetadata,
                versionPattern = Regex(
                    """("group"\s*:\s*"com\.fasterxml\.jackson"\s*,\s*"module"\s*:\s*"jackson-bom"\s*,\s*"version"\s*:\s*\{\s*"requires"\s*:\s*")([^"]+)""",
                ),
                errorMessage = "Gradle metadata jackson-bom versions",
            )
            assertPublicationMetadataVersionIsRejected(
                repositoryRoot = repositoryRoot,
                initScript = initScript,
                file = mavenPom,
                versionPattern = Regex(
                    """(<groupId>com\.fasterxml\.jackson</groupId>\s*<artifactId>jackson-bom</artifactId>\s*<version>)([^<]+)(</version>)""",
                ),
                errorMessage = "POM jackson-bom versions",
            )
        } finally {
            moduleMetadataOriginal?.let(moduleMetadata::writeText)
            mavenPomOriginal?.let(mavenPom::writeText)
            initScript.delete()
        }
    }

    private fun runGradle(repositoryRoot: File, arguments: List<String>) =
        GradleRunner.create()
            .withProjectDir(repositoryRoot)
            .withArguments(arguments)
            .build()

    private fun assertPublicationMetadataVersionIsRejected(
        repositoryRoot: File,
        initScript: File,
        file: File,
        versionPattern: Regex,
        errorMessage: String,
    ) {
        val original = file.readText()
        val versionMatch = versionPattern.find(original)
            ?: error("Expected Jackson BOM version pattern was absent from ${file.relativeTo(repositoryRoot)}")
        val versionRange = versionMatch.groups[2]?.range
            ?: error("Expected a captured Jackson BOM version in ${file.relativeTo(repositoryRoot)}")
        file.writeText(original.replaceRange(versionRange, "0.0.0-invalid"))

        try {
            val failedRun = GradleRunner.create()
                .withProjectDir(repositoryRoot)
                .withArguments(
                    "--no-configuration-cache",
                    "--console=plain",
                    "--init-script",
                    initScript.absolutePath,
                    "verifyJacksonSecurityPublicationMetadata",
                )
                .buildAndFail()
            assertContains(failedRun.output, errorMessage)
            assertContains(failedRun.output, "0.0.0-invalid")
        } finally {
            file.writeText(original)
        }
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
