val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(bt4k.ktor.bom))
    implementation(platform(bt4k.exposed.bom))
    implementation(platform(libs.micrometer.bom))

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
    testImplementation(libs.mockk)

    testImplementation(bt4k.exposed.java.time)
    testImplementation(libs.h2.v2)
    testImplementation(bt4k.r2dbc.h2)
    testImplementation(libs.micrometer.test)
}
