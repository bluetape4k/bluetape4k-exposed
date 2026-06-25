val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":bluetape4k-exposed-bigquery"))

    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.h2.v2)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.mockk)
}
