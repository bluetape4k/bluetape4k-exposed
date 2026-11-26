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

    @Test
    fun `dependency only module accepts present empty baseline and actual dump`() {
        val result = validateProductionAbiInventory(
            expectedProjects = setOf("alpha", "bluetape4k-exposed-r2dbc-dao"),
            baselineProjects = setOf("alpha", "bluetape4k-exposed-r2dbc-dao"),
            actualProjects = setOf("alpha", "bluetape4k-exposed-r2dbc-dao"),
            emptyBaselineProjects = setOf("bluetape4k-exposed-r2dbc-dao"),
            emptyActualDumpProjects = setOf("bluetape4k-exposed-r2dbc-dao"),
            dependencyOnlyModules = setOf("bluetape4k-exposed-r2dbc-dao"),
        )

        result.requireValid()
        check(result.emptyBaselineProjects == setOf("bluetape4k-exposed-r2dbc-dao"))
        check(result.emptyActualDumpProjects == setOf("bluetape4k-exposed-r2dbc-dao"))
    }

    @Test
    fun `dependency only module cannot have a non empty baseline or actual dump`() {
        val dependencyOnlyModules = setOf("bluetape4k-exposed-r2dbc-dao")

        val nonEmptyBaseline = validateProductionAbiInventory(
            expectedProjects = dependencyOnlyModules,
            baselineProjects = dependencyOnlyModules,
            actualProjects = dependencyOnlyModules,
            emptyActualDumpProjects = dependencyOnlyModules,
            dependencyOnlyModules = dependencyOnlyModules,
        )
        expectFailure<IllegalStateException> { nonEmptyBaseline.requireValid() }

        val nonEmptyActualDump = validateProductionAbiInventory(
            expectedProjects = dependencyOnlyModules,
            baselineProjects = dependencyOnlyModules,
            actualProjects = dependencyOnlyModules,
            emptyBaselineProjects = dependencyOnlyModules,
            dependencyOnlyModules = dependencyOnlyModules,
        )
        expectFailure<IllegalStateException> { nonEmptyActualDump.requireValid() }
    }

    @Test
    fun `empty baseline and actual dump outside dependency only allowlist fail`() {
        val result = validateProductionAbiInventory(
            expectedProjects = setOf("alpha"),
            baselineProjects = setOf("alpha"),
            actualProjects = setOf("alpha"),
            emptyBaselineProjects = setOf("alpha"),
            emptyActualDumpProjects = setOf("alpha"),
            dependencyOnlyModules = emptySet(),
        )

        expectFailure<IllegalStateException> { result.requireValid() }
    }

    @Test
    fun `dependency only allowlist must be part of the publication inventory`() {
        expectFailure<IllegalArgumentException> {
            validateProductionAbiInventory(
                expectedProjects = setOf("alpha"),
                baselineProjects = setOf("alpha"),
                actualProjects = setOf("alpha"),
                dependencyOnlyModules = setOf("orphan"),
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
