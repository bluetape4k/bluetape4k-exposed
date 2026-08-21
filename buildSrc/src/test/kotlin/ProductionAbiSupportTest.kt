import kotlin.test.Test

class ProductionAbiSupportTest {
    @Test
    fun `empty publication inventory fails closed`() {
        expectFailure<IllegalArgumentException> {
            validateProductionAbiInventory(
                expectedProjects = emptySet(),
                baselineProjects = emptySet(),
                actualProjects = emptySet(),
            )
        }
    }

    @Test
    fun `missing and orphan baseline or actual entries are reported`() {
        val result = validateProductionAbiInventory(
            expectedProjects = setOf("alpha", "beta"),
            baselineProjects = setOf("alpha", "orphan-baseline"),
            actualProjects = setOf("alpha", "orphan-actual"),
        )

        check(result.missingBaselines == setOf("beta"))
        check(result.orphanBaselines == setOf("orphan-baseline"))
        check(result.missingActuals == setOf("beta"))
        check(result.orphanActuals == setOf("orphan-actual"))
        expectFailure<IllegalStateException> { result.requireValid() }
    }

    @Test
    fun `complete inventory is valid`() {
        val result = validateProductionAbiInventory(
            expectedProjects = setOf("alpha", "beta"),
            baselineProjects = setOf("alpha", "beta"),
            actualProjects = setOf("alpha", "beta"),
        )

        result.requireValid()
        check(result.missingBaselines.isEmpty())
        check(result.orphanBaselines.isEmpty())
        check(result.missingActuals.isEmpty())
        check(result.orphanActuals.isEmpty())
    }

    @Test
    fun `empty baseline is never treated as valid`() {
        val result = validateProductionAbiInventory(
            expectedProjects = setOf("alpha"),
            baselineProjects = setOf("alpha"),
            actualProjects = setOf("alpha"),
            emptyBaselineProjects = setOf("alpha"),
        )

        check(result.emptyBaselineProjects == setOf("alpha"))
        expectFailure<IllegalStateException> { result.requireValid() }
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
