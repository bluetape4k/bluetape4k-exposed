plugins { kotlin("jvm") version "2.4.10" }

val moduleVersion = providers.environmentVariable("ISSUE731_MODULE_VERSION").orElse("2.0.0").get()
val legacyModuleVersion = providers.environmentVariable("ISSUE731_LEGACY_MODULE_VERSION").orElse("1.12.1").get()
val bluetapeBomVersion = providers.environmentVariable("ISSUE731_BLUETAPE_BOM_VERSION").orElse("2.0.0-SNAPSHOT").get()
val sourceHead = providers.environmentVariable("ISSUE731_SOURCE_HEAD").orElse("").get()
val expectedHead = providers.environmentVariable("ISSUE731_EXPECTED_HEAD").orElse(sourceHead).get()

val legacyCompileClasspath = configurations.create("legacyCompileClasspath")

dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:$bluetapeBomVersion"))
    implementation(platform("io.github.bluetape4k.exposed:bluetape4k-exposed-bom:$moduleVersion"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch")
    implementation("io.github.bluetape4k:bluetape4k-jackson3")
    add(legacyCompileClasspath.name, "io.github.bluetape4k.exposed:bluetape4k-exposed-batch:$legacyModuleVersion")
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

val legacyBinaryDirectory = layout.buildDirectory.dir("legacy-classes")
val compileLegacyBinary = tasks.register<JavaCompile>("compileLegacyBinary") {
    dependsOn("verifyProvenance")
    source = fileTree("src/legacy/java") { include("**/*.java") }
    classpath = legacyCompileClasspath
    destinationDirectory.set(legacyBinaryDirectory)
    options.release.set(17)
}

tasks.register<JavaExec>("runLegacyBinary") {
    dependsOn(compileLegacyBinary, "classes")
    classpath = files(legacyBinaryDirectory, sourceSets.main.get().runtimeClasspath)
    mainClass.set("issue731.consumer.LegacyBinaryConsumer")
}

tasks.named("compileKotlin") { dependsOn("verifyProvenance") }
tasks.test {
    dependsOn("runLegacyBinary")
    useJUnitPlatform()
}
