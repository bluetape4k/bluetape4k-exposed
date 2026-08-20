/**
 * 공통 production ABI aggregate가 비교할 publication, baseline, actual 집합을 검증합니다.
 *
 * 이 helper는 파일이나 Gradle task를 직접 실행하지 않는 결정적 순수 검증만 담당하며,
 * publication 파생과 KGP task 실행은 root build script가 소유합니다.
 */
data class ProductionAbiInventoryResult(
    val expectedProjects: Set<String>,
    val baselineProjects: Set<String>,
    val actualProjects: Set<String>,
) {
    val missingBaselines: Set<String> = expectedProjects - baselineProjects
    val orphanBaselines: Set<String> = baselineProjects - expectedProjects
    val missingActuals: Set<String> = expectedProjects - actualProjects
    val orphanActuals: Set<String> = actualProjects - expectedProjects

    val isValid: Boolean
        get() = missingBaselines.isEmpty() &&
            orphanBaselines.isEmpty() &&
            missingActuals.isEmpty() &&
            orphanActuals.isEmpty()

    /** 검증 실패를 성공으로 삼지 않도록 상세 차이를 포함해 중단합니다. */
    fun requireValid() {
        check(isValid) {
            buildString {
                appendLine("Production ABI inventory is incomplete")
                appendDifference("missing baselines", missingBaselines)
                appendDifference("orphan baselines", orphanBaselines)
                appendDifference("missing actual dumps", missingActuals)
                appendDifference("orphan actual dumps", orphanActuals)
            }.trimEnd()
        }
    }

    private fun StringBuilder.appendDifference(label: String, values: Set<String>) {
        if (values.isNotEmpty()) {
            appendLine("$label: ${values.sorted().joinToString(", ")}")
        }
    }
}

/**
 * expected publication이 비어 있는 경우를 포함해 inventory를 fail-closed로 준비합니다.
 */
fun validateProductionAbiInventory(
    expectedProjects: Set<String>,
    baselineProjects: Set<String>,
    actualProjects: Set<String>,
): ProductionAbiInventoryResult {
    require(expectedProjects.isNotEmpty()) { "Published ABI inventory must not be empty" }
    return ProductionAbiInventoryResult(
        expectedProjects = expectedProjects,
        baselineProjects = baselineProjects,
        actualProjects = actualProjects,
    )
}
