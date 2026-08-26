plugins { kotlin("jvm") version "2.4.10" }

val moduleVersion = providers.environmentVariable("ISSUE731_MODULE_VERSION").orElse("2.0.0").get()
val bluetapeBomVersion = providers.environmentVariable("ISSUE731_BLUETAPE_BOM_VERSION").orElse("2.0.0-SNAPSHOT").get()
val sourceHead = providers.environmentVariable("ISSUE731_SOURCE_HEAD").orElse("").get()
val expectedHead = providers.environmentVariable("ISSUE731_EXPECTED_HEAD").orElse(sourceHead).get()

dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:$bluetapeBomVersion"))
    implementation(platform("io.github.bluetape4k.exposed:bluetape4k-exposed-bom:$moduleVersion"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch")
    testImplementation(kotlin("test"))
}

tasks.register("verifyProvenance") {
    doLast {
        check(sourceHead.isNotBlank()) {
            "ISSUE731_SOURCE_HEAD must identify the publication checkout"
        }
        check(sourceHead == expectedHead) {
            "published sourceHead=$sourceHead does not match expectedHead=$expectedHead"
        }
    }
}

tasks.named("compileKotlin") { dependsOn("verifyProvenance") }
tasks.test { useJUnitPlatform() }
