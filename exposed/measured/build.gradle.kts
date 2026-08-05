
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(bt4k.exposed.bom))
    api(bt4k.exposed.core)
    compileOnly(bt4k.exposed.jdbc)
    compileOnly(libs.exposed.dao)

    api(bt4k.bluetape4k.measured)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    // Database Drivers for exposed-jdbc-tests dialect matrix
    testRuntimeOnly(bt4k.hikaricp)
    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(bt4k.pgjdbc.ng)
}
