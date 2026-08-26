
plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
    // junit5의 legacy JDK 21 provider가 JDK 25 테스트 runtime에 섞이지 않도록 한다.
    testRuntimeClasspath {
        exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
    }
}

dependencies {
    // Spring Boot BOM: platform()을 사용하면 compileClasspath/runtimeClasspath에만 적용되고
    // kotlinBuildToolsApiClasspath 같은 내부 Gradle 설정에는 영향을 주지 않음
    // (dependencyManagement 플러그인은 ALL configurations에 적용되어 kotlin-stdlib 버전 충돌 유발)
    api(platform(bt4k.spring.boot4.dependencies))
    api(platform(bt4k.kotlinx.coroutines.bom))
    // Exposed platform must be API-visible for versionless Exposed API dependencies.
    api(platform(bt4k.exposed.bom))

    api("org.springframework.data:spring-data-commons")

    api(project(":bluetape4k-exposed-spring-boot-common"))

    api(libs.kotlin.reflect)
    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(libs.exposed.dao)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(bt4k.exposed.spring7.transaction)

    testImplementation(bt4k.exposed.migration.jdbc)
    testImplementation(bt4k.flyway.core)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)

    // JDK 25 테스트 런타임과 StructuredTaskScope provider의 classfile/preview 호환성을 맞춘다.
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)

    api(project(":bluetape4k-exposed-jdbc"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jdbc")
    compileOnly(project(":bluetape4k-exposed-jdbc-caffeine"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(bt4k.mockk)
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.hikaricp)

    // Multi-DB 테스트용 JDBC 드라이버
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.mariadb.java.client)
    testImplementation(bt4k.postgresql)
}
