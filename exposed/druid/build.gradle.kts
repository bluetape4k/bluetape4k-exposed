dependencies {
    implementation(bt4k.bluetape4k.core)

    api(bt4k.bluetape4k.logging)
    api(libs.kotlinx.coroutines.core)
    api(libs.avatica.core)

    testImplementation(bt4k.bluetape4k.junit5)
}
