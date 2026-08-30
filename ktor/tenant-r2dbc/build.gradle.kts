dependencies {
    api(platform(bt4k.ktor.bom))
    api(platform(bt4k.exposed.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(project(":bluetape4k-exposed-ktor-r2dbc"))
    api(bt4k.bluetape4k.tenant)
    api(bt4k.bluetape4k.ktor.tenant)
    api(libs.kotlinx.coroutines.core)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.r2dbc.h2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.test)
}
