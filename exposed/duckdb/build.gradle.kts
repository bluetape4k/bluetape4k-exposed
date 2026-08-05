
tasks.test {
    // DuckDB JDBC uses System.load() for native library — required for Java 25+
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(bt4k.bluetape4k.core)
    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(libs.kotlinx.coroutines.core)

    // DuckDB JDBC 드라이버
    api(bt4k.duckdb.jdbc)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
