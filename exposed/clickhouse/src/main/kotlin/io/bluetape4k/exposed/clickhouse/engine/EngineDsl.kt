package io.bluetape4k.exposed.clickhouse.engine

import org.jetbrains.exposed.v1.core.Expression

/**
 * [MergeTree] DSL 빌더입니다.
 */
class MergeTreeBuilder {
    private var orderByExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var partitionByExpression: ClickHouseEngineExpression? = null
    private var primaryKeyExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var sampleByExpression: ClickHouseEngineExpression? = null
    private val settings: MutableList<ClickHouseSetting> = mutableListOf()

    /** 타입이 지정된 Exposed 표현식으로 `ORDER BY`를 설정합니다. 표현식이 하나 이상 필요합니다. */
    fun orderBy(vararg expressions: Expression<*>) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** 엄격한 비안전 조각 검증 후 원시 ClickHouse SQL로 `ORDER BY`를 설정합니다. */
    fun unsafeRawOrderBy(vararg expressions: String) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** 선택적 `PARTITION BY` 표현식을 설정합니다. */
    fun partitionBy(expression: Expression<*>) {
        partitionByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** 엄격한 비안전 조각 검증 후 원시 `PARTITION BY` SQL을 설정합니다. */
    fun unsafeRawPartitionBy(expression: String) {
        partitionByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** 선택적 `PRIMARY KEY` 표현식을 설정합니다. 생략하면 `ORDER BY`를 사용합니다. */
    fun primaryKey(vararg expressions: Expression<*>) {
        primaryKeyExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** 엄격한 비안전 조각 검증 후 원시 `PRIMARY KEY` SQL을 설정합니다. */
    fun unsafeRawPrimaryKey(vararg expressions: String) {
        primaryKeyExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** 선택적 `SAMPLE BY` 표현식을 설정합니다. */
    fun sampleBy(expression: Expression<*>) {
        sampleByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** 엄격한 비안전 조각 검증 후 원시 `SAMPLE BY` SQL을 설정합니다. */
    fun unsafeRawSampleBy(expression: String) {
        sampleByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** 타입이 지정되고 검증된 ClickHouse 엔진 설정을 추가합니다. */
    fun settings(vararg values: ClickHouseSetting) {
        settings.addAll(values)
    }

    fun setting(name: String, value: Int) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Long) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Double) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Boolean) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: String) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Int) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Long) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Double) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Boolean) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: String) = settings(ClickHouseSetting.of(name, value))

    /** 엄격한 비안전 조각 검증 후 원시 ClickHouse 엔진 설정 값을 추가합니다. */
    fun unsafeRawSetting(name: String, value: String) {
        settings(ClickHouseSetting.unsafeRaw(name, value))
    }

    internal fun build(): MergeTree = MergeTree(
        orderBy = orderByExpressions,
        partitionBy = partitionByExpression,
        primaryKeyColumns = primaryKeyExpressions,
        sampleBy = sampleByExpression,
        settings = settings.toList(),
    )
}

/**
 * [MergeTree] 엔진을 생성합니다.
 *
 * ```kotlin
 * val engine = mergeTree {
 *     orderBy(Events.eventDate, Events.userId)
 *     partitionBy(Events.eventMonth)
 *     setting("index_granularity", 8192)
 * }
 * ```
 */
fun mergeTree(block: MergeTreeBuilder.() -> Unit): MergeTree =
    MergeTreeBuilder().apply(block).build()

/**
 * [ReplacingMergeTree] DSL 빌더입니다.
 */
class ReplacingMergeTreeBuilder {
    private var orderByExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var versionExpression: ClickHouseEngineExpression? = null
    private var partitionByExpression: ClickHouseEngineExpression? = null
    private val settings: MutableList<ClickHouseSetting> = mutableListOf()

    /** 타입이 지정된 Exposed 표현식으로 `ORDER BY`를 설정합니다. 표현식이 하나 이상 필요합니다. */
    fun orderBy(vararg expressions: Expression<*>) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** 엄격한 비안전 조각 검증 후 원시 ClickHouse SQL로 `ORDER BY`를 설정합니다. */
    fun unsafeRawOrderBy(vararg expressions: String) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** 최신 중복 행을 유지하는 데 사용할 선택적 버전 컬럼 또는 표현식을 설정합니다. */
    fun versionColumn(expression: Expression<*>) {
        versionExpression = ClickHouseEngineExpression.from(expression)
    }

    /** 엄격한 비안전 조각 검증 후 원시 버전 컬럼 SQL을 설정합니다. */
    fun unsafeRawVersionColumn(expression: String) {
        versionExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** 선택적 `PARTITION BY` 표현식을 설정합니다. */
    fun partitionBy(expression: Expression<*>) {
        partitionByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** 엄격한 비안전 조각 검증 후 원시 `PARTITION BY` SQL을 설정합니다. */
    fun unsafeRawPartitionBy(expression: String) {
        partitionByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** 타입이 지정되고 검증된 ClickHouse 엔진 설정을 추가합니다. */
    fun settings(vararg values: ClickHouseSetting) {
        settings.addAll(values)
    }

    fun setting(name: String, value: Int) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Long) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Double) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Boolean) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: String) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Int) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Long) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Double) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Boolean) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: String) = settings(ClickHouseSetting.of(name, value))

    /** 엄격한 비안전 조각 검증 후 원시 ClickHouse 엔진 설정 값을 추가합니다. */
    fun unsafeRawSetting(name: String, value: String) {
        settings(ClickHouseSetting.unsafeRaw(name, value))
    }

    internal fun build(): ReplacingMergeTree = ReplacingMergeTree(
        orderBy = orderByExpressions,
        versionColumn = versionExpression,
        partitionBy = partitionByExpression,
        settings = settings.toList(),
    )
}

/**
 * [ReplacingMergeTree] 엔진을 생성합니다.
 */
fun replacingMergeTree(block: ReplacingMergeTreeBuilder.() -> Unit): ReplacingMergeTree =
    ReplacingMergeTreeBuilder().apply(block).build()

/**
 * [SummingMergeTree] DSL 빌더입니다.
 */
class SummingMergeTreeBuilder {
    private var orderByExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var sumColumnExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var partitionByExpression: ClickHouseEngineExpression? = null
    private val settings: MutableList<ClickHouseSetting> = mutableListOf()

    /** 타입이 지정된 Exposed 표현식으로 `ORDER BY`를 설정합니다. 표현식이 하나 이상 필요합니다. */
    fun orderBy(vararg expressions: Expression<*>) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** 엄격한 비안전 조각 검증 후 원시 ClickHouse SQL로 `ORDER BY`를 설정합니다. */
    fun unsafeRawOrderBy(vararg expressions: String) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** 합산할 컬럼을 설정합니다. 생략하면 모든 숫자 컬럼을 사용합니다. */
    fun sumColumns(vararg expressions: Expression<*>) {
        sumColumnExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** 엄격한 비안전 조각 검증 후 원시 합산 컬럼 SQL을 설정합니다. */
    fun unsafeRawSumColumns(vararg expressions: String) {
        sumColumnExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** 선택적 `PARTITION BY` 표현식을 설정합니다. */
    fun partitionBy(expression: Expression<*>) {
        partitionByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** 엄격한 비안전 조각 검증 후 원시 `PARTITION BY` SQL을 설정합니다. */
    fun unsafeRawPartitionBy(expression: String) {
        partitionByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** 타입이 지정되고 검증된 ClickHouse 엔진 설정을 추가합니다. */
    fun settings(vararg values: ClickHouseSetting) {
        settings.addAll(values)
    }

    fun setting(name: String, value: Int) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Long) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Double) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Boolean) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: String) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Int) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Long) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Double) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Boolean) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: String) = settings(ClickHouseSetting.of(name, value))

    /** 엄격한 비안전 조각 검증 후 원시 ClickHouse 엔진 설정 값을 추가합니다. */
    fun unsafeRawSetting(name: String, value: String) {
        settings(ClickHouseSetting.unsafeRaw(name, value))
    }

    internal fun build(): SummingMergeTree = SummingMergeTree(
        orderBy = orderByExpressions,
        sumColumns = sumColumnExpressions,
        partitionBy = partitionByExpression,
        settings = settings.toList(),
    )
}

/**
 * [SummingMergeTree] 엔진을 생성합니다.
 */
fun summingMergeTree(block: SummingMergeTreeBuilder.() -> Unit): SummingMergeTree =
    SummingMergeTreeBuilder().apply(block).build()

/**
 * [AggregatingMergeTree] DSL 빌더입니다.
 */
class AggregatingMergeTreeBuilder {
    private var orderByExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var partitionByExpression: ClickHouseEngineExpression? = null
    private val settings: MutableList<ClickHouseSetting> = mutableListOf()

    /** 타입이 지정된 Exposed 표현식으로 `ORDER BY`를 설정합니다. 표현식이 하나 이상 필요합니다. */
    fun orderBy(vararg expressions: Expression<*>) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** 엄격한 비안전 조각 검증 후 원시 ClickHouse SQL로 `ORDER BY`를 설정합니다. */
    fun unsafeRawOrderBy(vararg expressions: String) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** 선택적 `PARTITION BY` 표현식을 설정합니다. */
    fun partitionBy(expression: Expression<*>) {
        partitionByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** 엄격한 비안전 조각 검증 후 원시 `PARTITION BY` SQL을 설정합니다. */
    fun unsafeRawPartitionBy(expression: String) {
        partitionByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** 타입이 지정되고 검증된 ClickHouse 엔진 설정을 추가합니다. */
    fun settings(vararg values: ClickHouseSetting) {
        settings.addAll(values)
    }

    fun setting(name: String, value: Int) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Long) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Double) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: Boolean) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: String, value: String) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Int) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Long) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Double) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: Boolean) = settings(ClickHouseSetting.of(name, value))
    fun setting(name: ClickHouseSettingName, value: String) = settings(ClickHouseSetting.of(name, value))

    /** 엄격한 비안전 조각 검증 후 원시 ClickHouse 엔진 설정 값을 추가합니다. */
    fun unsafeRawSetting(name: String, value: String) {
        settings(ClickHouseSetting.unsafeRaw(name, value))
    }

    internal fun build(): AggregatingMergeTree = AggregatingMergeTree(
        orderBy = orderByExpressions,
        partitionBy = partitionByExpression,
        settings = settings.toList(),
    )
}

/**
 * [AggregatingMergeTree] 엔진을 생성합니다.
 */
fun aggregatingMergeTree(block: AggregatingMergeTreeBuilder.() -> Unit): AggregatingMergeTree =
    AggregatingMergeTreeBuilder().apply(block).build()
