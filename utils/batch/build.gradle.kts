import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("plugin.allopen")
    alias(bt4k.plugins.kotlinx.benchmark)
    alias(bt4k.plugins.kover)
}

kover {
    reports {
        filters {
            excludes {
                packages("io.bluetape4k.batch.benchmark")
            }
        }
    }
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

sourceSets {
    create("benchmark")
}

kotlin {
    target {
        compilations.getByName("benchmark").associateWith(compilations.getByName("main"))
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
    testRuntimeClasspath {
        exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
    }
    named("benchmarkImplementation") {
        extendsFrom(
            configurations.getByName("implementation"),
            configurations.getByName("compileOnly"),
            configurations.getByName("testImplementation"),
        )
    }
    named("benchmarkRuntimeOnly") {
        extendsFrom(
            configurations.getByName("runtimeOnly"),
            configurations.getByName("testRuntimeOnly"),
        )
    }
}

benchmark {
    targets {
        register("benchmark") {
            this as kotlinx.benchmark.gradle.JvmBenchmarkTarget
            jmhVersion = bt4k.versions.managed.jmh.core.h350a653f63e5.get()
        }
    }
    configurations {
        register("h2Jdbc") {
            include("io.bluetape4k.batch.benchmark.jdbc.H2JdbcBatchBenchmark")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("h2R2dbc") {
            include("io.bluetape4k.batch.benchmark.r2dbc.H2R2dbcBatchBenchmark")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("postgresJdbc") {
            include("io.bluetape4k.batch.benchmark.jdbc.PostgreSqlJdbcBatchBenchmark")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("postgresR2dbc") {
            include("io.bluetape4k.batch.benchmark.r2dbc.PostgreSqlR2dbcBatchBenchmark")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("mysqlJdbc") {
            include("io.bluetape4k.batch.benchmark.jdbc.MySqlJdbcBatchBenchmark")
            warmups = 2
            iterations = 5
            iterationTime = 5
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("mysqlR2dbc") {
            include("io.bluetape4k.batch.benchmark.r2dbc.MySqlR2dbcBatchBenchmark")
            warmups = 2
            iterations = 5
            iterationTime = 5
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
    }
}

val benchmarkReportRoot = layout.buildDirectory.dir("reports/benchmarks")

val writeBenchmarkSidecars = tasks.register<org.gradle.api.tasks.Exec>("writeBenchmarkSidecars") {
    commandLine(
        "python3",
        rootProject.file("scripts/batch/write_benchmark_sidecars.py").absolutePath,
        benchmarkReportRoot.get().asFile.absolutePath,
        "--source-root",
        rootProject.rootDir.absolutePath,
        "--source-ref",
        providers.gradleProperty("benchmarkSourceRef").orElse("local").get(),
        "--warmups",
        "2",
        "--iterations",
        "5",
        "--metric-type",
        "ops/s",
    )
}

val validateBenchmarkSidecars = tasks.register<org.gradle.api.tasks.Exec>("validateBenchmarkSidecars") {
    dependsOn(writeBenchmarkSidecars)
    commandLine(
        "python3",
        rootProject.file("scripts/batch/validate_benchmark_sidecars.py").absolutePath,
        benchmarkReportRoot.get().asFile.absolutePath,
        "--source-root",
        rootProject.rootDir.absolutePath,
    )
}

val benchmarkProfilesByTask = mapOf(
    "h2JdbcBenchmark" to "h2Jdbc",
    "h2R2dbcBenchmark" to "h2R2dbc",
    "postgresJdbcBenchmark" to "postgresJdbc",
    "postgresR2dbcBenchmark" to "postgresR2dbc",
    "mysqlJdbcBenchmark" to "mysqlJdbc",
    "mysqlR2dbcBenchmark" to "mysqlR2dbc",
)

val benchmarkSidecarTasks = benchmarkProfilesByTask.mapValues { (benchmarkTaskName, profile) ->
    tasks.register<org.gradle.api.tasks.Exec>("write${benchmarkTaskName.removeSuffix("Benchmark").replaceFirstChar { it.uppercase() }}BenchmarkSidecar") {
        commandLine(
            "python3",
            rootProject.file("scripts/batch/write_benchmark_sidecars.py").absolutePath,
            benchmarkReportRoot.get().asFile.absolutePath,
            "--source-root",
            rootProject.rootDir.absolutePath,
            "--source-ref",
            providers.gradleProperty("benchmarkSourceRef").orElse("local").get(),
            "--warmups",
            "2",
            "--iterations",
            "5",
            "--metric-type",
            "ops/s",
            "--profile",
            profile,
        )
    }
}

tasks.configureEach {
    benchmarkSidecarTasks[name]?.let { sidecarTask ->
        finalizedBy(sidecarTask)
    }
}

tasks.register<JavaExec>("generateBenchmarkDocs") {
    dependsOn("benchmarkClasses")
    dependsOn(validateBenchmarkSidecars)
    classpath = sourceSets["benchmark"].runtimeClasspath
    mainClass.set("io.bluetape4k.batch.benchmark.support.BenchmarkDocsGeneratorKt")
    args(
        projectDir.absolutePath,
        benchmarkReportRoot.get().asFile.absolutePath,
    )
}

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(project(":bluetape4k-exposed-batch-core"))
    api(project(":bluetape4k-exposed-batch-jdbc"))
    api(project(":bluetape4k-exposed-batch-r2dbc"))

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.jackson3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.hikaricp)
    testImplementation(bt4k.r2dbc.h2)
    testImplementation(bt4k.r2dbc.pool)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(bt4k.postgresql)
    testImplementation(bt4k.r2dbc.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.r2dbc.mysql)

    add("benchmarkImplementation", bt4k.kotlinx.benchmark.runtime)
    add("benchmarkImplementation", bt4k.kotlinx.benchmark.runtime.jvm)
    add("benchmarkImplementation", bt4k.jmh.core)
}

// The aggregator keeps the historical effective JAR surface while publishing
// the three child artifacts as the canonical dependency coordinates.
val coreJar = project(":bluetape4k-exposed-batch-core").tasks.named<Jar>("jar")
val jdbcJar = project(":bluetape4k-exposed-batch-jdbc").tasks.named<Jar>("jar")
val r2dbcJar = project(":bluetape4k-exposed-batch-r2dbc").tasks.named<Jar>("jar")

tasks.named<Jar>("jar") {
    dependsOn(coreJar, jdbcJar, r2dbcJar)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ zipTree(coreJar.get().archiveFile) })
    from({ zipTree(jdbcJar.get().archiveFile) })
    from({ zipTree(r2dbcJar.get().archiveFile) })
}
