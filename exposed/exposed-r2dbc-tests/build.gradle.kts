val bluetape4kVersion: String by project

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    // Exposed
    implementation(platform(libs.exposed.bom))

    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    implementation(libs.exposed.migration.r2dbc)
    implementation(libs.exposed.java.time)

    implementation("io.github.bluetape4k:bluetape4k-idgenerators:${bluetape4kVersion}")

    // Coroutines
    compileOnly("io.github.bluetape4k:bluetape4k-coroutines:${bluetape4kVersion}")
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    // R2DBC
    api(libs.r2dbc.spi)
    api(libs.r2dbc.pool)
    implementation(libs.r2dbc.h2)
    implementation(libs.r2dbc.mariadb)
    implementation(libs.r2dbc.mysql)
    implementation(libs.r2dbc.postgresql)

    // Bluetape4k Modules for Testing
    api("io.github.bluetape4k:bluetape4k-assertions:${bluetape4kVersion}")
    api("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    api("io.github.bluetape4k:bluetape4k-testcontainers:${bluetape4kVersion}")
    api(libs.testcontainers.mariadb)
    api(libs.testcontainers.mysql)
    api(libs.testcontainers.postgresql)

    // Database Drivers for Testcontainers Database
    compileOnly(libs.h2.v2)
    compileOnly(libs.mariadb.java.client)
    compileOnly(libs.mysql.connector.j)
    compileOnly(libs.postgresql.driver)

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
