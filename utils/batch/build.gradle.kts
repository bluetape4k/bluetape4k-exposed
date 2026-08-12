
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
    // https://github.com/Kotlin/kotlinx-benchmark
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
    // JDK 25 테스트 런타임에는 legacy JDK 21 provider를 섞지 않습니다.
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

// https://github.com/Kotlin/kotlinx-benchmark
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
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("mysqlR2dbc") {
            include("io.bluetape4k.batch.benchmark.r2dbc.MySqlR2dbcBatchBenchmark")
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
    }
}

tasks.register<JavaExec>("generateBenchmarkDocs") {
    dependsOn("benchmarkClasses")
    classpath = sourceSets["benchmark"].runtimeClasspath
    mainClass.set("io.bluetape4k.batch.benchmark.support.BenchmarkDocsGeneratorKt")
    args(
        projectDir.absolutePath,
        layout.buildDirectory.dir("reports/benchmarks").get().asFile.absolutePath
    )
}

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(bt4k.bluetape4k.core)
    api(bt4k.bluetape4k.coroutines)
    api(bt4k.bluetape4k.logging)
    api(bt4k.bluetape4k.workflow)

    implementation(bt4k.bluetape4k.virtualthread.api)
    runtimeOnly(bt4k.bluetape4k.virtualthread.jdk21)

    // Exposed JDBC/R2DBC
    compileOnly(project(":bluetape4k-exposed-jdbc"))
    compileOnly(project(":bluetape4k-exposed-r2dbc"))
    compileOnly(bt4k.exposed.java.time)

    // Checkpoint JSON 직렬화
    compileOnly(bt4k.bluetape4k.jackson3)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Test
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.jackson3)
    testImplementation(libs.kotlinx.coroutines.test)
    // StructuredTaskScope provider의 JDK 25 classfile/runtime 호환성을 맞춥니다.
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)

    // JDBC/R2DBC 통합 테스트 인프라
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))

    // Test DB — H2 (내장)
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.hikaricp)
    testImplementation(bt4k.r2dbc.h2)
    testImplementation(bt4k.r2dbc.pool)

    // Test DB — PostgreSQL (Testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(bt4k.postgresql)
    testImplementation(bt4k.r2dbc.postgresql)

    // Test DB — MySQL (Testcontainers)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.r2dbc.mysql)

    // Benchmark
    add("benchmarkImplementation", bt4k.kotlinx.benchmark.runtime)
    add("benchmarkImplementation", bt4k.kotlinx.benchmark.runtime.jvm)
    add("benchmarkImplementation", bt4k.jmh.core)
}
