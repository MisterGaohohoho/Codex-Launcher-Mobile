package com.codexlauncher.companion

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.codexlauncher.companion.data.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComputerProfilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs
        get() = context.getSharedPreferences("companion-v1", Context.MODE_PRIVATE)

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val first =
        PairedComputer(
            "https://192.168.1.2:39173",
            "a".repeat(64),
            "phone-a",
            "b".repeat(64),
            "Studio PC",
            true,
            "2030-01-01T00:00:00Z",
        )
    private val second =
        first.copy(
            certificateSha256 = "c".repeat(64),
            deviceId = "phone-b",
            deviceToken = "d".repeat(64),
            computerName = "Travel PC",
        )

    @Before
    fun clearFixture() {
        check(prefs.edit().clear().commit())
    }

    @Test
    fun migratesTheOriginalEncryptedDeviceAndRestoresMultipleComputersAfterRestart() {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(
                    cipher.doFinal(WireJson.encodeToString(first).toByteArray()),
                    Base64.NO_WRAP,
                )
        check(
            prefs
                .edit()
                .putString("encrypted_device", encrypted)
                .putString("theme", "dark")
                .commit()
        )
        val store = DeviceStore(context, key)
        assertEquals(first, store.loadProfiles().active)
        assertFalse(prefs.contains("encrypted_device"))
        assertTrue(prefs.contains("encrypted_computers_v2"))
        val profiles = store.loadProfiles().upsert(second).select(first.id)
        store.saveProfiles(profiles)
        val restarted = DeviceStore(context, key)
        assertEquals(profiles, restarted.loadProfiles())
        assertEquals("dark", restarted.theme)
        val persisted = prefs.getString("encrypted_computers_v2", "")!!
        assertFalse(persisted.contains(first.deviceToken))
        assertFalse(persisted.contains(second.computerName))
    }

    @Test
    fun identityUsesTheCertificateAndRePairingPreservesTheOtherPcAndLocalNickname() {
        val named =
            first.copy(nickname = "Home", connectionEndpoints = listOf("https://100.72.1.2:39173"))
        val profiles = ComputerProfiles().upsert(named).upsert(second)
        // Different certificates can legitimately have the same LAN address on different networks.
        assertEquals(2, profiles.validate().computers.size)
        val renewed =
            first.copy(
                certificateSha256 = first.certificateSha256.uppercase(),
                endpoint = "https://192.168.2.3:39173",
                deviceToken = "e".repeat(64),
            )
        val next = profiles.upsert(renewed).validate()
        assertEquals(2, next.computers.size)
        assertEquals(second, next.computers.last())
        assertEquals("Home", next.active!!.nickname)
        assertEquals(renewed.deviceToken, next.active!!.deviceToken)
        assertEquals(first.certificateSha256, next.active!!.certificateSha256)
        val legacyUppercase = first.copy(certificateSha256 = first.certificateSha256.uppercase())
        assertEquals(
            legacyUppercase.certificateSha256,
            ComputerProfiles().upsert(legacyUppercase).upsert(first).active!!.certificateSha256,
        )
        assertEquals(named.connectionEndpoints, next.active!!.connectionEndpoints)
        assertEquals(
            "",
            next
                .upsert(next.active!!.copy(nickname = ""), preserveNickname = false)
                .active!!
                .nickname,
        )
    }

    @Test
    fun removingOneComputerPreservesTheOtherAuthorizationAndActiveSelection() {
        val store = DeviceStore(context, key)
        store.saveProfiles(ComputerProfiles().upsert(first).upsert(second).select(first.id))
        store.clear()
        assertEquals(listOf(second), store.loadProfiles().computers)
        assertEquals(second.id, store.loadProfiles().activeId)
        store.clear()
        assertTrue(store.loadProfiles().computers.isEmpty())
        assertNull(store.loadProfiles().activeId)
    }

    @Test
    fun corruptCiphertextAndInvalidProfilesDoNotEraseAnExistingAuthorization() {
        val store = DeviceStore(context, key)
        store.saveProfiles(ComputerProfiles().upsert(first))
        val original = prefs.getString("encrypted_computers_v2", null)
        assertThrows(IllegalArgumentException::class.java) {
            store.saveProfiles(
                ComputerProfiles()
                    .upsert(first.copy(connectionEndpoints = listOf("https://8.8.8.8:39173")))
            )
        }
        assertEquals(original, prefs.getString("encrypted_computers_v2", null))
        check(prefs.edit().putString("encrypted_computers_v2", "invalid:invalid").commit())
        assertThrows(Exception::class.java) { store.loadProfiles() }
        assertTrue(prefs.contains("encrypted_computers_v2"))
    }
}
