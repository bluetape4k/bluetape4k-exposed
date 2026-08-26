package io.bluetape4k.exposed.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.should

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.invoke.MethodType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ExposedKtorAbiCompatibilityTest {

    @Test
    fun `compiled production output retains old and new JVM descriptors`() {
        val productionLocation = Path.of(
            requireNotNull(Bluetape4kExposedKtorConfig::class.java.protectionDomain.codeSource) {
                "Bluetape4kExposedKtorConfig has no code source; cannot locate compiled production output."
            }.location.toURI()
        ).toAbsolutePath().normalize()
        val inspections = EXPECTED_MEMBERS
            .map(AbiMember::owner)
            .distinct()
            .associateWith { owner -> inspectClass(owner, productionLocation) }
        val mismatches = EXPECTED_MEMBERS.mapNotNull { expected ->
            val actual = inspections.getValue(expected.owner).descriptors[expected.jvmName].orEmpty()
            if (expected.descriptor in actual) {
                null
            } else {
                buildString {
                    append(expected.owner)
                    append('.')
                    append(expected.jvmName)
                    append(" expected ")
                    append(expected.descriptor)
                    append(" but found ")
                    append(actual.ifEmpty { setOf("<member not found>") })
                }
            }
        }

        val diagnostics = buildString {
            appendLine("Ktor ABI descriptors changed in compiled production output: $productionLocation")
            mismatches.forEach { appendLine("- $it") }
            inspections.values.forEach { inspection ->
                appendLine()
                appendLine("Inspection for ${inspection.owner} via ${inspection.backend}:")
                appendLine(inspection.diagnostics)
            }
        }
        (mismatches.isEmpty()).should(diagnostics) { it }
    }

    @Test
    fun `reflection fallback rejects classes outside production output`() {
        val productionLocation = Path.of(
            requireNotNull(Bluetape4kExposedKtorConfig::class.java.protectionDomain.codeSource).location.toURI()
        ).toAbsolutePath().normalize()
        val shadowedLocation = Path.of(
            requireNotNull(Test::class.java.protectionDomain.codeSource).location.toURI()
        ).toAbsolutePath().normalize()

        val error = assertFailsWith<IllegalStateException> {
            inspectWithReflection(Test::class.java.name, productionLocation, "forced fallback")
        }
        (error.message?.contains("expected: $productionLocation") == true)
            .should("fallback diagnostics did not include the expected production location") { it }
        (error.message?.contains("actual: $shadowedLocation") == true)
            .should("fallback diagnostics did not include the actual shadowed location") { it }
    }

    @Test
    fun `timed process is terminated before diagnostics are read`() {
        val shell = Path.of("/bin/sh")
        assumeTrue(Files.isExecutable(shell), "timeout regression probe requires /bin/sh")

        val execution = executeProcess(
            command = listOf(shell.toString(), "-c", "printf 'timeout-probe\\n'; while :; do :; done"),
            timeout = 100,
            timeoutUnit = TimeUnit.MILLISECONDS,
        )

        execution.timedOut.should("process exceeded its timeout but was reported as completed") { it }
        (execution.output.contains("timeout-probe")).should("timeout diagnostics did not retain process output") { it }
        (Files.notExists(execution.outputFile)).should("temporary process output was not deleted") { it }
    }

    private fun inspectClass(owner: String, productionLocation: Path): AbiInspection =
        try {
            inspectWithJavap(owner, productionLocation)
        } catch (e: IOException) {
            inspectWithReflection(owner, productionLocation, "javap could not be executed: ${e.message}")
        } catch (e: SecurityException) {
            inspectWithReflection(owner, productionLocation, "javap execution was denied: ${e.message}")
        }

    private fun inspectWithJavap(owner: String, productionLocation: Path): AbiInspection {
        val javap = resolveJavap()
        val command = listOf(
            javap,
            "-s",
            "-p",
            "-classpath",
            productionLocation.toString(),
            owner,
        )
        val execution = executeProcess(command, 30, TimeUnit.SECONDS)
        if (execution.timedOut) {
            return inspectWithReflection(
                owner,
                productionLocation,
                buildString {
                    appendLine("javap timed out after 30 seconds: ${command.joinToString(" ")}")
                    appendLine("captured output:")
                    append(execution.output)
                },
            )
        }
        val exitCode = requireNotNull(execution.exitCode)
        val descriptors = if (exitCode == 0) parseJavapDescriptors(owner, execution.output) else emptyMap()
        return AbiInspection(
            owner = owner,
            backend = "javap -s -p (exit $exitCode)",
            descriptors = descriptors,
            diagnostics = buildString {
                appendLine("command: ${command.joinToString(" ")}")
                appendLine("production location: $productionLocation")
                append(execution.output)
            },
        )
    }

    private fun executeProcess(
        command: List<String>,
        timeout: Long,
        timeoutUnit: TimeUnit,
    ): ProcessExecution {
        val outputFile = Files.createTempFile("exposed-ktor-abi-", ".log")
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start()
            val completed = process.waitFor(timeout, timeoutUnit)
            if (!completed) {
                process.destroyForcibly()
                check(process.waitFor(10, TimeUnit.SECONDS)) {
                    "Timed out process did not terminate after destroyForcibly: ${command.joinToString(" ")}"
                }
            }
            val output = Files.readString(outputFile)
            ProcessExecution(
                timedOut = !completed,
                exitCode = if (completed) process.exitValue() else null,
                output = output,
                outputFile = outputFile,
            )
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }

    private fun resolveJavap(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "javap.exe"
        } else {
            "javap"
        }
        val javaHomeJavap = Path.of(System.getProperty("java.home"), "bin", executable)
        return if (Files.isExecutable(javaHomeJavap)) javaHomeJavap.toString() else executable
    }

    private fun parseJavapDescriptors(owner: String, output: String): Map<String, Set<String>> {
        val descriptors = linkedMapOf<String, MutableSet<String>>()
        var currentMember: String? = null
        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("descriptor:") -> {
                    currentMember?.let { member ->
                        descriptors.getOrPut(member) { linkedSetOf() }
                            .add(trimmed.substringAfter("descriptor:").trim())
                    }
                    currentMember = null
                }

                trimmed.endsWith(';') && '(' in trimmed -> {
                    val declaredName = trimmed.substringBefore('(').substringAfterLast(' ')
                    currentMember = if (declaredName == owner) "<init>" else declaredName
                }

                trimmed.isNotEmpty() -> currentMember = null
            }
        }
        return descriptors
    }

    private fun inspectWithReflection(
        owner: String,
        expectedProductionLocation: Path,
        javapFailure: String,
    ): AbiInspection {
        val type = Class.forName(owner)
        val actualProductionLocation = type.protectionDomain.codeSource?.location
            ?.toURI()
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
        check(actualProductionLocation == expectedProductionLocation) {
            "Reflection fallback rejected $owner from an unexpected code source; " +
                "expected: $expectedProductionLocation; actual: ${actualProductionLocation ?: "<unavailable>"}"
        }
        val descriptors = linkedMapOf<String, MutableSet<String>>()
        type.declaredConstructors.forEach { constructor ->
            descriptors.getOrPut("<init>") { linkedSetOf() }
                .add(MethodType.methodType(Void.TYPE, constructor.parameterTypes.toList()).toMethodDescriptorString())
        }
        type.declaredMethods.forEach { method ->
            descriptors.getOrPut(method.name) { linkedSetOf() }
                .add(MethodType.methodType(method.returnType, method.parameterTypes.toList()).toMethodDescriptorString())
        }
        return AbiInspection(
            owner = owner,
            backend = "reflection MethodType fallback",
            descriptors = descriptors,
            diagnostics = buildString {
                appendLine(javapFailure)
                appendLine("loaded class code source: ${type.protectionDomain.codeSource?.location}")
                descriptors.forEach { (member, values) -> appendLine("$member: $values") }
            },
        )
    }

    private data class AbiMember(
        val owner: String,
        val jvmName: String,
        val descriptor: String,
    )

    private data class AbiInspection(
        val owner: String,
        val backend: String,
        val descriptors: Map<String, Set<String>>,
        val diagnostics: String,
    )

    private data class ProcessExecution(
        val timedOut: Boolean,
        val exitCode: Int?,
        val output: String,
        val outputFile: Path,
    )

    companion object {
        private const val PACKAGE_NAME = "io.bluetape4k.exposed.ktor"
        private const val CONFIG_CLASS = "$PACKAGE_NAME.Bluetape4kExposedKtorConfig"
        private const val INSTALLER_CLASS = "$PACKAGE_NAME.Bluetape4kExposedKtorKt"
        private const val ROUTES_CLASS = "$PACKAGE_NAME.ExposedKtorHealthRoutesKt"

        private val EXPECTED_MEMBERS = listOf(
            AbiMember(
                CONFIG_CLASS,
                "<init>",
                "(Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;" +
                    "Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;ZZLjava/lang/String;Ljava/lang/String;JJ" +
                    "Lio/micrometer/core/instrument/MeterRegistry;)V",
            ),
            AbiMember(
                CONFIG_CLASS,
                "<init>",
                "(Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;" +
                    "Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;ZZLjava/lang/String;Ljava/lang/String;JJ" +
                    "Lio/micrometer/core/instrument/MeterRegistry;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
            ),
            AbiMember(
                INSTALLER_CLASS,
                "installBluetape4kExposedKtor",
                "(Lio/ktor/server/application/Application;" +
                    "Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;)V",
            ),
            AbiMember(
                INSTALLER_CLASS,
                "installBluetape4kExposedKtor\$default",
                "(Lio/ktor/server/application/Application;" +
                    "Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;ILjava/lang/Object;)V",
            ),
            AbiMember(
                INSTALLER_CLASS,
                "installBluetape4kExposedKtor",
                "(Lio/ktor/server/application/Application;" +
                    "Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;" +
                    "Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;)V",
            ),
            AbiMember(
                ROUTES_CLASS,
                "bluetape4kExposedHealthRoutes-021xcDE",
                "(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;" +
                    "Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;" +
                    "Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;)V",
            ),
            AbiMember(
                ROUTES_CLASS,
                "bluetape4kExposedHealthRoutes-021xcDE\$default",
                "(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;" +
                    "Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;" +
                    "Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;" +
                    "ILjava/lang/Object;)V",
            ),
            AbiMember(
                ROUTES_CLASS,
                "bluetape4kExposedHealthRoutes-PLKeYGg",
                "(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;" +
                    "Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;" +
                    "Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;" +
                    "Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;)V",
            ),
            AbiMember(
                ROUTES_CLASS,
                "bluetape4kExposedHealthRoutes-PLKeYGg\$default",
                "(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;" +
                    "Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;" +
                    "Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;" +
                    "Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;ILjava/lang/Object;)V",
            ),
        )
    }
}

@Suppress("unused")
private fun Application.compileInstallerSourceCalls(
    config: Bluetape4kExposedKtorConfig,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
) {
    installBluetape4kExposedKtor()
    installBluetape4kExposedKtor(config)
    installBluetape4kExposedKtor(config = config, cacheReadiness = cacheReadiness)
}

@Suppress("unused")
private fun Route.compileRouteSourceCalls(
    jdbcDatabase: Database?,
    jdbcBlockingDispatcher: CoroutineDispatcher?,
    r2dbcDatabase: R2dbcDatabase?,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
) {
    bluetape4kExposedHealthRoutes(jdbcDatabase, jdbcBlockingDispatcher, r2dbcDatabase)
    bluetape4kExposedHealthRoutes(
        jdbcDatabase = null,
        jdbcBlockingDispatcher = null,
        r2dbcDatabase = null,
        cacheReadiness = cacheReadiness,
    )
}
