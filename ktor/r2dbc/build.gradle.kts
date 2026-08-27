dependencies {
    api(platform(bt4k.ktor.bom))
    api(platform(bt4k.exposed.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(project(":bluetape4k-exposed-ktor-core"))
    api(bt4k.exposed.r2dbc)
    api(libs.kotlinx.coroutines.core)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.exposed.java.time)
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(bt4k.r2dbc.h2)
    // R2DBC Testcontainers 초기화가 MySQL 대기를 위해 JDBC 연결을 사용합니다.
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.r2dbc.mysql)
    testRuntimeOnly(bt4k.r2dbc.postgresql)
    testImplementation(libs.kotlinx.coroutines.test)
}
