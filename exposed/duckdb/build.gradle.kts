tasks.test {
    // DuckDB JDBC uses System.load() for native library — required for Java 25+
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(bt4k.bluetape4k.core)
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)

    // DuckDB JDBC 드라이버
    api(bt4k.duckdb.jdbc)

    // Coroutines
    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
