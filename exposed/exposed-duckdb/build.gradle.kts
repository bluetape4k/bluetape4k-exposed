val bluetape4kVersion: String by project

tasks.test {
    // DuckDB JDBC uses System.load() for native library — required for Java 25+
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation("io.github.bluetape4k:bluetape4k-core:${bluetape4kVersion}")
    api("io.github.bluetape4k:bluetape4k-logging:${bluetape4kVersion}")
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    api(libs.kotlinx.coroutines.core)

    // DuckDB JDBC 드라이버
    api(libs.duckdb.jdbc)

    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation(libs.kotlinx.coroutines.test)
}
