package com.codexlauncher.companion.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString

interface SessionStore {
    fun load(): PairedComputer?

    fun save(value: PairedComputer)

    fun clear()

    fun loadProfiles(): ComputerProfiles =
        load()?.let { ComputerProfiles().upsert(it) } ?: ComputerProfiles()

    fun saveProfiles(value: ComputerProfiles) {
        value.active?.let(::save) ?: clear()
    }

    var theme: String
}

class DeviceStore(context: Context, private val suppliedKey: SecretKey? = null) : SessionStore {
    private val prefs = context.getSharedPreferences("companion-v1", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        suppliedKey?.let {
            return it
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }

    override fun load(): PairedComputer? = loadProfiles().active

    @Synchronized
    override fun loadProfiles(): ComputerProfiles {
        prefs.getString("encrypted_computers_v2", null)?.let {
            return WireJson.decodeFromString<ComputerProfiles>(decrypt(it, 512 * 1024)).validate()
        }
        val legacy = prefs.getString("encrypted_device", null) ?: return ComputerProfiles()
        val profiles =
            ComputerProfiles()
                .upsert(WireJson.decodeFromString<PairedComputer>(decrypt(legacy, 16384)))
                .validate()
        // One atomic preferences commit stores the complete list before retiring v1.
        saveProfiles(profiles)
        return profiles
    }

    private fun decrypt(stored: String, limit: Int): String {
        require(stored.length <= limit)
        val parts = stored.split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        val plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        return try {
            plain.toString(Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    // KTX edit returns Unit; credential writes must check commit's Boolean.
    @Synchronized
    override fun save(value: PairedComputer) = saveProfiles(loadProfiles().upsert(value))

    @SuppressLint("UseKtx")
    @Synchronized
    override fun saveProfiles(value: ComputerProfiles) {
        value.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = WireJson.encodeToString(value).toByteArray(Charsets.UTF_8)
        val encrypted =
            try {
                cipher.doFinal(plain)
            } finally {
                plain.fill(0)
            }
        val data =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(
            prefs
                .edit()
                .putString("encrypted_computers_v2", data)
                .remove("encrypted_device")
                .commit()
        ) {
            "无法保存电脑授权"
        }
    }

    @Synchronized
    override fun clear() {
        val current = loadProfiles()
        saveProfiles(current.activeId?.let(current::remove) ?: current)
    }

    override var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) {
            prefs.edit { putString("theme", value) }
        }

    companion object {
        private const val ALIAS = "codex-launcher-companion-device-v1"
    }
}
