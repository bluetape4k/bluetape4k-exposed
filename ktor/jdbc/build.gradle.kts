dependencies {
    api(platform(bt4k.ktor.bom))
    api(platform(bt4k.exposed.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(project(":bluetape4k-exposed-ktor-core"))
    api(bt4k.exposed.jdbc)
    api(libs.kotlinx.coroutines.core)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.exposed.java.time)
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(bt4k.bluetape4k.jdbc)
    testImplementation(bt4k.h2.v2)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testImplementation(libs.kotlinx.coroutines.test)
}
