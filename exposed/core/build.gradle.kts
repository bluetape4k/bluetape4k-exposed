
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(bt4k.exposed.bom))
    api(bt4k.exposed.core)
    api(libs.kotlinx.coroutines.core)
    compileOnly(bt4k.exposed.jdbc)
    compileOnly(libs.exposed.dao)
    compileOnly(libs.exposed.crypt)
    compileOnly(libs.exposed.kotlin.datetime)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.json)
    compileOnly(libs.exposed.money)

    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    // exposed-dao 모듈에서 idEquals, idHashCode 등 사용
    testImplementation(project(":bluetape4k-exposed-dao"))

    // Entity ID generators (ColumnExtensions에서 사용)
    api(bt4k.bluetape4k.idgenerators)
    api(bt4k.java.uuid.generator)

    //
    // Custom Column Types
    //

    // Compress column types
    compileOnly(bt4k.bluetape4k.io)

    // Serializer (runtime for tests)
    testRuntimeOnly(bt4k.kryo5)
    testRuntimeOnly(bt4k.fory.kotlin)  // new Apache Fory

    // Compressors
    testRuntimeOnly(bt4k.at.yawk.lz4.java)
    testRuntimeOnly(bt4k.snappy.java)
    testRuntimeOnly(bt4k.zstd.jni)

    // Phone number column types (compileOnly -> testImplementation 자동 전이 via extendsFrom)
    compileOnly(bt4k.libphonenumber.v8)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    // Database Drivers
    compileOnly(bt4k.hikaricp)
    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(bt4k.pgjdbc.ng)
}
