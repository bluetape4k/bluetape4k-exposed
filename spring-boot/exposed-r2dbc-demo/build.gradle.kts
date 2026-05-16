val bluetape4kVersion: String by project

plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Spring Boot BOM: platform() 방식 필수 (dependencyManagement 사용 금지 - KGP 2.3 충돌)
    implementation(platform(libs.spring.boot.dependencies))

    implementation(project(":exposed-spring-boot-r2dbc"))

    implementation(libs.exposed.r2dbc)
    implementation(libs.exposed.java.time)

    implementation(libs.r2dbc.pool)
    runtimeOnly(libs.r2dbc.h2)
    runtimeOnly(libs.h2.v2)   // JDBC DataSource (DataInitializer + SchemaUtils에 필요)

    // Jackson 3
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.bluetape4k.junit5)
}
