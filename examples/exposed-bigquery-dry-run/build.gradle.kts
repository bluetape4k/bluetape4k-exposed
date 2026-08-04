val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    testImplementation(project(":bluetape4k-exposed-bigquery"))

    testImplementation(bt4k.exposed.core)
    testImplementation(bt4k.exposed.jdbc)
    testImplementation(bt4k.h2.v2)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.mockk)
}
