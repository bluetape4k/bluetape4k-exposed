val bluetape4kVersion: String by project

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
    api("io.github.bluetape4k:bluetape4k-logging:${bluetape4kVersion}")

    // Exposed
    api(platform(libs.exposed.bom))
    api(libs.exposed.core)
    compileOnly(libs.exposed.jdbc)
    compileOnly(libs.exposed.dao)
    compileOnly(libs.exposed.java.time)

    // Coroutines
    compileOnly(libs.kotlinx.coroutines.core)

    // Test Fixtures
    testFixturesApi("io.github.bluetape4k:bluetape4k-logging:${bluetape4kVersion}")
    testFixturesApi(platform(libs.exposed.bom))
    testFixturesApi(libs.exposed.core)
    testFixturesApi(libs.exposed.jdbc)
    testFixturesImplementation(libs.exposed.java.time)

    testFixturesImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testFixturesImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testFixturesImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testFixturesCompileOnly(libs.exposed.r2dbc)

    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.coroutines.test)

    testFixturesImplementation("io.github.bluetape4k:bluetape4k-assertions:${bluetape4kVersion}")
    testFixturesImplementation(libs.awaitility.kotlin)

    // Testing
    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation("io.github.bluetape4k:bluetape4k-assertions:${bluetape4kVersion}")
}
