val springBootVersion = bt4k.versions.spring.boot.get()

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

// Spring Boot 4 baseline을 compile/test classpath에만 보강한다.
// configurations.all 은 Kotlin 내부 설정(kotlinBuildToolsApiClasspath 등)에도 영향을 주어 컴파일 오류를 유발하므로,
// 실제 컴파일/런타임 classpath 설정만 대상으로 제한한다.
listOf(
    "compileClasspath", "runtimeClasspath",
    "testCompileClasspath", "testRuntimeClasspath",
).forEach { configName ->
    configurations.matching { it.name == configName }.configureEach {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "org.springframework.boot" -> {
                    useVersion(springBootVersion)
                    because("spring-boot 모듈: Spring Boot 4 baseline 유지")
                }
                "org.springframework" -> {
                    // spring-batch 6.x 가 요구하는 Spring Framework 7.x 보장
                    if (requested.name.startsWith("spring-") &&
                        !requested.name.contains("security") &&
                        !requested.name.contains("data")
                    ) {
                        useVersion("7.0.6")
                        because("spring-boot 모듈: Spring Framework 7 baseline 유지")
                    }
                }
                "org.springframework.batch" -> {
                    useVersion("6.0.3")
                    because("spring-boot 모듈: Spring Batch 6 baseline 유지")
                }
            }
        }
    }
}

dependencies {
    // Spring Boot BOM: platform()을 사용하면 compileClasspath/runtimeClasspath에만 적용되고
    // kotlinBuildToolsApiClasspath 같은 내부 Gradle 설정에는 영향을 주지 않음
    // (dependencyManagement 플러그인은 ALL configurations에 적용되어 kotlin-stdlib 버전 충돌 유발)
    api(platform(bt4k.spring.boot4.dependencies))
    api(platform(bt4k.kotlinx.coroutines.bom))

    // Core
    api(libs.kotlin.reflect)
    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-core"))
    api(bt4k.bluetape4k.virtualthread.api)

    // Exposed
    api(bt4k.exposed.spring7.transaction)
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)

    // Spring Batch (Spring Boot BOM 버전 관리)
    api("org.springframework.boot:spring-boot-starter-batch")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    // Test
    testImplementation(bt4k.bluetape4k.junit5)
    // 테스트 fixture의 Exposed starter 대신 이 모듈의 Spring Boot platform/starter 조합을 사용한다.
    testImplementation(project(":bluetape4k-exposed-jdbc-tests")) {
        exclude(group = "org.jetbrains.exposed", module = "exposed-spring-boot-starter")
    }
    // JDK 25 테스트 런타임과 StructuredTaskScope provider의 classfile/preview 호환성을 맞춘다.
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")  // DataSource auto-configuration (Spring Boot 분리 모듈)
    testImplementation(libs.spring.batch.test)
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.hikaricp)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(bt4k.bluetape4k.testcontainers.spring)
    testImplementation(bt4k.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(bt4k.mysql.connector.j)
}
