
import org.gradle.api.tasks.testing.Test

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(bt4k.bluetape4k.core)
    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(libs.kotlinx.coroutines.core)
    api(bt4k.clickhouse.jdbc)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.hikaricp)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.clickhouse)
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
