val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.exposed.bom))
    api(bt4k.exposed.core)
    compileOnly(bt4k.exposed.jdbc)
    compileOnly(libs.exposed.dao)

    api(bt4k.bluetape4k.measured)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    // Database Drivers for exposed-jdbc-tests dialect matrix
    testRuntimeOnly(bt4k.hikaricp)
    testRuntimeOnly(libs.h2.v2)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(libs.pgjdbc.ng)
}
