package io.bluetape4k.exposed.redisson.snapshot

import org.redisson.api.options.LocalCachedMapOptions
import java.security.MessageDigest
import java.util.TreeMap

internal fun snapshotNamespaceFingerprint(
    backend: String,
    namespace: String,
    keyRawClass: Class<*>,
    snapshotRawClass: Class<*>,
    schemaVersion: String,
    codec: SnapshotRedissonCodec<*>,
    synchronizationStrategy: LocalCachedMapOptions.SyncStrategy,
): String =
    MessageDigest.getInstance("SHA-256")
        .digest(
            canonicalSnapshotNamespaceFingerprintInput(
                backend,
                namespace,
                keyRawClass,
                snapshotRawClass,
                schemaVersion,
                codec,
                synchronizationStrategy,
            ).toByteArray(Charsets.UTF_8),
        )
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun canonicalSnapshotNamespaceFingerprintInput(
    backend: String,
    namespace: String,
    keyRawClass: Class<*>,
    snapshotRawClass: Class<*>,
    schemaVersion: String,
    codec: SnapshotRedissonCodec<*>,
    synchronizationStrategy: LocalCachedMapOptions.SyncStrategy,
): String {
    val codecInternals = codec as? SnapshotRedissonCodecInternals
        ?: throw IllegalArgumentException("Unsupported snapshot Redisson codec implementation.")
    val fields = TreeMap<String, String>().apply {
        put("backend", backend)
        put("canonicalKeyEncodingId", codecInternals.canonicalKeyEncodingId)
        put("codecClass", codecInternals.delegateClassName)
        put("codecVersion", codec.codecVersion)
        put("keyRawClass", keyRawClass.name)
        put("namespace", namespace)
        put("schemaVersion", schemaVersion)
        put("snapshotRawClass", snapshotRawClass.name)
        put("synchronizationStrategy", synchronizationStrategy.name)
    }
    return buildString {
        append("bt4k-snapshot-fingerprint/v1\n")
        fields.forEach { (name, value) -> append(name).append('=').append(value).append('\n') }
    }
}
