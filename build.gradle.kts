import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import nmcp.NmcpAggregationExtension
import nmcp.NmcpExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    base
    `maven-publish`
    signing
    alias(libs.plugins.kotlin.jvm)

    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.noarg) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.atomicfu)

    alias(libs.plugins.detekt)
    alias(libs.plugins.dependency.management)

    alias(libs.plugins.dokka)
    alias(libs.plugins.test.logger)

    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.nmcp) apply false

    alias(libs.plugins.kover)
    alias(bt4k.plugins.exposed.plugin) apply false
}

val rootLibs = libs
val bt4kCatalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("bt4k")
fun bt4kVersion(alias: String): String {
    val version = bt4kCatalog.findVersion(alias).get()
    return version.requiredVersion
        .ifBlank { version.preferredVersion }
        .ifBlank { version.strictVersion }
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

val projectGroup: String by project
val baseVersion: String by project
val snapshotVersion: String by project
val bluetape4kVersion: String by project

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
}

subprojects {
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
        configurations.matching { it.name == "kotlinCompilerClasspath" || it.name == "kotlinCompilerPluginClasspath" }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(rootLibs.versions.kotlin.get())
                    because("KGP build-tools requires matching kotlin-compiler version")
                }
            }
        }
        kotlin {
            jvmToolchain(21)
            compilerOptions {
                languageVersion.set(KotlinVersion.KOTLIN_2_3)
                apiVersion.set(KotlinVersion.KOTLIN_2_3)
                freeCompilerArgs = listOf(
                    "-Xjsr305=strict",
                    "-jvm-default=enable",
                    "-Xstring-concat=indy",
                    "-Xcontext-parameters",
                    "-Xannotation-default-target=param-property"
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

        compileJava { options.isIncremental = true }
        compileKotlin { compilerOptions { incremental = true } }

        test {
            usesService(testMutex)
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

        val reportMerge by registering(ReportMergeTask::class) {
            val file = rootProject.layout.buildDirectory.asFile.get().resolve("reports/detekt/merged.xml")
            output.set(file)
        }
        withType<Detekt>().configureEach detekt@{
            finalizedBy(reportMerge)
            reportMerge.configure { input.from(this@detekt.xmlReportFile) }
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
            mavenBom(rootLibs.bluetape4k.bom.get().toString())
            mavenBom(rootLibs.spring.boot.dependencies.get().toString())
            mavenBom(rootLibs.kotlinx.coroutines.bom.get().toString())
            mavenBom(rootLibs.kotlin.bom.get().toString())
            mavenBom(rootLibs.junit.bom.get().toString())
            mavenBom(rootLibs.micrometer.bom.get().toString())
            mavenBom(rootLibs.testcontainers.bom.get().toString())
            mavenBom(rootLibs.jackson.bom.get().toString())
            mavenBom(rootLibs.jackson3.bom.get().toString())
            mavenBom(rootLibs.netty.bom.get().toString())
        }
        dependencies {
            dependency("io.agroal:agroal-pool:${bt4kVersion("agroal")}")
            dependency("commons-codec:commons-codec:${bt4kVersion("commons-codec")}")
            dependency("org.apache.commons:commons-csv:${bt4kVersion("commons-csv")}")
            dependency("org.apache.commons:commons-exec:${bt4kVersion("commons-exec")}")
            dependency("commons-io:commons-io:${bt4kVersion("commons-io")}")
            dependency("commons-logging:commons-logging:${bt4kVersion("commons-logging")}")
            dependency("org.apache.commons:commons-pool2:${bt4kVersion("commons-pool2")}")
            dependency("org.apache.fory:fory-kotlin:${bt4kVersion("fory-kotlin")}")
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
            dependency(rootLibs.lz4.java.get().toString())
        }
    }

    dependencies {
        val api by configurations
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations

        api(rootLibs.jetbrains.annotations)

        implementation(rootLibs.kotlin.stdlib)
        implementation(rootLibs.kotlin.reflect)
        testImplementation(rootLibs.kotlin.test)
        testImplementation(rootLibs.kotlin.test.junit5)

        implementation(rootLibs.kotlinx.coroutines.core)
        implementation(rootLibs.kotlinx.atomicfu)

        api(rootLibs.slf4j.api)
        testImplementation(rootLibs.logback.classic)
        testImplementation(rootLibs.jcl.over.slf4j)
        testImplementation(rootLibs.jul.to.slf4j)
        testImplementation(rootLibs.log4j.over.slf4j)

        testImplementation(rootLibs.junit.jupiter)
        testRuntimeOnly(rootLibs.junit.platform.engine)

        testImplementation(rootLibs.awaitility.kotlin)
        testImplementation(rootLibs.mockk)
    }

    if (!isNonPublishedModule()) {
        publishing {
            publications {
                create<MavenPublication>("BluetapeExposed") {
                    val sourcesJar by tasks.registering(Jar::class) {
                        archiveClassifier.set("sources")
                        from(sourceSets["main"].allSource)
                    }
                    val javadocJar by tasks.registering(Jar::class) {
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

extensions.configure<NmcpAggregationExtension>("nmcpAggregation") {
    centralPortal {
        username.set(centralUser)
        password.set(centralPassword)
        publishingType.set("AUTOMATIC")
        uploadSnapshotsParallelism.set(centralSnapshotsParallelism)
    }
}

val publishableProjects = subprojects.filterNot { project ->
    project.isNonPublishedModule()
}

dependencies {
    publishableProjects.forEach { publishableProject ->
        add("nmcpAggregation", project(publishableProject.path))
    }
}

dependencies {
    subprojects
        .filterNot { sub -> sub.name == "bluetape4k-exposed-bom" }
        .filter { it.plugins.hasPlugin("org.jetbrains.kotlinx.kover") }
        .forEach { sub -> kover(project(sub.path)) }
}
