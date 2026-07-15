val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(bt4k.timefold.solver.bom))

    api(libs.timefold.solver.core)

    api(bt4k.exposed.core)
    compileOnly(project(":bluetape4k-exposed-jdbc"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    // JDBC Drivers
    testImplementation(bt4k.hikaricp)
    testRuntimeOnly(libs.h2.v2)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(libs.pgjdbc.ng)

    // Bluetape4k Modules for Testing
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
}
