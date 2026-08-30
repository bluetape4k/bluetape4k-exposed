dependencies {
    api(platform(bt4k.ktor.bom))
    api(platform(bt4k.exposed.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(project(":bluetape4k-exposed-ktor-jdbc"))
    api(bt4k.bluetape4k.tenant)
    api(bt4k.bluetape4k.ktor.tenant)
    api(libs.kotlinx.coroutines.core)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.h2.v2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.test)
}
