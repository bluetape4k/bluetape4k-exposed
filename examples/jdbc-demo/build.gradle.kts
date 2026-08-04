val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

plugins {
    kotlin("plugin.spring")
    alias(bt4k.plugins.exposed.plugin)
}

exposed {
    migrations {
        tablesPackage.set("io.bluetape4k.examples.exposed.mvc.domain")
        databaseUrl.set("jdbc:h2:mem:exposed-jdbc-demo-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
        databaseUser.set("sa")
        databasePassword.set("")
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Spring Boot BOM: platform() 방식 필수 (dependencyManagement 사용 금지 - KGP 2.3 충돌)
    implementation(platform(bt4k.spring.boot4.dependencies))

    implementation(project(":bluetape4k-exposed-spring-boot-jdbc"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    implementation(bt4k.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(bt4k.exposed.migration.jdbc)
    implementation(bt4k.exposed.java.time)
    runtimeOnly(bt4k.h2.v2)

    // Jackson 3
    implementation(bt4k.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(bt4k.bluetape4k.junit5)
}
