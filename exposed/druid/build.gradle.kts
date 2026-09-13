dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(bt4k.bluetape4k.core)
    api(libs.kotlinx.coroutines.core)
    api(bt4k.avatica.core)

    testImplementation(bt4k.bluetape4k.junit5)
}
