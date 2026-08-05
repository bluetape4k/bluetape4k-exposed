
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(bt4k.exposed.bom))

    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.migration.r2dbc)
    testImplementation(bt4k.exposed.java.time)

    api(project(":bluetape4k-exposed-core"))
    api(project(":bluetape4k-exposed-dao"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))

    api(bt4k.bluetape4k.r2dbc)
    api(bt4k.r2dbc.spi)
    testRuntimeOnly(bt4k.r2dbc.h2)
    testRuntimeOnly(bt4k.r2dbc.mariadb)
    testRuntimeOnly(bt4k.r2dbc.mysql)
    testRuntimeOnly(bt4k.r2dbc.postgresql)

    // Coroutines
    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    compileOnly(bt4k.bluetape4k.io)
    compileOnly(bt4k.bluetape4k.idgenerators)

    // Bluetape4k Modules for Testing
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    // Database Drivers for Testcontainers Databases
    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
}
