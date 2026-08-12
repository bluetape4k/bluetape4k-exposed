plugins {
    kotlin("plugin.allopen")
    alias(bt4k.plugins.kotlinx.benchmark)
}

sourceSets {
    create("benchmark")
}

kotlin {
    target {
        compilations.getByName("benchmark")
            .associateWith(compilations.getByName("main"))
    }
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

configurations {
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
        named("main") {
            warmups = 1
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("jdbcR2dbc") {
            include("io.bluetape4k.exposed.benchmark.jdbc.*")
            include("io.bluetape4k.exposed.benchmark.r2dbc.*")
            warmups = 1
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("idTables") {
            include("io.bluetape4k.exposed.benchmark.id.*")
            warmups = 1
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("cache") {
            include("io.bluetape4k.exposed.benchmark.cache.CacheStrategyBenchmark.*")
            include("io.bluetape4k.exposed.benchmark.cache.SnapshotCacheBenchmark.*")
            warmups = 1
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("redisCache") {
            include("io.bluetape4k.exposed.benchmark.cache.RedisCacheBenchmark.*")
            warmups = 1
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
        }
        register("smoke") {
            include("io.bluetape4k.exposed.benchmark.*.*")
            exclude("io.bluetape4k.exposed.benchmark.cache.RedisCacheBenchmark.*")
            warmups = 1
            iterations = 1
            iterationTime = 100
            iterationTimeUnit = "ms"
            mode = "thrpt"
            outputTimeUnit = "s"
            reportFormat = "json"
            param("rowCount", "100")
            param("cacheSize", "1000")
        }
    }
}

tasks.register<JavaExec>("generateBenchmarkDocs") {
    dependsOn("benchmarkClasses")
    classpath = sourceSets["benchmark"].runtimeClasspath
    mainClass.set("io.bluetape4k.exposed.benchmark.support.BenchmarkDocsGeneratorKt")
    args(
        projectDir.absolutePath,
        rootDir.absolutePath,
        layout.buildDirectory.dir("reports/benchmarks").get().asFile.absolutePath,
    )
}

configurations {
    // JDK 25 benchmark runtime에는 legacy JDK 21 provider를 섞지 않습니다.
    named("benchmarkRuntimeClasspath") {
        exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
    }
}

dependencies {
    add("benchmarkImplementation", bt4k.kotlinx.benchmark.runtime)
    add("benchmarkImplementation", bt4k.kotlinx.benchmark.runtime.jvm)
    add("benchmarkImplementation", bt4k.jmh.core)

    add("benchmarkImplementation", project(":bluetape4k-exposed-core"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-dao"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-jdbc"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-r2dbc"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-cache"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-jdbc-caffeine"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-jdbc-lettuce"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-jdbc-redisson"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-r2dbc-caffeine"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-r2dbc-lettuce"))
    add("benchmarkImplementation", project(":bluetape4k-exposed-r2dbc-redisson"))

    add("benchmarkImplementation", bt4k.bluetape4k.core)
    add("benchmarkImplementation", bt4k.bluetape4k.coroutines)
    add("benchmarkImplementation", bt4k.bluetape4k.idgenerators)
    add("benchmarkImplementation", bt4k.bluetape4k.jackson3)
    add("benchmarkImplementation", bt4k.bluetape4k.logging)
    add("benchmarkImplementation", bt4k.bluetape4k.testcontainers)
    add("benchmarkImplementation", bt4k.bluetape4k.virtualthread.api)
    // JDK 25 benchmark runtime과 StructuredTaskScope provider의 classfile/preview 호환성을 맞춘다.
    add("benchmarkImplementation", bt4k.bluetape4k.virtualthread.jdk25)
    add("benchmarkImplementation", bt4k.caffeine)
    add("benchmarkImplementation", libs.exposed.dao)
    add("benchmarkImplementation", bt4k.exposed.java.time)
    add("benchmarkImplementation", bt4k.exposed.jdbc)
    add("benchmarkImplementation", bt4k.exposed.r2dbc)
    add("benchmarkImplementation", bt4k.h2.v2)
    add("benchmarkImplementation", bt4k.hikaricp)
    add("benchmarkImplementation", libs.kotlinx.coroutines.core)
    add("benchmarkImplementation", libs.kotlinx.coroutines.reactor)
    add("benchmarkImplementation", libs.lettuce.core)
    add("benchmarkImplementation", bt4k.r2dbc.h2)
    add("benchmarkImplementation", bt4k.r2dbc.pool)
    add("benchmarkImplementation", bt4k.redisson)
    add("benchmarkImplementation", bt4k.slf4j.api)

    add("benchmarkRuntimeOnly", bt4k.logback)
    add("benchmarkRuntimeOnly", libs.jcl.over.slf4j)
    add("benchmarkRuntimeOnly", libs.jul.to.slf4j)
    add("benchmarkRuntimeOnly", libs.log4j.over.slf4j)
}
