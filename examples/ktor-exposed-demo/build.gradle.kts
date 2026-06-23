plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.examples.exposed.ktor.KtorExposedDemoApplicationKt")
}

dependencies {
    implementation(platform(bt4k.ktor.bom))
    implementation(platform(libs.exposed.bom))

    implementation(project(":bluetape4k-exposed-ktor"))
    implementation(bt4k.bluetape4k.ktor.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.r2dbc)
    implementation(libs.hikaricp)
    implementation(libs.r2dbc.pool)
    implementation(libs.kotlinx.coroutines.core)
    implementation("io.ktor:ktor-server-netty")

    runtimeOnly(libs.h2.v2)
    runtimeOnly(libs.r2dbc.h2)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
}
