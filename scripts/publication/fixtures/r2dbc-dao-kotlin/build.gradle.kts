plugins {
    id("org.jetbrains.kotlin.jvm")
}

val stagedVersion = providers.gradleProperty("stagedVersion").get()
val r2dbcH2Version = providers.gradleProperty("r2dbcH2Version").get()

dependencies {
    implementation(platform("io.github.bluetape4k.exposed:bluetape4k-exposed-bom:${stagedVersion}"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-dao")
    runtimeOnly("io.r2dbc:r2dbc-h2:${r2dbcH2Version}")
}

tasks.register<JavaExec>("runR2dbcDaoConsumer") {
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("probe.R2dbcDaoConsumerKt")
}

listOf("compileClasspath", "runtimeClasspath").forEach { configurationName ->
    tasks.register("print${configurationName.replaceFirstChar(Char::uppercase)}Coordinates") {
        doLast {
            configurations.getByName(configurationName).incoming.resolutionResult.allComponents
                .mapNotNull { it.moduleVersion }
                .map { "${it.group}:${it.name}:${it.version}" }
                .toSortedSet()
                .forEach { println("R2DBC_DAO_COORDINATE:${configurationName}:${it}") }
        }
    }
}
