
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.ktor.bom))
    api(platform(bt4k.exposed.bom))
    api(platform(bt4k.micrometer.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(bt4k.bluetape4k.ktor.core)

    api(project(":bluetape4k-exposed-cache"))
    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-r2dbc"))

    api(libs.kotlinx.coroutines.core)
    api(libs.micrometer.core)

    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation("io.ktor:ktor-server-auth")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(bt4k.mockk)

    testImplementation(bt4k.exposed.java.time)
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.r2dbc.h2)
    testImplementation(libs.micrometer.test)
}
