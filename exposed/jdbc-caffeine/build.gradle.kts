
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
    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-cache"))
    api(bt4k.caffeine)

    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    api(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.hikaricp)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility.kotlin)

    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(bt4k.mariadb.java.client)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.postgresql)
    testImplementation(bt4k.pgjdbc.ng)
}
