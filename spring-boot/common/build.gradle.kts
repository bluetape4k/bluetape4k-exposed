plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
    // JDK 25 테스트 런타임에는 legacy JDK 21 provider를 섞지 않습니다.
    testRuntimeClasspath {
        exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
    }
}

dependencies {
    // Spring Boot와 Exposed BOM은 API dependency의 버전을 소비자에게 전달합니다.
    api(platform(bt4k.spring.boot4.dependencies))
    api(platform(bt4k.exposed.bom))

    // Spring Data 공통 SPI만 소유하며 JDBC/R2DBC adapter에는 의존하지 않습니다.
    api("org.springframework.data:spring-data-commons")
    api(libs.kotlin.reflect)
    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(libs.exposed.dao)
    compileOnly("org.springframework:spring-context")

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(project(":bluetape4k-exposed-dao"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(bt4k.mockk)
    testImplementation(bt4k.h2.v2)
}
