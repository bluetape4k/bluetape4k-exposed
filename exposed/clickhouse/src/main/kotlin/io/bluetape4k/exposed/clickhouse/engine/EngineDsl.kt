package io.bluetape4k.exposed.clickhouse.engine

import org.jetbrains.exposed.v1.core.Expression

/**
 * DSL builder for [MergeTree].
 */
class MergeTreeBuilder {
    private var orderByExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var partitionByExpression: ClickHouseEngineExpression? = null
    private var primaryKeyExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var sampleByExpression: ClickHouseEngineExpression? = null
    private val settings: MutableList<ClickHouseSetting> = mutableListOf()

    /** Sets `ORDER BY` with typed Exposed expressions. At least one expression is required. */
    fun orderBy(vararg expressions: Expression<*>) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** Sets `ORDER BY` with raw ClickHouse SQL after strict unsafe-fragment validation. */
    fun unsafeRawOrderBy(vararg expressions: String) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** Sets the optional `PARTITION BY` expression. */
    fun partitionBy(expression: Expression<*>) {
        partitionByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** Sets raw `PARTITION BY` SQL after strict unsafe-fragment validation. */
    fun unsafeRawPartitionBy(expression: String) {
        partitionByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** Sets optional `PRIMARY KEY` expressions. Defaults to `ORDER BY` when omitted. */
    fun primaryKey(vararg expressions: Expression<*>) {
        primaryKeyExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** Sets raw `PRIMARY KEY` SQL after strict unsafe-fragment validation. */
    fun unsafeRawPrimaryKey(vararg expressions: String) {
        primaryKeyExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** Sets the optional `SAMPLE BY` expression. */
    fun sampleBy(expression: Expression<*>) {
        sampleByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** Sets raw `SAMPLE BY` SQL after strict unsafe-fragment validation. */
    fun unsafeRawSampleBy(expression: String) {
        sampleByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** Adds typed/validated ClickHouse engine settings. */
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

    /** Adds a raw ClickHouse engine setting value after strict unsafe-fragment validation. */
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
 * Creates a [MergeTree] engine.
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
 * DSL builder for [ReplacingMergeTree].
 */
class ReplacingMergeTreeBuilder {
    private var orderByExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var versionExpression: ClickHouseEngineExpression? = null
    private var partitionByExpression: ClickHouseEngineExpression? = null
    private val settings: MutableList<ClickHouseSetting> = mutableListOf()

    /** Sets `ORDER BY` with typed Exposed expressions. At least one expression is required. */
    fun orderBy(vararg expressions: Expression<*>) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** Sets `ORDER BY` with raw ClickHouse SQL after strict unsafe-fragment validation. */
    fun unsafeRawOrderBy(vararg expressions: String) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** Sets the optional version column/expression used to keep the latest duplicate row. */
    fun versionColumn(expression: Expression<*>) {
        versionExpression = ClickHouseEngineExpression.from(expression)
    }

    /** Sets raw version column SQL after strict unsafe-fragment validation. */
    fun unsafeRawVersionColumn(expression: String) {
        versionExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** Sets the optional `PARTITION BY` expression. */
    fun partitionBy(expression: Expression<*>) {
        partitionByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** Sets raw `PARTITION BY` SQL after strict unsafe-fragment validation. */
    fun unsafeRawPartitionBy(expression: String) {
        partitionByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** Adds typed/validated ClickHouse engine settings. */
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

    /** Adds a raw ClickHouse engine setting value after strict unsafe-fragment validation. */
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
 * Creates a [ReplacingMergeTree] engine.
 */
fun replacingMergeTree(block: ReplacingMergeTreeBuilder.() -> Unit): ReplacingMergeTree =
    ReplacingMergeTreeBuilder().apply(block).build()

/**
 * DSL builder for [SummingMergeTree].
 */
class SummingMergeTreeBuilder {
    private var orderByExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var sumColumnExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var partitionByExpression: ClickHouseEngineExpression? = null
    private val settings: MutableList<ClickHouseSetting> = mutableListOf()

    /** Sets `ORDER BY` with typed Exposed expressions. At least one expression is required. */
    fun orderBy(vararg expressions: Expression<*>) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** Sets `ORDER BY` with raw ClickHouse SQL after strict unsafe-fragment validation. */
    fun unsafeRawOrderBy(vararg expressions: String) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** Sets columns to be summed. Defaults to all numeric columns when omitted. */
    fun sumColumns(vararg expressions: Expression<*>) {
        sumColumnExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** Sets raw sum column SQL after strict unsafe-fragment validation. */
    fun unsafeRawSumColumns(vararg expressions: String) {
        sumColumnExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** Sets the optional `PARTITION BY` expression. */
    fun partitionBy(expression: Expression<*>) {
        partitionByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** Sets raw `PARTITION BY` SQL after strict unsafe-fragment validation. */
    fun unsafeRawPartitionBy(expression: String) {
        partitionByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** Adds typed/validated ClickHouse engine settings. */
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

    /** Adds a raw ClickHouse engine setting value after strict unsafe-fragment validation. */
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
 * Creates a [SummingMergeTree] engine.
 */
fun summingMergeTree(block: SummingMergeTreeBuilder.() -> Unit): SummingMergeTree =
    SummingMergeTreeBuilder().apply(block).build()

/**
 * DSL builder for [AggregatingMergeTree].
 */
class AggregatingMergeTreeBuilder {
    private var orderByExpressions: List<ClickHouseEngineExpression> = emptyList()
    private var partitionByExpression: ClickHouseEngineExpression? = null
    private val settings: MutableList<ClickHouseSetting> = mutableListOf()

    /** Sets `ORDER BY` with typed Exposed expressions. At least one expression is required. */
    fun orderBy(vararg expressions: Expression<*>) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.from(it) }
    }

    /** Sets `ORDER BY` with raw ClickHouse SQL after strict unsafe-fragment validation. */
    fun unsafeRawOrderBy(vararg expressions: String) {
        orderByExpressions = expressions.map { ClickHouseEngineExpression.unsafeRaw(it) }
    }

    /** Sets the optional `PARTITION BY` expression. */
    fun partitionBy(expression: Expression<*>) {
        partitionByExpression = ClickHouseEngineExpression.from(expression)
    }

    /** Sets raw `PARTITION BY` SQL after strict unsafe-fragment validation. */
    fun unsafeRawPartitionBy(expression: String) {
        partitionByExpression = ClickHouseEngineExpression.unsafeRaw(expression)
    }

    /** Adds typed/validated ClickHouse engine settings. */
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

    /** Adds a raw ClickHouse engine setting value after strict unsafe-fragment validation. */
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
 * Creates an [AggregatingMergeTree] engine.
 */
fun aggregatingMergeTree(block: AggregatingMergeTreeBuilder.() -> Unit): AggregatingMergeTree =
    AggregatingMergeTreeBuilder().apply(block).build()
