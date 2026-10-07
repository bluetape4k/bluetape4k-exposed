import io.bluetape4k.gradle.NormalizedSigningKeyId
import io.bluetape4k.gradle.normalizeSigningKeyId
import io.bluetape4k.gradle.resolveSigningKey
import io.bluetape4k.gradle.resolveSigningKeyId
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Project property 또는 환경 변수에서 값을 조회합니다.
 */
fun Project.getEnvOrProperty(propertyKey: String, envKey: String): String =
    findProperty(propertyKey) as? String ?: System.getenv(envKey).orEmpty()

data class CentralPublishingConfig(
    val username: String,
    val password: String,
)

/**
 * Central Portal 자격증명을 project property / 환경 변수에서 로딩합니다.
 *
 * Property keys: `central.user`, `central.password`
 * Env var keys:  `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`
 */
fun Project.resolveCentralPublishingConfig(): CentralPublishingConfig = CentralPublishingConfig(
    username = getEnvOrProperty("central.user", "CENTRAL_USERNAME")
        .ifBlank { getEnvOrProperty("centralPortalUsername", "CENTRAL_USERNAME") },
    password = getEnvOrProperty("central.password", "CENTRAL_PASSWORD")
        .ifBlank { getEnvOrProperty("centralPortalPassword", "CENTRAL_PASSWORD") },
)

data class SigningConfig(
    val keyId: String,
    val key: String,
    val password: String,
    val useGpgCmd: Boolean,
    val gpgExecutable: String,
    val gpgKeyName: String,
)

/**
 * Signing 설정을 project property / 환경 변수에서 로딩합니다.
 *
 * Env var keys: `SIGNING_KEY_ID`, `SIGNING_KEY`, `SIGNING_PASSWORD`
 */
fun Project.resolveSigningConfig(): SigningConfig {
    val normalizedKeyId: NormalizedSigningKeyId =
        normalizeSigningKeyId(getEnvOrProperty("signingKeyId", "SIGNING_KEY_ID"))
    val keyId = resolveSigningKeyId(normalizedKeyId.value)
    normalizedKeyId.warning?.let(project.logger::warn)
    val key = resolveSigningKey(getEnvOrProperty("signingKey", "SIGNING_KEY"))
    val password = getEnvOrProperty("signingPassword", "SIGNING_PASSWORD")
    val useGpgCmd = getEnvOrProperty("signingUseGpgCmd", "SIGNING_USE_GPG_CMD").toBoolean()
    val gpgExecutable = getEnvOrProperty("signing.gnupg.executable", "GPG_EXECUTABLE")
        .ifBlank { "/opt/homebrew/bin/gpg" }
    val gpgKeyName = getEnvOrProperty("signing.gnupg.keyName", "GPG_KEY_NAME").ifBlank { keyId }
    return SigningConfig(keyId, key, password, useGpgCmd, gpgExecutable, gpgKeyName)
}

/**
 * Maven publication 서명을 설정합니다.
 * - CI: `SIGNING_KEY` + `SIGNING_PASSWORD` 환경 변수로 in-memory PGP 서명
 * - 로컬: `signingUseGpgCmd=true` 또는 gpg-cmd 설정으로 서명
 */
fun Project.configurePublishingSigning(publicationName: String) {
    val config = resolveSigningConfig()
    tasks.withType(GenerateMavenPom::class.java).configureEach {
        doLast {
            normalizeManagedDependencies(destination)
        }
    }
    extensions.configure<SigningExtension> {
        when {
            config.key.isNotBlank() && config.password.isNotBlank() -> {
                useInMemoryPgpKeys(config.keyId.ifBlank { null }, config.key, config.password)
                project.extensions.findByType(PublishingExtension::class.java)
                    ?.publications
                    ?.findByName(publicationName)
                    ?.let { sign(it) }
            }
            config.useGpgCmd -> {
                if (file(config.gpgExecutable).exists()) {
                    project.extensions.extraProperties["signing.gnupg.executable"] = config.gpgExecutable
                }
                if (config.gpgKeyName.isNotBlank()) {
                    project.extensions.extraProperties["signing.gnupg.keyName"] = config.gpgKeyName
                }
                useGpgCmd()
                project.extensions.findByType(PublishingExtension::class.java)
                    ?.publications
                    ?.findByName(publicationName)
                    ?.let { sign(it) }
            }
            else -> {
                // 서명 키 없음 — 로컬 개발 빌드에서는 서명 건너뜀
            }
        }
    }
}

internal fun normalizeManagedDependencies(pomFile: File) {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = factory.newDocumentBuilder().parse(pomFile)
    val dependencies = document.documentElement
        .childElements("dependencyManagement")
        .firstOrNull()
        ?.childElements("dependencies")
        ?.firstOrNull() ?: return
    val fingerprints = mutableMapOf<String, String>()
    var changed = false

    dependencies.childElements("dependency").forEach { dependency ->
        val groupId = dependency.childText("groupId")
        val artifactId = dependency.childText("artifactId")
        if (groupId.isBlank() || artifactId.isBlank()) return@forEach

        val type = dependency.childText("type").ifBlank { "jar" }
        val classifier = dependency.childText("classifier")
        val key = "$groupId:$artifactId:$type:$classifier"
        val fingerprint = dependency.canonicalFingerprint()
        val previous = fingerprints.putIfAbsent(key, fingerprint)

        when {
            previous == null -> Unit
            previous == fingerprint -> {
                dependencies.removeChild(dependency)
                changed = true
            }
            else -> throw GradleException(
                "Maven POM contains conflicting dependencyManagement entries for $key",
            )
        }
    }

    if (changed) {
        val output = java.io.ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        transformer.transform(DOMSource(document), StreamResult(output))
        pomFile.writeBytes(output.toByteArray())
    }
}

private fun Element.childElements(name: String): List<Element> =
    (0 until childNodes.length).mapNotNull { index ->
        (childNodes.item(index) as? Element)?.takeIf { it.tagName == name }
    }

private fun Element.childText(name: String): String = childElements(name).firstOrNull()?.textContent?.trim().orEmpty()

private fun org.w3c.dom.Node.canonicalFingerprint(): String = when (nodeType) {
    org.w3c.dom.Node.ELEMENT_NODE -> {
        val attributes = getAttributes()
        val serializedAttributes = (0 until attributes.length)
            .map { attributes.item(it) }
            .sortedBy { it.nodeName }
            .joinToString("|") { "${it.nodeName}=${it.nodeValue}" }
        val content = (0 until childNodes.length).mapNotNull { index ->
            val child = childNodes.item(index)
            when (child.nodeType) {
                org.w3c.dom.Node.ELEMENT_NODE -> child.canonicalFingerprint()
                org.w3c.dom.Node.TEXT_NODE,
                org.w3c.dom.Node.CDATA_SECTION_NODE,
                -> child.nodeValue?.trim()?.takeIf(String::isNotEmpty)
                else -> null
            }
        }.sorted().joinToString("|")
        "$nodeName[$serializedAttributes]{$content}"
    }
    else -> nodeValue?.trim().orEmpty()
}
