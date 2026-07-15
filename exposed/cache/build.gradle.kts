val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

plugins {
    `java-test-fixtures`
}

// testFixtures 시나리오 패키지는 다른 모듈의 통합 테스트 지원용 코드이므로
// 이 모듈의 커버리지 측정에서 제외한다.
kover {
    reports {
        filters {
            excludes {
                packages("io.bluetape4k.exposed.cache.scenarios")
            }
        }
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Bluetape4k
    api(bt4k.bluetape4k.logging)

    // Exposed
    api(platform(libs.exposed.bom))
    api(bt4k.exposed.core)
    compileOnly(bt4k.exposed.jdbc)
    compileOnly(libs.exposed.dao)
    compileOnly(bt4k.exposed.java.time)

    // Coroutines
    compileOnly(libs.kotlinx.coroutines.core)

    // Test Fixtures
    testFixturesApi(bt4k.bluetape4k.logging)
    testFixturesApi(platform(libs.exposed.bom))
    testFixturesApi(bt4k.exposed.core)
    testFixturesApi(bt4k.exposed.jdbc)
    testFixturesImplementation(bt4k.exposed.java.time)

    testFixturesImplementation(bt4k.bluetape4k.junit5)
    testFixturesImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testFixturesImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testFixturesCompileOnly(bt4k.exposed.r2dbc)

    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.coroutines.test)

    testFixturesImplementation(libs.awaitility.kotlin)

    // Testing
    testImplementation(bt4k.bluetape4k.junit5)
}
