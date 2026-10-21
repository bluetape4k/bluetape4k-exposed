dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(bt4k.avatica.core)

    api(bt4k.bluetape4k.core)
    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(bt4k.bluetape4k.junit5)
}
