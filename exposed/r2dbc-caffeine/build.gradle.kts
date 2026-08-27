
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val exposedCacheMainClasses = project(":bluetape4k-exposed-cache")
    .layout.buildDirectory
    .dir("classes/kotlin/main")

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(project(":bluetape4k-exposed-cache").tasks.named("compileKotlin"))
    compilerOptions.freeCompilerArgs.add(
        "-Xfriend-paths=${exposedCacheMainClasses.get().asFile.absolutePath}"
    )
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(project(":bluetape4k-exposed-r2dbc"))
    api(project(":bluetape4k-exposed-cache"))
    api(bt4k.bluetape4k.coroutines)
    api(bt4k.caffeine)

    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.reactive)

    testRuntimeOnly(bt4k.r2dbc.h2)
    testRuntimeOnly(bt4k.postgresql)     // Testcontainers PostgreSQL startup verification
    testRuntimeOnly(bt4k.mysql.connector.j)     // Testcontainers MySQL8 startup verification
    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.h2.v2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility.kotlin)
}
