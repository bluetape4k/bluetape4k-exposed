import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import nmcp.NmcpAggregationExtension
import nmcp.NmcpExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.BinariesSource
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    base
    `maven-publish`
    signing
    alias(bt4k.plugins.kotlin.jvm)

    alias(bt4k.plugins.kotlin.spring) apply false
    alias(bt4k.plugins.kotlin.allopen) apply false
    alias(bt4k.plugins.kotlin.noarg) apply false
    alias(bt4k.plugins.kotlin.serialization) apply false
    alias(bt4k.plugins.kotlinx.atomicfu)

    alias(bt4k.plugins.detekt.dev)
    alias(bt4k.plugins.dependency.management)

    alias(bt4k.plugins.dokka)
    alias(bt4k.plugins.test.logger)

    alias(bt4k.plugins.nmcp.aggregation)
    alias(bt4k.plugins.nmcp) apply false

    alias(bt4k.plugins.kover)
    alias(bt4k.plugins.exposed.plugin) apply false
}

val rootLibs = libs
val rootBt4k = bt4k
val bt4kCatalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("bt4k")
fun bt4kLibrary(alias: String) = bt4kCatalog.findLibrary(alias).get()
fun bt4kVersion(alias: String): String {
    val version = bt4kCatalog.findVersion(alias).get()
    return version.requiredVersion
        .ifBlank { version.preferredVersion }
        .ifBlank { version.strictVersion }
}

@OptIn(ExperimentalAbiValidation::class)
fun Project.configureProductionAbiValidation() {
    if (isNonPublishedModule() || name == "bluetape4k-exposed-bom") return

    extensions.configure<KotlinProjectExtension> {
        abiValidation {
            referenceDumpDir.set(rootProject.layout.projectDirectory.dir("api"))
            binariesSource.set(BinariesSource.MAVEN_PUBLICATIONS)
        }
    }
}


fun Project.isNonPublishedModule(): Boolean {
    val relativePath = rootProject.rootDir.toPath()
        .relativize(projectDir.toPath())
        .toString()
        .replace(File.separatorChar, '/')

    return relativePath == "examples" ||
            relativePath.startsWith("examples/") ||
            relativePath == "benchmark" ||
            relativePath.startsWith("benchmark/") ||
            name.contains("-demo") ||
            name.endsWith("-benchmark")
}

val centralPublishing = resolveCentralPublishingConfig()
val centralUser: String = centralPublishing.username
val centralPassword: String = centralPublishing.password
val centralSnapshotsParallelism: Int = providers
    .gradleProperty("centralSnapshotsParallelism")
    .map(String::toInt)
    .orElse(4)
    .get()

val projectGroup: String = providers.gradleProperty("projectGroup").get()
val baseVersion: String = providers.gradleProperty("baseVersion").get()
val snapshotVersion: String = providers.gradleProperty("snapshotVersion").get()

allprojects {
    group = projectGroup
    version = baseVersion + snapshotVersion

    repositories {
        mavenCentral()
        google()
        maven {
            name = "central-snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(1, TimeUnit.DAYS)
    }
    configurations.matching { it.name.startsWith("dokka") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jsoup" && requested.name == "jsoup") {
                useVersion("1.23.1")
                because("CVE-2026-71497: Dokka tooling must use the first patched jsoup release")
            }
        }
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
    }
    if (!isNonPublishedModule()) {
        apply(plugin = "com.gradleup.nmcp")
    }

    configurations.matching { it.name.startsWith("nmcp") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization")) {
                useVersion("1.9.0")
                because("nmcp runtime compatibility")
            }
        }
    }

    plugins.withId("com.gradleup.nmcp") {
        extensions.configure<NmcpExtension>("nmcp") {
            publishAllPublicationsToCentralPortal {
                username.set(centralUser)
                password.set(centralPassword)
                publishingType.set("AUTOMATIC")
                uploadSnapshotsParallelism.set(centralSnapshotsParallelism)
            }
        }
    }
}

subprojects {
    // BOM 모듈은 java-platform 플러그인을 사용하므로 Java/Kotlin 설정을 건너뜁니다.
    if (name == "bluetape4k-exposed-bom") return@subprojects

    apply {
        plugin<JavaLibraryPlugin>()
        plugin("org.jetbrains.kotlin.jvm")
        plugin("dev.detekt")
        plugin("org.jetbrains.kotlinx.atomicfu")
        if (!isNonPublishedModule()) {
            plugin("org.jetbrains.kotlinx.kover")
            plugin("maven-publish")
            plugin("signing")
        }
        plugin("io.spring.dependency-management")
        plugin("org.jetbrains.dokka")
        plugin("com.adarshr.test-logger")
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        configureProductionAbiValidation()

        configurations.matching { it.name == "kotlinCompilerClasspath" || it.name == "kotlinCompilerPluginClasspath" }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(bt4kVersion("kotlin"))
                    because("KGP build-tools requires matching kotlin-compiler version")
                }
            }
        }
        kotlin {
            jvmToolchain(25)
            compilerOptions {
                languageVersion.set(KotlinVersion.KOTLIN_2_4)
                apiVersion.set(KotlinVersion.KOTLIN_2_4)
                jvmTarget.set(JvmTarget.JVM_25)
                freeCompilerArgs = listOf(
                    "-Xjsr305=strict",
                    "-jvm-default=enable",
                    "-Xstring-concat=indy",
                )
                val experimentalAnnotations = listOf(
                    "kotlin.RequiresOptIn",
                    "kotlin.ExperimentalStdlibApi",
                    "kotlin.contracts.ExperimentalContracts",
                    "kotlin.experimental.ExperimentalTypeInference",
                    "kotlinx.coroutines.ExperimentalCoroutinesApi",
                    "kotlinx.coroutines.InternalCoroutinesApi",
                    "kotlinx.coroutines.FlowPreview",
                    "kotlinx.coroutines.DelicateCoroutinesApi",
                )
                freeCompilerArgs.addAll(experimentalAnnotations.map { "-opt-in=$it" })
            }
        }
    }

    pluginManager.withPlugin("dev.detekt") {
        extensions.configure<DetektExtension> {
            baseline.set(project.layout.projectDirectory.file("config/detekt/baseline.xml"))
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlinx.atomicfu") {
        atomicfu {
            transformJvm = true
            jvmVariant = "VH"
        }
    }

    tasks {
        abstract class TestMutexService: BuildService<BuildServiceParameters.None>
        abstract class SigningMutexService: BuildService<BuildServiceParameters.None>

        val testMutex = gradle.sharedServices.registerIfAbsent("test-mutex", TestMutexService::class) {
            maxParallelUsages.set(1)
        }
        val signingMutex = gradle.sharedServices.registerIfAbsent("signing-mutex", SigningMutexService::class) {
            maxParallelUsages.set(1)
        }

        withType<Test>().configureEach {
            usesService(testMutex)
            inputs.property(
                "exposedTestDb",
                providers.environmentVariable("EXPOSED_TEST_DB").orElse("H2"),
            )
        }

        compileJava { options.isIncremental = true }
        compileKotlin { compilerOptions { incremental = true } }

        test {
            useJUnitPlatform()
            jvmArgs(
                "-Xshare:off",
                "-Xms2M",
                "-Xmx4G",
                "-XX:+UseG1GC",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+EnableDynamicAgentLoading",
                "--enable-preview",
                "-Didea.io.use.nio2=true"
            )
            testLogging {
                showExceptions = true
                showCauses = true
                showStackTraces = true
                events("failed")
            }
        }

        withType<Sign>().configureEach {
            usesService(signingMutex)
        }

        testlogger {
            theme = com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
        }

        withType<Detekt>().configureEach detekt@{
            // examples를 포함한 모든 정적검사에서 생성 소스만 제외한다.
            exclude("**/generated/**")
            reports {
                checkstyle.required.set(true)
                html.required.set(true)
                sarif.required.set(false)
                markdown.required.set(false)
            }
        }

        jar {
            manifest.attributes["Specification-Title"] = project.name
            manifest.attributes["Specification-Version"] = project.version
            manifest.attributes["Implementation-Title"] = project.name
            manifest.attributes["Implementation-Version"] = project.version
            manifest.attributes["Automatic-Module-Name"] = project.name.replace('-', '.')
            manifest.attributes["Created-By"] =
                "${System.getProperty("java.version")} (${System.getProperty("java.specification.vendor")})"
        }

        dokka {
            dokkaPublications.html {
                outputDirectory.set(layout.buildDirectory.asFile.get().resolve("javadoc"))
            }
            dokkaSourceSets.configureEach {
                includes.from(project.files("README.md"))
            }
        }

        clean {
            doLast {
                delete("./.project")
                delete("./out")
                delete("./bin")
            }
        }
    }

    dependencyManagement {
        setApplyMavenExclusions(false)
        imports {
            mavenBom(bt4kLibrary("bluetape4k-bom").get().toString())
            mavenBom("org.springframework.boot:spring-boot-dependencies:${bt4kVersion("spring-boot")}")
            mavenBom("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4kVersion("kotlinx-coroutines")}")
            mavenBom("org.jetbrains.kotlin:kotlin-bom:${bt4kVersion("kotlin")}")
            mavenBom(rootBt4k.junit.bom.get().toString())
            mavenBom(rootBt4k.micrometer.bom.get().toString())
            mavenBom("org.testcontainers:testcontainers-bom:${bt4kVersion("testcontainers")}")
            mavenBom("com.fasterxml.jackson:jackson-bom:${bt4kVersion("jackson")}")
            mavenBom("tools.jackson:jackson-bom:${bt4kVersion("jackson3")}")
            mavenBom(bt4kLibrary("netty-bom").get().toString())
        }
        dependencies {
            // <central-catalog-local-aliases>
            dependency("ai.timefold.solver:timefold-solver-benchmark:${bt4kVersion("timefold-solver")}")
            dependency("ai.timefold.solver:timefold-solver-core:${bt4kVersion("timefold-solver")}")
            dependency("ai.timefold.solver:timefold-solver-jackson:${bt4kVersion("timefold-solver")}")
            dependency("ai.timefold.solver:timefold-solver-spring-boot-starter:${bt4kVersion("timefold-solver")}")
            dependency("aws.sdk.kotlin:aws-config:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:aws-endpoint:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:aws-http:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:batch:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:cloudwatch:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:cloudwatchlogs:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:dynamodb:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:dynamodbstreams:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:http-client-engine-crt:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:kafka:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:kinesis:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:kms:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:lambda:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:rds:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:s3:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:ses:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:sesv2:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:sns:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:sqs:${bt4kVersion("aws-kotlin")}")
            dependency("aws.sdk.kotlin:sts:${bt4kVersion("aws-kotlin")}")
            dependency("com.esotericsoftware:reflectasm:${bt4kVersion("reflectasm")}")
            dependency("com.fasterxml.jackson.core:jackson-core:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.core:jackson-databind:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-avro:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-ion:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-properties:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-protobuf:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-smile:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-guava:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-joda:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jsr353:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.module:jackson-module-blackbird:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.module:jackson-module-jsonSchema:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.module:jackson-module-kotlin:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.module:jackson-module-parameter:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson.module:jackson-module-parameter-names:${bt4kVersion("jackson")}")
            dependency("com.fasterxml.jackson:jackson-bom:${bt4kVersion("jackson")}")
            dependency("com.google.protobuf:protobuf-java-util:${bt4kVersion("protobuf")}")
            dependency("com.google.protobuf:protobuf-kotlin:${bt4kVersion("protobuf")}")
            dependency("com.google.protobuf:protoc:${bt4kVersion("protobuf")}")
            dependency("com.hazelcast:hazelcast-spring:${bt4kVersion("hazelcast")}")
            dependency("com.sksamuel.scrimage:scrimage-filters:${bt4kVersion("scrimage")}")
            dependency("com.sksamuel.scrimage:scrimage-webp:${bt4kVersion("scrimage")}")
            dependency("io.agroal:agroal-api:${bt4kVersion("agroal")}")
            dependency("io.agroal:agroal-narayana:${bt4kVersion("agroal")}")
            dependency("io.agroal:agroal-spring-boot-starter:${bt4kVersion("agroal")}")
            dependency("io.github.benas:random-beans:${bt4kVersion("random-beans")}")
            dependency("io.lettuce:lettuce-core:${bt4kVersion("lettuce")}")
            dependency("io.netty:netty-all:${bt4kVersion("netty")}")
            dependency("io.netty:netty-buffer:${bt4kVersion("netty")}")
            dependency("io.netty:netty-codec:${bt4kVersion("netty")}")
            dependency("io.netty:netty-codec-dns:${bt4kVersion("netty")}")
            dependency("io.netty:netty-codec-protobuf:${bt4kVersion("netty")}")
            dependency("io.netty:netty-common:${bt4kVersion("netty")}")
            dependency("io.netty:netty-handler:${bt4kVersion("netty")}")
            dependency("io.netty:netty-handler-proxy:${bt4kVersion("netty")}")
            dependency("io.netty:netty-resolver:${bt4kVersion("netty")}")
            dependency("io.netty:netty-resolver-dns:${bt4kVersion("netty")}")
            dependency("io.netty:netty-resolver-dns-classes-macos:${bt4kVersion("netty")}")
            dependency("io.netty:netty-resolver-dns-native-macos:${bt4kVersion("netty")}")
            dependency("io.netty:netty-transport:${bt4kVersion("netty")}")
            dependency("io.netty:netty-transport-classes-epoll:${bt4kVersion("netty")}")
            dependency("io.netty:netty-transport-classes-kqueue:${bt4kVersion("netty")}")
            dependency("io.netty:netty-transport-native-epoll:${bt4kVersion("netty")}")
            dependency("io.netty:netty-transport-native-kqueue:${bt4kVersion("netty")}")
            dependency("io.vertx:vertx-jdbc-client:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-junit5:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-lang-kotlin:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-lang-kotlin-coroutines:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-mysql-client:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-pg-client:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-sql-client:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-sql-client-templates:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-web:${bt4kVersion("vertx")}")
            dependency("io.vertx:vertx-web-client:${bt4kVersion("vertx")}")
            dependency("org.apache.avro:avro-ipc:${bt4kVersion("avro")}")
            dependency("org.apache.avro:avro-ipc-netty:${bt4kVersion("avro")}")
            dependency("org.apache.avro:avro-protobuf:${bt4kVersion("avro")}")
            dependency("org.apache.httpcomponents.client5:httpclient5-cache:${bt4kVersion("httpclient5")}")
            dependency("org.apache.httpcomponents.client5:httpclient5-fluent:${bt4kVersion("httpclient5")}")
            dependency("org.apache.httpcomponents.client5:httpclient5-testing:${bt4kVersion("httpclient5")}")
            dependency("org.apache.httpcomponents.core5:httpcore5-testing:${bt4kVersion("httpcore5")}")
            dependency("org.apache.ignite:ignite-aop:${bt4kVersion("ignite")}")
            dependency("org.apache.ignite:ignite-aws:${bt4kVersion("ignite")}")
            dependency("org.apache.ignite:ignite-clients:${bt4kVersion("ignite")}")
            dependency("org.apache.ignite:ignite-compress:${bt4kVersion("ignite")}")
            dependency("org.apache.ignite:ignite-indexing:${bt4kVersion("ignite")}")
            dependency("org.apache.ignite:ignite-slf4j:${bt4kVersion("ignite")}")
            dependency("org.apache.ignite:ignite-spring:${bt4kVersion("ignite")}")
            dependency("org.apache.ignite:ignite-tools:${bt4kVersion("ignite")}")
            dependency("org.apache.ignite:ignite-zookeeper:${bt4kVersion("ignite")}")
            dependency("org.apache.logging.log4j:log4j-api:${bt4kVersion("log4j")}")
            dependency("org.apache.logging.log4j:log4j-jcl:${bt4kVersion("log4j")}")
            dependency("org.apache.logging.log4j:log4j-jul:${bt4kVersion("log4j")}")
            dependency("org.apache.logging.log4j:log4j-slf4j-impl:${bt4kVersion("log4j")}")
            dependency("org.apache.logging.log4j:log4j-web:${bt4kVersion("log4j")}")
            dependency("org.assertj:assertj-core:${bt4kVersion("assertj-core")}")
            dependency("org.awaitility:awaitility-kotlin:${bt4kVersion("awaitility")}")
            dependency("org.hibernate.orm:hibernate-envers:${bt4kVersion("hibernate")}")
            dependency("org.hibernate.orm:hibernate-hikaricp:${bt4kVersion("hibernate")}")
            dependency("org.hibernate.orm:hibernate-jpamodelgen:${bt4kVersion("hibernate")}")
            dependency("org.hibernate.orm:hibernate-micrometer:${bt4kVersion("hibernate")}")
            dependency("org.hibernate.orm:hibernate-spatial:${bt4kVersion("hibernate")}")
            dependency("org.hibernate.orm:hibernate-testing:${bt4kVersion("hibernate")}")
            dependency("org.jetbrains.exposed:exposed-bom:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.exposed:exposed-crypt:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.exposed:exposed-dao:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.exposed:exposed-json:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.exposed:exposed-kotlin-datetime:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.exposed:exposed-migration-core:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.exposed:exposed-migration-r2dbc:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.exposed:exposed-money:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.exposed:spring-transaction:${bt4kVersion("exposed")}")
            dependency("org.jetbrains.kotlin:kotlin-bom:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-compiler:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-compiler-embeddable:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-daemon-client:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-reflect:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-script-runtime:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-scripting-common:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-scripting-dependencies:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-scripting-jsr223:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-scripting-jvm:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-stdlib:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-stdlib-common:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-test:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-test-common:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlin:kotlin-test-junit5:${bt4kVersion("kotlin")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-debug:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-rx2:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-rx3:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-test:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:${bt4kVersion("kotlinx-coroutines")}")
            dependency("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:${bt4kVersion("kotlinx-serialization")}")
            dependency("org.jetbrains.kotlinx:kotlinx-serialization-properties:${bt4kVersion("kotlinx-serialization")}")
            dependency("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:${bt4kVersion("kotlinx-serialization")}")
            dependency("org.ow2.asm:asm-commons:${bt4kVersion("ow2-asm")}")
            dependency("org.ow2.asm:asm-tree:${bt4kVersion("ow2-asm")}")
            dependency("org.ow2.asm:asm-util:${bt4kVersion("ow2-asm")}")
            dependency("org.redisson:redisson-spring-boot-starter:${bt4kVersion("redisson")}")
            dependency("org.redisson:redisson-spring-data-34:${bt4kVersion("redisson")}")
            dependency("org.redisson:redisson-spring-data-35:${bt4kVersion("redisson")}")
            dependency("org.redisson:redisson-spring-data-40:${bt4kVersion("redisson")}")
            dependency("org.slf4j:jcl-over-slf4j:${bt4kVersion("slf4j")}")
            dependency("org.slf4j:jul-to-slf4j:${bt4kVersion("slf4j")}")
            dependency("org.slf4j:log4j-over-slf4j:${bt4kVersion("slf4j")}")
            dependency("org.slf4j:slf4j-simple:${bt4kVersion("slf4j")}")
            dependency("org.springdoc:springdoc-openapi-starter-webflux-ui:${bt4kVersion("springdoc-openapi")}")
            dependency("org.springdoc:springdoc-openapi-starter-webmvc-api:${bt4kVersion("springdoc-openapi")}")
            dependency("org.springframework.boot:spring-boot-dependencies:${bt4kVersion("spring-boot")}")
            dependency("org.testcontainers:testcontainers:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-bom:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-cassandra:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-chromadb:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-clickhouse:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-cockroachdb:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-elasticsearch:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-gcloud:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-influxdb:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-junit-jupiter:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-k3s:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-kafka:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-localstack:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-mariadb:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-minio:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-mockserver:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-mongodb:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-mysql:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-neo4j:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-nginx:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-ollama:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-oracle-xe:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-postgresql:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-pulsar:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-r2dbc:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-rabbitmq:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-redpanda:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-toxiproxy:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-trino:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-vault:${bt4kVersion("testcontainers")}")
            dependency("org.testcontainers:testcontainers-weaviate:${bt4kVersion("testcontainers")}")
            dependency("software.amazon.awssdk:apache-client:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:applicationautoscaling:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:auth:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:aws-core:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:aws-crt-client:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:cloudwatch:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:cloudwatchevents:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:cloudwatchlogs:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:dynamodb-enhanced:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:ec2:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:elasticache:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:kafka:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:kinesis:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:kms:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:lambda:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:netty-nio-client:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:s3:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:s3-transfer-manager:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:sdk-core:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:ses:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:sesv2:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:sns:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:sqs:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:sts:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:test-utils:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:url-connection-client:${bt4kVersion("aws2")}")
            dependency("software.amazon.awssdk:utils:${bt4kVersion("aws2")}")
            dependency("tools.jackson.core:jackson-core:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.core:jackson-databind:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-avro:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-cbor:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-csv:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-ion:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-properties:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-protobuf:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-smile:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-toml:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.dataformat:jackson-dataformat-yaml:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.datatype:jackson-datatype-eclipse-collections:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.datatype:jackson-datatype-guava:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.datatype:jackson-datatype-javax-money:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.datatype:jackson-datatype-json-org:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.datatype:jackson-datatype-jsr353:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.datatype:jackson-datatype-moneta:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.module:jackson-module-blackbird:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.module:jackson-module-kotlin:${bt4kVersion("jackson3")}")
            dependency("tools.jackson.module:jackson-module-no-ctor-deser:${bt4kVersion("jackson3")}")
            dependency("tools.jackson:jackson-bom:${bt4kVersion("jackson3")}")
            // </central-catalog-local-aliases>
            dependency("io.agroal:agroal-pool:${bt4kVersion("agroal")}")
            dependency("commons-codec:commons-codec:${bt4kVersion("commons-codec")}")
            dependency("org.apache.commons:commons-csv:${bt4kVersion("commons-csv")}")
            dependency("org.apache.commons:commons-exec:${bt4kVersion("commons-exec")}")
            dependency("commons-io:commons-io:${bt4kVersion("commons-io")}")
            dependency("commons-logging:commons-logging:${bt4kVersion("commons-logging")}")
            dependency("org.apache.commons:commons-pool2:${bt4kVersion("commons-pool2")}")
            dependency("org.apache.fory:fory-kotlin:${bt4kVersion("fory-kotlin")}")
            dependency("com.fasterxml.jackson.core:jackson-annotations:${bt4kVersion("jackson-annotations")}")
            dependency("com.hazelcast:hazelcast:${bt4kVersion("hazelcast")}")
            dependency("jakarta.xml.bind:jakarta.xml.bind-api:${bt4kVersion("jakarta-xml-bind")}")
            dependency("org.javamoney:moneta:${bt4kVersion("javamoney-moneta")}")
            dependency("org.mybatis.dynamic-sql:mybatis-dynamic-sql:${bt4kVersion("mybatis-dynamic-sql")}")
            dependency("com.mysql:mysql-connector-j:${bt4kVersion("mysql-connector-j")}")
            dependency("org.ow2.asm:asm:${bt4kVersion("ow2-asm")}")
            dependency("org.postgresql:postgresql:${bt4kVersion("postgresql")}")
            dependency("io.r2dbc:r2dbc-h2:${bt4kVersion("r2dbc-h2")}")
            dependency("org.redisson:redisson:${bt4kVersion("redisson")}")
            dependency("com.sksamuel.scrimage:scrimage-core:${bt4kVersion("scrimage")}")
            dependency("org.slf4j:slf4j-api:${bt4kVersion("slf4j")}")
            dependency("com.github.luben:zstd-jni:${bt4kVersion("zstd-jni")}")
            dependency("com.google.guava:guava:${bt4kVersion("guava")}")
            dependency(rootBt4k.at.yawk.lz4.java.get().toString())
        }
    }

    dependencies {
        add("api", rootBt4k.jetbrains.annotations.get())

        add("implementation", rootLibs.kotlin.stdlib.asProvider().get())
        add("implementation", rootLibs.kotlin.reflect.get())
        add("testImplementation", rootLibs.kotlin.test.asProvider().get())
        add("testImplementation", rootLibs.kotlin.test.junit5.get())

        add("implementation", rootLibs.kotlinx.coroutines.core.asProvider().get())
        add("implementation", rootBt4k.kotlinx.atomicfu.get())

        add("api", bt4kLibrary("slf4j-api").get())
        add("testImplementation", rootBt4k.logback.asProvider().get())
        add("testImplementation", rootLibs.jcl.over.slf4j.get())
        add("testImplementation", rootLibs.jul.to.slf4j.get())
        add("testImplementation", rootLibs.log4j.over.slf4j.get())

        add("testImplementation", rootBt4k.junit.jupiter.asProvider().get())
        add("testRuntimeOnly", rootBt4k.junit.platform.engine.get())

        add("testImplementation", rootLibs.awaitility.kotlin.get())
        add("testImplementation", rootBt4k.mockk.get())
    }

    if (!isNonPublishedModule()) {
        publishing {
            publications {
                create<MavenPublication>("BluetapeExposed") {
                    val sourcesJar = tasks.register<Jar>("sourcesJar") {
                        archiveClassifier.set("sources")
                        from(sourceSets["main"].allSource)
                    }
                    val javadocJar = tasks.register<Jar>("javadocJar") {
                        archiveClassifier.set("javadoc")
                        from(layout.buildDirectory.asFile.get().resolve("javadoc"))
                    }
                    from(components["java"])
                    artifact(sourcesJar)
                    artifact(javadocJar)

                    pom {
                        name.set(project.name)
                        description.set("Kotlin Exposed ORM extensions — coroutine-native, virtual-thread aware")
                        url.set("https://github.com/bluetape4k/bluetape4k-exposed")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers {
                            developer {
                                id.set("debop")
                                name.set("Sunghyouk Bae")
                                email.set("sunghyouk.bae@gmail.com")
                            }
                        }
                        scm {
                            connection.set("scm:git:git://github.com/bluetape4k/bluetape4k-exposed.git")
                            developerConnection.set("scm:git:ssh://github.com/bluetape4k/bluetape4k-exposed.git")
                            url.set("https://github.com/bluetape4k/bluetape4k-exposed")
                        }
                    }
                }
            }
            repositories {
                mavenCentral()
                maven {
                    name = "central-snapshots"
                    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                }
            }
        }

        configurePublishingSigning("BluetapeExposed")
    }
}

tasks.named<Detekt>("detekt") {
    dependsOn(subprojects
        .filterNot { it.name == "bluetape4k-exposed-bom" }
        .map { it.tasks.named("detekt") })
    doLast {
        val reports = subprojects
            .filterNot { it.name == "bluetape4k-exposed-bom" }
            .flatMap { project ->
                project.layout.buildDirectory.dir("reports/detekt").get().asFile
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "xml" && it.length() > 0L }
                    .toList()
            }
        check(reports.isNotEmpty()) {
            "Detekt aggregate completed without any non-empty subproject XML report"
        }
    }
}

val exampleProjects = subprojects.filter { project ->
    val relativePath = rootProject.rootDir.toPath()
        .relativize(project.projectDir.toPath())
        .toString()
        .replace(File.separatorChar, '/')
    relativePath.startsWith("examples/")
}

data class ExamplePatternRule(
    val name: String,
    val pattern: Regex,
)

val exampleProductionPatternRules = listOf(
    ExamplePatternRule("production println", Regex("\\bprintln\\s*\\(")),
    ExamplePatternRule("production System output", Regex("System\\.(out|err)")),
    ExamplePatternRule("production non-null assertion", Regex("!!")),
)
val exampleTestPatternRules = listOf(
    ExamplePatternRule(
        "test raw assertion",
        Regex("import\\s+(org\\.junit\\.jupiter\\.api\\.Assertions\\.assert|kotlin\\.test\\.assert)"),
    ),
)
val ktorUuidPatternRule = ExamplePatternRule("Ktor production direct UUID", Regex("UUID\\.randomUUID\\s*\\("))

tasks.register("exampleDetekt") {
    group = "verification"
    description = "모든 examples 프로젝트의 Detekt와 비어 있지 않은 XML 보고서를 검증한다."
    dependsOn(exampleProjects.map { it.tasks.named("detekt") })
    doLast {
        check(exampleProjects.isNotEmpty()) {
            "Example Detekt aggregate has no examples projects"
        }

        val reportsByProject = exampleProjects.associateWith { project ->
            project.layout.buildDirectory.dir("reports/detekt").get().asFile
                .walkTopDown()
                .filter { it.isFile && it.extension == "xml" && it.length() > 0L }
                .toList()
        }
        val missingReports = reportsByProject
            .filterValues { it.isEmpty() }
            .keys
            .map(Project::getPath)

        check(missingReports.isEmpty()) {
            "Example Detekt missing non-empty XML report for: ${missingReports.joinToString()}"
        }
        logger.lifecycle(
            "Example Detekt analyzed projects: ${reportsByProject.keys.map(Project::getPath).joinToString()}"
        )

        val productionFiles = exampleProjects.flatMap { project ->
            project.fileTree(project.projectDir) {
                include("src/main/**/*.kt")
            }.files
        }
        val testFiles = exampleProjects.flatMap { project ->
            project.fileTree(project.projectDir) {
                include("src/test/**/*.kt")
                include("src/*IntegrationTest/**/*.kt")
            }.files
        }
        val violations = buildList {
            productionFiles.forEach { source ->
                val relative = rootProject.rootDir.toPath().relativize(source.toPath()).toString()
                val rules = exampleProductionPatternRules +
                    if (relative.startsWith("examples/ktor-exposed-demo/")) {
                        listOf(ktorUuidPatternRule)
                    } else {
                        emptyList()
                    }
                rules.forEach { rule ->
                    source.readLines().forEachIndexed { index, line ->
                        if (rule.pattern.containsMatchIn(line)) {
                            add("${rule.name}: $relative:${index + 1}")
                        }
                    }
                }
            }
            testFiles.forEach { source ->
                val relative = rootProject.rootDir.toPath().relativize(source.toPath()).toString()
                exampleTestPatternRules.forEach { rule ->
                    source.readLines().forEachIndexed { index, line ->
                        if (rule.pattern.containsMatchIn(line)) {
                            add("${rule.name}: $relative:${index + 1}")
                        }
                    }
                }
            }
        }
        check(violations.isEmpty()) {
            "Example Kotlin pattern violations:\n${violations.joinToString("\n")}"
        }
        logger.lifecycle("Example pattern rules passed: production=${productionFiles.size}, tests=${testFiles.size}")
    }
}

tasks.named("detektBaseline") {
    dependsOn(subprojects
        .filterNot { it.name == "bluetape4k-exposed-bom" }
        .map { it.tasks.named("detektBaseline") })
}

extensions.configure<NmcpAggregationExtension>("nmcpAggregation") {
    centralPortal {
        username.set(centralUser)
        password.set(centralPassword)
        publishingType.set("AUTOMATIC")
        uploadSnapshotsParallelism.set(centralSnapshotsParallelism)
    }
}

val manualModuleInventory = layout.buildDirectory.file("manual/module-inventory.json")

tasks.register("exportManualModuleInventory") {
    group = "documentation"
    description = "Exports the registered Gradle project inventory for manual validation."

    val repositoryRoot = project.rootDir.toPath()
    val modules = project.subprojects.sortedBy(Project::getPath).map { module ->
        val sourceDir = repositoryRoot.relativize(module.projectDir.toPath())
            .toString().replace(File.separatorChar, '/')
        val kind = when {
            sourceDir.startsWith("examples/") -> "example"
            sourceDir.startsWith("benchmark/") -> "benchmark"
            else -> "library"
        }
        linkedMapOf(
            "gradlePath" to module.path,
            "projectName" to module.name,
            "sourceDir" to sourceDir,
            "kind" to kind,
        )
    }
    val inventoryJson = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(modules)) + "\n"
    inputs.property("inventoryJson", inventoryJson)
    outputs.file(manualModuleInventory)
    doLast {
        outputs.files.singleFile.apply {
            parentFile.mkdirs()
            writeText(inventoryJson)
        }
    }
}

val publishableProjects = subprojects.filterNot { project ->
    project.isNonPublishedModule()
}

tasks.register("checkKtorDependencyBoundary") {
    group = "verification"
    description = "Checks that selective Ktor artifacts do not pull sibling backend surfaces."

    val selectivePaths = listOf(
        ":bluetape4k-exposed-ktor-core",
        ":bluetape4k-exposed-ktor-jdbc",
        ":bluetape4k-exposed-ktor-r2dbc",
        ":bluetape4k-exposed-ktor-cache",
    )
    dependsOn(
        selectivePaths.flatMap { path ->
            val selectiveProject = project(path)
            listOf(
                selectiveProject.tasks.named("generatePomFileForBluetapeExposedPublication"),
                selectiveProject.tasks.named("generateMetadataFileForBluetapeExposedPublication"),
            )
        },
    )

    val defaultAllowlistFile = rootProject.file("scripts/verification/ktor-dependency-allowlist.json")
    val allowlistFile = System.getenv("KTOR_DEPENDENCY_ALLOWLIST_FILE")
        ?.takeIf { it.isNotBlank() }
        ?.let(rootProject::file)
        ?: defaultAllowlistFile
    inputs.file(allowlistFile)

    doLast {
        val exposedGroup = projectGroup
        check(allowlistFile.isFile) {
            "Ktor dependency allowlist is missing: ${allowlistFile.absolutePath}"
        }
        val policy = groovy.json.JsonSlurper().parse(allowlistFile) as? Map<*, *>
            ?: error("Ktor dependency allowlist root must be an object")
        val aliases = (policy["aliases"] as? Map<*, *> ?: emptyMap<Any?, Any?>())
            .mapNotNull { (raw, canonical) ->
                val rawCoordinate = raw?.toString()?.trim()
                val canonicalCoordinate = canonical?.toString()?.trim()
                if (rawCoordinate.isNullOrBlank() || canonicalCoordinate.isNullOrBlank()) {
                    null
                } else {
                    rawCoordinate to canonicalCoordinate
                }
            }
            .toMap()
        val commonCoordinates = (policy["common"] as? List<*>)
            ?.map { it.toString().trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: error("Ktor dependency allowlist common coordinates are missing")
        val moduleCoordinates = (policy["modules"] as? Map<*, *>)
            ?.mapNotNull { (module, coordinates) ->
                val moduleName = module?.toString()?.trim()
                val moduleAllowlist = (coordinates as? List<*>)
                    ?.map { it.toString().trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                if (moduleName.isNullOrBlank() || moduleAllowlist == null) {
                    null
                } else {
                    moduleName to moduleAllowlist
                }
            }
            ?.toMap()
            ?: error("Ktor dependency allowlist modules are missing")
        val moduleNameByPath = linkedMapOf(
            ":bluetape4k-exposed-ktor-core" to "core",
            ":bluetape4k-exposed-ktor-jdbc" to "jdbc",
            ":bluetape4k-exposed-ktor-r2dbc" to "r2dbc",
            ":bluetape4k-exposed-ktor-cache" to "cache",
        )
        check(moduleNameByPath.values.all { it in moduleCoordinates }) {
            "Ktor dependency allowlist is missing a selective module definition"
        }
        val allowedByModule = moduleNameByPath.mapValues { (_, moduleName) ->
            commonCoordinates + moduleCoordinates.getValue(moduleName)
        }
        val selectiveProjects = moduleNameByPath.keys.associateWith { project(it) }
        val sourceGraph = linkedMapOf<String, MutableList<String>>()
        val resolvedGraph = linkedMapOf<String, MutableList<String>>()
        val violations = buildList {
            selectiveProjects.forEach { (path, selectiveProject) ->
                val allowedCoordinates = allowedByModule.getValue(path)
                fun checkCoordinate(location: String, coordinate: String?) {
                    if (coordinate == null) return
                    val canonicalCoordinate = aliases[coordinate] ?: coordinate
                    if (canonicalCoordinate !in allowedCoordinates) {
                        add(
                            "$path:$location -> unallowlisted coordinate=$coordinate " +
                                "(canonical=$canonicalCoordinate)",
                        )
                    }
                }
                listOf("api", "implementation", "compileOnly", "runtimeOnly").forEach { configurationName ->
                    selectiveProject.configurations.findByName(configurationName)
                        ?.dependencies
                        ?.forEach { dependency ->
                            val dependencyProject = dependency as? org.gradle.api.artifacts.ProjectDependency
                            val coordinate = if (dependencyProject != null) {
                                "$exposedGroup:${dependencyProject.path.substringAfterLast(':')}"
                            } else {
                                dependency.group?.let { "$it:${dependency.name}" }
                            }
                            check(coordinate != null) {
                                "$path:$configurationName dependency must declare a fully-qualified group:name coordinate"
                            }
                            sourceGraph.getOrPut(path) { mutableListOf() } += "$configurationName:$coordinate"
                            checkCoordinate(configurationName, coordinate)
                        }
                }
                listOf("compileClasspath", "runtimeClasspath").forEach { configurationName ->
                    selectiveProject.configurations.findByName(configurationName)
                        ?.takeIf { it.isCanBeResolved }
                        ?.incoming
                        ?.resolutionResult
                        ?.allComponents
                        ?.forEach { component ->
                            val projectPath = (component.id as?
                                org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath
                            val coordinate = component.moduleVersion?.let { "${it.group}:${it.name}" }
                                ?: projectPath?.let { "$exposedGroup:${it.substringAfterLast(':')}" }
                            check(coordinate != null) {
                                "$path:$configurationName resolved component must expose a fully-qualified group:name coordinate"
                            }
                            resolvedGraph.getOrPut(path) { mutableListOf() } += "$configurationName:$coordinate"
                            checkCoordinate(configurationName, coordinate)
                        }
                }

                val publicationDir = selectiveProject.layout.buildDirectory
                    .dir("publications/BluetapeExposed").get().asFile
                val pom = publicationDir.resolve("pom-default.xml")
                val metadata = publicationDir.resolve("module.json")
                check(pom.isFile && metadata.isFile) {
                    "$path publication metadata is missing: ${pom.absolutePath}, ${metadata.absolutePath}"
                }
                val pomDocument = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(pom)
                fun isElementNamed(element: org.w3c.dom.Element, name: String): Boolean =
                    element.localName == name || element.nodeName.substringAfter(':') == name
                fun directChild(element: org.w3c.dom.Element, name: String): org.w3c.dom.Element? =
                    (0 until element.childNodes.length)
                        .asSequence()
                        .map { element.childNodes.item(it) }
                        .filterIsInstance<org.w3c.dom.Element>()
                        .firstOrNull { isElementNamed(it, name) }
                fun directChildText(element: org.w3c.dom.Element, name: String): String? =
                    directChild(element, name)?.textContent?.trim()?.takeIf { it.isNotBlank() }
                val pomDependencies = directChild(pomDocument.documentElement, "dependencies")
                    ?.let { dependenciesElement ->
                        (0 until dependenciesElement.childNodes.length)
                            .asSequence()
                            .map { dependenciesElement.childNodes.item(it) }
                            .filterIsInstance<org.w3c.dom.Element>()
                            .filter { isElementNamed(it, "dependency") }
                            .map { dependency ->
                                val groupId = directChildText(dependency, "groupId")
                                    ?: error("$path published POM dependency is missing groupId")
                                val artifactId = directChildText(dependency, "artifactId")
                                    ?: error("$path published POM dependency is missing artifactId")
                                "$groupId:$artifactId"
                            }
                            .toSet()
                    }
                    ?: emptySet()
                val metadataRoot = groovy.json.JsonSlurper().parse(metadata) as? Map<*, *>
                    ?: error("$path Gradle metadata root must be an object")
                val variants = metadataRoot["variants"] as? List<*>
                    ?: error("$path Gradle metadata variants are missing")
                val metadataCoordinates = variants.flatMap { variant ->
                    val variantMap = variant as? Map<*, *>
                        ?: error("$path Gradle metadata variant must be an object")
                    listOf("dependencies", "dependencyConstraints").flatMap { dependencyKey ->
                        val dependencies = when (val value = variantMap[dependencyKey]) {
                            null -> emptyList<Any?>()
                            is List<*> -> value
                            else -> error("$path Gradle metadata $dependencyKey must be an array")
                        }
                        dependencies.map { dependency ->
                            val dependencyMap = dependency as? Map<*, *>
                                ?: error("$path Gradle metadata $dependencyKey entry must be an object")
                            val groupId = dependencyMap["group"]?.toString()?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?: error("$path Gradle metadata $dependencyKey entry is missing group")
                            val moduleId = dependencyMap["module"]?.toString()?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?: error("$path Gradle metadata $dependencyKey entry is missing module")
                            "$groupId:$moduleId"
                        }
                    }
                }.toSet()
                pomDependencies.forEach { coordinate -> checkCoordinate("publishedPom", coordinate) }
                metadataCoordinates.forEach { coordinate -> checkCoordinate("publishedGradleMetadata", coordinate) }
                sourceGraph.getOrPut("$path:publishedPom") { mutableListOf() }
                    .addAll(pomDependencies.sorted())
                sourceGraph.getOrPut("$path:publishedGradleMetadata") { mutableListOf() }
                    .addAll(metadataCoordinates.sorted())
            }
        }
        check(violations.isEmpty()) {
            "Selective Ktor dependency boundary violations:\n${violations.joinToString("\n")}"
        }
        val receipt = linkedMapOf(
            "schema" to 1,
            "selectiveArtifacts" to selectiveProjects.keys.sorted(),
            "allowlist" to linkedMapOf(
                "schema" to policy["schema"],
                "file" to rootProject.projectDir.toPath().relativize(allowlistFile.toPath())
                    .toString().replace(File.separatorChar, '/'),
                "commonCount" to commonCoordinates.size,
                "moduleCoordinateCounts" to allowedByModule.mapValues { it.value.size },
            ),
            "sourceGraph" to sourceGraph.mapValues { it.value.distinct().sorted() },
            "resolvedGraph" to resolvedGraph.mapValues { it.value.distinct().sorted() },
            "publishedMetadata" to selectiveProjects.keys.sorted().associateWith { path ->
                val project = project(path)
                val publicationDir = project.layout.buildDirectory.dir("publications/BluetapeExposed").get().asFile
                linkedMapOf(
                    "pom" to rootProject.projectDir.toPath().relativize(publicationDir.resolve("pom-default.xml").toPath())
                        .toString().replace(File.separatorChar, '/'),
                    "module" to rootProject.projectDir.toPath().relativize(publicationDir.resolve("module.json").toPath())
                        .toString().replace(File.separatorChar, '/'),
                )
            },
            "consumerFixture" to linkedMapOf(
                "status" to "PENDING",
                "mode" to "external-published-consumer-required",
                "receipt" to "build/verification/ktor-consumer-boundary.json",
            ),
        )
        val dependencyReceipt = layout.buildDirectory.file("verification/ktor-dependency-boundary.json").get().asFile
        dependencyReceipt.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(receipt)) + "\n")
        }
        val consumerFixtureScript = rootProject.file("scripts/verification/validate_ktor_consumer.rb")
        check(consumerFixtureScript.isFile) {
            "Ktor consumer fixture validator is missing: ${consumerFixtureScript.absolutePath}"
        }
        val consumerProcess = ProcessBuilder(
            "ruby",
            consumerFixtureScript.absolutePath,
            project.version.toString(),
        ).directory(rootProject.projectDir).inheritIO().start()
        check(consumerProcess.waitFor() == 0) {
            "Ktor external consumer fixture failed with exit code ${consumerProcess.exitValue()}"
        }
        receipt["consumerFixture"] = linkedMapOf(
            "status" to "PASS",
            "mode" to "external-published-consumer",
            "receipt" to "build/verification/ktor-consumer-boundary.json",
        )
        dependencyReceipt.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(receipt)) + "\n")
        logger.lifecycle("Ktor dependency boundary passed: selectiveArtifacts=${selectiveProjects.size}")
    }
}

val publicationInventory = layout.buildDirectory.file("publication/publication-inventory.json")
val publicationInventoryEntries = publishableProjects
    .sortedBy(Project::getPath)
    .map { publishableProject ->
        val sourceDir = rootProject.rootDir.toPath()
            .relativize(publishableProject.projectDir.toPath())
            .toString()
            .replace(File.separatorChar, '/')
        linkedMapOf(
            "gradlePath" to publishableProject.path,
            "projectName" to publishableProject.name,
            "sourceDir" to sourceDir,
            "metadataPath" to "$sourceDir/build/publications/BluetapeExposed/module.json",
            "pomPath" to "$sourceDir/build/publications/BluetapeExposed/pom-default.xml",
        )
    }

tasks.register("exportPublicationInventory") {
    group = "verification"
    description = "Exports the publishable project registry for publication validation."

    val inventoryJson = groovy.json.JsonOutput.prettyPrint(
        groovy.json.JsonOutput.toJson(linkedMapOf("publications" to publicationInventoryEntries)),
    ) + "\n"
    inputs.property("publicationInventory", inventoryJson)
    outputs.file(publicationInventory)
    doLast {
        publicationInventory.get().asFile.apply {
            parentFile.mkdirs()
            writeText(inventoryJson)
        }
    }
}

val productionAbiProjects = publishableProjects
    .filterNot { it.name == "bluetape4k-exposed-bom" }
    .sortedBy(Project::getPath)

check(productionAbiProjects.size == 42) {
    "Production ABI publication inventory must contain 42 JVM modules, found ${productionAbiProjects.size}"
}

val productionAbiCheckTasks = productionAbiProjects.map { project ->
    project.tasks.named("checkKotlinAbi")
}
val productionAbiUpdateTasks = productionAbiProjects.map { project ->
    project.tasks.named("updateKotlinAbi")
}
val productionAbiReport = layout.buildDirectory.file("abi/reports/production-abi.txt")

tasks.register("checkProductionAbi") {
    group = "verification"
    description = "Checks the fail-closed ABI baseline for every published JVM module."
    dependsOn(productionAbiCheckTasks)
    doLast {
        val expectedProjects = productionAbiProjects.map(Project::getName).toSet()
        val baselineFiles = rootProject.layout.projectDirectory.dir("api").asFile
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "api" }
        val baselineProjects = baselineFiles
            .map { it.name.removeSuffix(".api") }
            .toSet()
        val emptyBaselineProjects = baselineFiles
            .filter { it.length() == 0L }
            .map { it.name.removeSuffix(".api") }
            .toSet()
        val actualProjects = productionAbiProjects
            .map { project ->
                project.layout.buildDirectory.file("kotlin/abi/${project.name}.api").get().asFile
            }
            .filter { it.isFile && it.length() > 0L }
            .map { it.name.removeSuffix(".api") }
            .toSet()

        val result = validateProductionAbiInventory(
            expectedProjects = expectedProjects,
            baselineProjects = baselineProjects,
            actualProjects = actualProjects,
            emptyBaselineProjects = emptyBaselineProjects,
        )
        result.requireValid()

        productionAbiReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("modules=${expectedProjects.size}/${expectedProjects.size}")
                    appendLine("baselines=${baselineProjects.size}/${expectedProjects.size}")
                    appendLine("actualDumps=${actualProjects.size}/${expectedProjects.size}")
                    appendLine("orphanBaselines=${result.orphanBaselines.size}")
                    appendLine("orphanActuals=${result.orphanActuals.size}")
                    appendLine("emptyBaselines=${result.emptyBaselineProjects.size}")
                    expectedProjects.sorted().forEach { appendLine(it) }
                },
            )
        }
    }
}

tasks.register("updateProductionAbiBaseline") {
    group = "verification"
    description = "Manually bootstraps or updates the central production ABI baseline."
    dependsOn(productionAbiUpdateTasks)
    doLast {
        val baselineFiles = rootProject.layout.projectDirectory.dir("api").asFile
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "api" && it.length() > 0L }
        check(baselineFiles.size == productionAbiProjects.size) {
            "Production ABI baseline must contain ${productionAbiProjects.size} non-empty files, " +
                "found ${baselineFiles.size}"
        }
    }
}

tasks.register("publishPublicationValidation") {
    group = "verification"
    description = "Publishes every registered publication to the configured Maven local repository."
    dependsOn(
        publishableProjects.map {
            it.tasks.named("publishBluetapeExposedPublicationToMavenLocal")
        },
    )
}

dependencies {
    publishableProjects.forEach { publishableProject ->
        add("nmcpAggregation", project(mapOf("path" to publishableProject.path)))
    }
}

dependencies {
    subprojects
        .filterNot { sub -> sub.name == "bluetape4k-exposed-bom" }
        .filter { it.plugins.hasPlugin("org.jetbrains.kotlinx.kover") }
        .forEach { sub -> add("kover", project(mapOf("path" to sub.path))) }
}
