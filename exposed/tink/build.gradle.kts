
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(bt4k.exposed.bom))

    api(bt4k.exposed.core)
    compileOnly(bt4k.exposed.jdbc)
    compileOnly(libs.exposed.dao)
    api(project(":bluetape4k-exposed-core"))
    compileOnly(project(":bluetape4k-exposed-dao"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    // Encryption - Google Tink
    api(bt4k.bluetape4k.tink)
    api(bt4k.tink)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    // Database Drivers
    testRuntimeOnly(bt4k.hikaricp)
    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(bt4k.pgjdbc.ng)
}
