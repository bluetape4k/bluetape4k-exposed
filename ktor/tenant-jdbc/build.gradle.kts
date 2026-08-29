dependencies {
    val bluetape4kBomVersion = rootProject.extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("bt4k")
        .findVersion("bluetape4k-bom")
        .get()
        .requiredVersion

    api(platform(bt4k.ktor.bom))
    api(platform(bt4k.exposed.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(project(":bluetape4k-exposed-ktor-jdbc"))
    api("io.github.bluetape4k:bluetape4k-tenant:$bluetape4kBomVersion")
    api("io.github.bluetape4k:bluetape4k-ktor-tenant:$bluetape4kBomVersion")
    api(libs.kotlinx.coroutines.core)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.h2.v2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.test)
}
