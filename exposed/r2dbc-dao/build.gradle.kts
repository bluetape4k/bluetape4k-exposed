configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(bt4k.exposed.bom))

    api(bt4k.exposed.dao.r2dbc)
    api(project(":bluetape4k-exposed-core"))

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.reactor.core)
    testImplementation(bt4k.r2dbc.pool)

    testRuntimeOnly(bt4k.r2dbc.h2)
}
