val bluetape4kVersion: String by project

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(libs.exposed.bom))
    api(libs.exposed.core)
    api(libs.kotlinx.coroutines.core)
    compileOnly(libs.exposed.jdbc)
    compileOnly(libs.exposed.dao)
    compileOnly(libs.exposed.crypt)
    compileOnly(libs.exposed.kotlin.datetime)
    compileOnly(libs.exposed.java.time)
    compileOnly(libs.exposed.json)
    compileOnly(libs.exposed.money)

    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    // exposed-dao 모듈에서 idEquals, idHashCode 등 사용
    testImplementation(project(":bluetape4k-exposed-dao"))

    // Entity ID generators (ColumnExtensions에서 사용)
    api(libs.bluetape4k.idgenerators)
    api(libs.java.uuid.generator)

    //
    // Custom Column Types
    //

    // Compress column types
    compileOnly(libs.bluetape4k.io)

    // Serializer (runtime for tests)
    testRuntimeOnly(libs.kryo5)
    testRuntimeOnly(libs.fory.kotlin)  // new Apache Fory

    // Compressors
    testRuntimeOnly(libs.lz4.java)
    testRuntimeOnly(libs.snappy.java)
    testRuntimeOnly(libs.zstd.jni)

    // Phone number column types (compileOnly -> testImplementation 자동 전이 via extendsFrom)
    compileOnly(libs.libphonenumber)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    // Database Drivers
    compileOnly(libs.hikaricp)
    testRuntimeOnly(libs.h2.v2)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.postgresql.driver)
    testRuntimeOnly(libs.pgjdbc.ng)
}
