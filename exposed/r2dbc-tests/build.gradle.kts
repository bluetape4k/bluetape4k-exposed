val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    // Exposed
    implementation(platform(libs.exposed.bom))

    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    implementation(libs.exposed.migration.r2dbc)
    implementation(bt4k.exposed.java.time)

    // Id Generators
    implementation(bt4k.bluetape4k.idgenerators)
    implementation(libs.java.uuid.generator)

    // Coroutines
    compileOnly(bt4k.bluetape4k.coroutines)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    // R2DBC
    api(libs.r2dbc.spi)
    api(libs.r2dbc.pool)
    implementation(bt4k.r2dbc.h2)
    implementation(libs.r2dbc.mariadb)
    implementation(libs.r2dbc.mysql)
    implementation(libs.r2dbc.postgresql)

    // Bluetape4k Modules for Testing
    api(bt4k.bluetape4k.junit5)
    api(bt4k.bluetape4k.testcontainers)
    api(libs.testcontainers.mariadb)
    api(libs.testcontainers.mysql)
    api(libs.testcontainers.postgresql)

    // Database Drivers for Testcontainers Database
    compileOnly(libs.h2.v2)
    compileOnly(libs.mariadb.java.client)
    compileOnly(bt4k.mysql.connector.j)
    compileOnly(bt4k.postgresql)



    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
