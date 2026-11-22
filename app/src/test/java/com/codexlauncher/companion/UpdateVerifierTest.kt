package com.codexlauncher.companion

import com.codexlauncher.companion.data.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class UpdateVerifierTest {
    private val keys =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val release =
        UpdateManifest(
            10,
            "1.0.0",
            "https://github.com/example/mobile/releases/download/v1.0.0/launcher-mobile.apk",
            "a".repeat(64),
            1024,
            publishedAt = "2026-11-22T00:00:00Z",
        )

    private fun signed(manifest: UpdateManifest = release): SignedManifest {
        val payload = WireJson.encodeToString(manifest).toByteArray()
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(payload)
                sign()
            }
        return SignedManifest(
            Base64.getEncoder().encodeToString(payload),
            Base64.getEncoder().encodeToString(signature),
        )
    }

    @Test
    fun validReleaseUsesTheOriginalSignedPayload() {
        assertEquals(release, UpdateVerifier.manifest(WireJson.encodeToString(signed()), publicKey))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateVerifier.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun alteredPayloadAndUntrustedSigningKeyAreRejected() {
        val envelope = signed()
        val altered = envelope.copy(payload = signed(release.copy(versionCode = 11)).payload)
        assertThrows(IllegalArgumentException::class.java) {
            UpdateVerifier.manifest(WireJson.encodeToString(altered), publicKey)
        }
        val foreignKey =
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            UpdateVerifier.manifest(
                WireJson.encodeToString(envelope),
                Base64.getEncoder().encodeToString(foreignKey.public.encoded),
            )
        }
    }

    @Test
    fun signedButInvalidDownloadMetadataIsRejected() {
        for (manifest in
            listOf(
                release.copy(apkUrl = "http://example.test/app.apk"),
                release.copy(apkUrl = "https://user:secret@example.test/app.apk"),
                release.copy(sha256 = "invalid"),
                release.copy(size = 201L * 1024 * 1024),
                release.copy(versionCode = 0),
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdateVerifier.manifest(WireJson.encodeToString(signed(manifest)), publicKey)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateVerifier.manifest("x".repeat(128 * 1024 + 1), publicKey)
        }
    }
}
