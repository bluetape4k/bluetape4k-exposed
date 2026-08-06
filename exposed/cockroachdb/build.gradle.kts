dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    implementation(bt4k.bluetape4k.core)

    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(bt4k.postgresql)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.jdbc)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.exposed.migration.jdbc)
    testImplementation(bt4k.hikaricp)
    testImplementation(libs.testcontainers.cockroachdb)
}
