configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))

    // Clickhouse
    api(bt4k.clickhouse.jdbc)

    // Exposed
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)

    // Bluetape4k
    api(bt4k.bluetape4k.core)
    api(bt4k.bluetape4k.io)

    // Coroutines
    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.clickhouse)

    testImplementation(bt4k.hikaricp)
}

// Docker-backed integration and benchmark selectors are opt-in and remain disabled in CI's default test task.
val clickhouseV2Integration = providers.gradleProperty("clickhouseV2Integration")
val clickhouseV2Benchmark = providers.gradleProperty("clickhouseV2Benchmark")
val clickhouseV2BenchmarkRun = providers.gradleProperty("clickhouseV2BenchmarkRun")
val clickhouseV2BenchmarkOutputDir = rootProject.layout.projectDirectory
    .dir("docs/benchmarks/clickhouse-v2-rowbinary")
    .asFile
    .absolutePath

tasks.withType<Test>().configureEach {
    clickhouseV2Integration.orNull?.let { systemProperty("clickhouseV2Integration", it) }
    clickhouseV2Benchmark.orNull?.let { systemProperty("clickhouseV2Benchmark", it) }
    clickhouseV2BenchmarkRun.orNull?.let { systemProperty("clickhouseV2BenchmarkRun", it) }
    systemProperty("clickhouseV2BenchmarkOutputDir", clickhouseV2BenchmarkOutputDir)
}
