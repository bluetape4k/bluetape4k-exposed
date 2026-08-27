plugins {
    alias(bt4k.plugins.kotlin.serialization)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.ktor.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(platform(bt4k.kotlinx.serialization.bom))
    api(platform(bt4k.micrometer.bom))

    api(bt4k.bluetape4k.ktor.core)
    api("io.ktor:ktor-server-core")
    api("io.ktor:ktor-server-status-pages")
    api("io.ktor:ktor-server-content-negotiation")
    api("io.ktor:ktor-serialization-kotlinx-json")
    api("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm")
    api(libs.kotlinx.serialization.json.jvm)
    api(libs.kotlinx.coroutines.core)
    api(libs.micrometer.core)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.test)
}
