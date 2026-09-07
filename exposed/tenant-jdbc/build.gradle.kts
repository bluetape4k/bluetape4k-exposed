configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(platform(bt4k.exposed.bom))
    api(bt4k.exposed.jdbc)

    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.hikaricp)
}
