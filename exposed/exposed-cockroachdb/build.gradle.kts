dependencies {
    implementation(libs.bluetape4k.core)

    api(libs.bluetape4k.logging)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    api(libs.postgresql.driver)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.jdbc)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.exposed.migration.jdbc)
    testImplementation(libs.hikaricp)
    testImplementation(libs.testcontainers.cockroachdb)
}
