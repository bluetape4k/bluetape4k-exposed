val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

dependencies {
    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    // BigQueryContext 가 Database.connect(), transaction() 을 내부적으로 호출하므로 implementation 필요
    implementation(bt4k.exposed.jdbc)
    implementation(bt4k.exposed.java.time)
    api(libs.kotlinx.coroutines.core)
    api(libs.google.api.services.bigquery)

    // BigQueryContext.create() 가 H2 sqlGenDb 를 내부 생성하므로 런타임 classpath 에 필요하다.
    implementation(libs.h2.v2)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.gcloud)
}
