package io.bluetape4k.timefold.solver.exposed.api.score.buildin

import ai.timefold.solver.core.api.score.SimpleScore
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnTransformer
import org.jetbrains.exposed.v1.core.ColumnWithTransform
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table

/**
 * Timefold Solver의 [SimpleScore]를 저장할 수 있는 Column을 생성합니다.
 *
 * [SimpleScore]는 단일 점수 값을 가지는 가장 기본적인 Score 유형입니다.
 * 데이터베이스에는 Long 형태로 저장됩니다.
 *
 * ```kotlin
 * object PlanningTables : IntIdTable("planning_solution") {
 *     val name = varchar("name", 255)
 *     val score = simpleScore("score")
 * }
 *
 * // 사용 예시
 * val simpleScore = SimpleScore.of(100)
 * PlanningTables.insert {
 *     it[name] = "Test Solution"
 *     it[score] = simpleScore
 * }
 * ```
 *
 * @param name 컬럼 이름
 * @return [SimpleScore] 타입의 컬럼
 *
 * @see SimpleScore
 */
fun Table.simpleScore(name: String): Column<SimpleScore> = registerColumn(name, SimpleScoreColumnType())

/**
 * [SimpleScore]를 위한 Exposed ColumnType 구현체입니다.
 *
 * Kotlin Long 타입과 [SimpleScore] 간의 변환을 처리합니다.
 *
 * ```kotlin
 * val columnType = SimpleScoreColumnType()
 * val score = SimpleScore.of(42)
 * val raw = columnType.notNullValueToDB(score)
 * // raw == 42L
 * ```
 */
class SimpleScoreColumnType: ColumnWithTransform<Long, SimpleScore>(LongColumnType(), SimpleScoreTransformer())

/**
 * [SimpleScore]와 데이터베이스 Long 값 간의 변환을 수행하는 Transformer 클래스입니다.
 *
 * [unwrap] 메서드는 [SimpleScore]를 Long으로 변환하고,
 * [wrap] 메서드는 Long을 [SimpleScore]로 변환합니다.
 *
 * ```kotlin
 * val transformer = SimpleScoreTransformer()
 * val score = SimpleScore.of(100)
 * val raw = transformer.unwrap(score)    // 100L
 * val restored = transformer.wrap(raw)  // SimpleScore.of(100)
 * ```
 */
class SimpleScoreTransformer: ColumnTransformer<Long, SimpleScore> {
    /**
     * [SimpleScore]를 데이터베이스 Long 값으로 변환합니다.
     *
     * ```kotlin
     * val raw = SimpleScoreTransformer().unwrap(SimpleScore.of(77))
     * // raw == 77L
     * ```
     *
     * @param value 변환할 [SimpleScore] 인스턴스
     * @return 점수의 Long 값
     */
    override fun unwrap(value: SimpleScore): Long = value.score()

    /**
     * 데이터베이스 Long 값을 [SimpleScore]로 변환합니다.
     *
     * ```kotlin
     * val score = SimpleScoreTransformer().wrap(77)
     * // score == SimpleScore.of(77)
     * ```
     *
     * @param value 데이터베이스에서 읽은 Long 값
     * @return 생성된 [SimpleScore] 인스턴스
     */
    override fun wrap(value: Long): SimpleScore = SimpleScore.of(value)
}
