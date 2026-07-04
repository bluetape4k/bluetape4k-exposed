dependencies {
    implementation(libs.bluetape4k.core)

    api(libs.bluetape4k.logging)
    api(libs.kotlinx.coroutines.core)
    api(libs.avatica.core)

    testImplementation(libs.bluetape4k.junit5)
}
