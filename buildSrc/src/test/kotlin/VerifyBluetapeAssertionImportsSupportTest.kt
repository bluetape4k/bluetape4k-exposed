import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test

class VerifyBluetapeAssertionImportsSupportTest {
    @Test
    fun `forbidden assertion imports are reported while bluetape assertions are allowed`() {
        val projectDirectory = createTempDirectory("verify-assertions").toRealPath()
        val sourceRoot = projectDirectory.resolve("src/test/kotlin").createDirectories()
        val sourceFile = sourceRoot.resolve("ExampleTest.kt").apply {
            writeText(
                """
                import io.bluetape4k.junit5.assertions.shouldBeEqualTo
                import org.junit.jupiter.api.Assertions.assertTrue
                """.trimIndent(),
            )
        }

        val violations = findBluetapeAssertionImportViolations(
            sourceRoots = listOf(sourceRoot.toFile()),
            kotlinCompileSources = listOf(sourceFile.toFile()),
            projectDirectory = projectDirectory.toFile(),
        )

        check(violations == listOf("src/test/kotlin/ExampleTest.kt:2: import org.junit.jupiter.api.Assertions.assertTrue"))
    }

    @Test
    fun `compile sources outside fixed roots fail closed`() {
        val projectDirectory = createTempDirectory("verify-assertions").toRealPath()
        val sourceRoot = projectDirectory.resolve("src/test/kotlin").createDirectories()
        val outsideSource = projectDirectory.resolve("generated/Generated.kt").apply {
            parent.createDirectories()
            writeText("class Generated")
        }

        expectFailure<IllegalStateException> {
            findBluetapeAssertionImportViolations(
                sourceRoots = listOf(sourceRoot.toFile()),
                kotlinCompileSources = listOf(outsideSource.toFile()),
                projectDirectory = projectDirectory.toFile(),
            )
        }
    }

    @Test
    fun `missing source roots fail closed`() {
        val projectDirectory = createTempDirectory("verify-assertions").toRealPath()
        val missingRoot = projectDirectory.resolve("src/main/kotlin")

        expectFailure<IllegalStateException> {
            findBluetapeAssertionImportViolations(
                sourceRoots = listOf(missingRoot.toFile()),
                kotlinCompileSources = emptyList(),
                projectDirectory = projectDirectory.toFile(),
            )
        }
    }

    @Test
    fun `symlinked source paths are rejected`() {
        val projectDirectory = createTempDirectory("verify-assertions").toRealPath()
        val realRoot = projectDirectory.resolve("real").createDirectories()
        val linkedRoot = projectDirectory.resolve("linked")
        Files.createSymbolicLink(linkedRoot, realRoot)

        expectFailure<IllegalStateException> {
            findBluetapeAssertionImportViolations(
                sourceRoots = listOf(linkedRoot.toFile()),
                kotlinCompileSources = emptyList(),
                projectDirectory = projectDirectory.toFile(),
            )
        }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.simpleName}, got ${error::class.simpleName}" }
            return
        }
        error("Expected ${T::class.simpleName}")
    }
}
