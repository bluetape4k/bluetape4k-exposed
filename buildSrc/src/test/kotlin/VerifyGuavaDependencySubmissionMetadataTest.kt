import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyGuavaDependencySubmissionMetadataTest {
    @Test
    fun `detached dependency submission graphs use the central Guava coordinate`() {
        val repositoryRoot = findRepositoryRoot()
        val expectedParents = setOf(
            "com.clickhouse:clickhouse-jdbc:0.9.9",
            "com.google.api-client:google-api-client:2.9.2",
            "com.google.http-client:google-http-client:2.0.0",
            "com.google.auth:google-auth-library-oauth2-http:1.30.0",
            "com.google.oauth-client:google-oauth-client:1.36.0",
            "io.opencensus:opencensus-contrib-http-util:0.31.1",
            "com.datastax.cassandra:cassandra-driver-core:3.10.0",
        )
        val initScript = createTempFile("verify-guava-dependency-submission", ".gradle").toFile()
        initScript.writeText(
            """
            gradle.beforeProject { rootProject ->
                if (rootProject.name != "bluetape4k-exposed" || rootProject.path != ":") return

                rootProject.tasks.register("probeGuavaGraph") {
                    doLast {
                        [
                            "com.clickhouse:clickhouse-jdbc:0.9.9",
                            "com.google.api-client:google-api-client:2.9.2",
                            "com.google.http-client:google-http-client:2.0.0",
                            "com.google.auth:google-auth-library-oauth2-http:1.30.0",
                            "com.google.oauth-client:google-oauth-client:1.36.0",
                            "io.opencensus:opencensus-contrib-http-util:0.31.1",
                            "com.datastax.cassandra:cassandra-driver-core:3.10.0",
                        ].each { notation ->
                            def configuration = rootProject.configurations.detachedConfiguration(
                                rootProject.dependencies.create(notation),
                            )
                            configuration.incoming.resolutionResult.allDependencies.each { dependency ->
                                def requested = dependency.requested
                                if (requested.group == "com.google.guava" && requested.module == "guava") {
                                    def selected = dependency.hasProperty("selected") ? dependency.selected.id.version : "UNRESOLVED"
                                    println("PROBE-GUAVA parent=${'$'}{notation} requested=${'$'}{requested.version} selected=${'$'}{selected}")
                                }
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        try {
            val result = GradleRunner.create()
                .withProjectDir(repositoryRoot)
                .withArguments(
                    "--no-configuration-cache",
                    "--console=plain",
                    "--init-script",
                    initScript.absolutePath,
                    "probeGuavaGraph",
                )
                .build()

            val probeLines = result.output.lineSequence()
                .filter { it.startsWith("PROBE-GUAVA") }
                .toList()
            val observedParents = probeLines.mapNotNull {
                Regex("parent=([^ ]+)").find(it)?.groupValues?.get(1)
            }.toSet()
            assertEquals(
                expectedParents,
                observedParents,
                "The detached graph did not probe exactly the expected alert parent edges:\n${probeLines.joinToString("\n")}",
            )
            assertTrue(
                probeLines.all { "requested=33.7.2-jre selected=33.7.2-jre" in it },
                "Detached dependency graph used a non-central Guava coordinate:\n${probeLines.joinToString("\n")}",
            )
        } finally {
            initScript.delete()
        }
    }

    private fun findRepositoryRoot(): File {
        val start = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(start) { it.parentFile }
            .first { candidate ->
                File(candidate, "settings.gradle.kts").isFile &&
                    File(candidate, "buildSrc").isDirectory
            }
    }
}
