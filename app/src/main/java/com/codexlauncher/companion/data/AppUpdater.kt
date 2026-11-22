package com.codexlauncher.companion.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.codexlauncher.companion.BuildConfig
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val minSdk: Int = 26,
    val publishedAt: String,
    val releaseNotes: String = "",
)

@Serializable data class SignedManifest(val payload: String, val signature: String)

data class UpdateState(
    val checking: Boolean = false,
    val manifest: UpdateManifest? = null,
    val progress: Float? = null,
    val downloaded: File? = null,
    val error: String? = null,
    val checked: Boolean = false,
)

object UpdateVerifier {
    fun manifest(envelope: String, publicKey: String): UpdateManifest {
        require(envelope.length <= 128 * 1024) { "更新信息过大" }
        val signed = WireJson.decodeFromString<SignedManifest>(envelope)
        val payload = Base64.getDecoder().decode(signed.payload)
        val signature = Base64.getDecoder().decode(signed.signature)
        val key =
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        require(
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
        ) {
            "更新信息签名无效"
        }
        val manifest = WireJson.decodeFromString<UpdateManifest>(payload.toString(Charsets.UTF_8))
        val url = URI(manifest.apkUrl)
        require(
            url.scheme == "https" &&
                !url.host.isNullOrBlank() &&
                url.userInfo == null &&
                url.fragment == null
        ) {
            "安装包地址无效"
        }
        require(
            manifest.versionCode > 0 &&
                manifest.versionName.length in 1..100 &&
                manifest.size in 1..200L * 1024 * 1024 &&
                manifest.sha256.matches(Regex("[a-fA-F0-9]{64}"))
        ) {
            "更新包信息无效"
        }
        return manifest
    }

    fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class AppUpdater(private val context: Context) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()

    suspend fun check(): UpdateManifest =
        withContext(Dispatchers.IO) {
            client
                .newCall(
                    Request.Builder()
                        .url(BuildConfig.UPDATE_URL)
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache")
                        .build()
                )
                .execute()
                .use { response ->
                    check(response.isSuccessful) { "更新服务暂不可用 (${response.code})" }
                    val source = response.body?.source() ?: error("更新服务返回空内容")
                    require(!source.request(128 * 1024 + 1L)) { "更新信息超过上限" }
                    UpdateVerifier.manifest(source.readUtf8(), BuildConfig.UPDATE_PUBLIC_KEY)
                }
        }

    suspend fun download(manifest: UpdateManifest, progress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(manifest.versionCode > BuildConfig.VERSION_CODE) { "当前已是最新版本" }
            require(manifest.minSdk <= Build.VERSION.SDK_INT) { "此版本需要更高的 Android 系统版本" }
            val root = File(context.cacheDir, "updates").apply { mkdirs() }
            val part = File(root, "launcher-${manifest.versionCode}.part")
            val target = File(root, "launcher-${manifest.versionCode}.apk")
            val hash = MessageDigest.getInstance("SHA-256")
            try {
                val call = client.newCall(Request.Builder().url(manifest.apkUrl).build())
                call.execute().use { response ->
                    check(response.isSuccessful) { "安装包下载失败 (${response.code})" }
                    val body = response.body ?: error("安装包为空")
                    require(body.contentLength() < 0 || body.contentLength() == manifest.size) {
                        "安装包大小不匹配"
                    }
                    var read = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                read += count
                                require(read <= manifest.size) { "安装包超出声明大小" }
                                hash.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                progress(read.toFloat() / manifest.size)
                            }
                            output.fd.sync()
                        }
                    }
                    require(
                        read == manifest.size &&
                            hash
                                .digest()
                                .joinToString("") { "%02x".format(it) }
                                .equals(manifest.sha256, ignoreCase = true)
                    ) {
                        "安装包完整性校验失败"
                    }
                }
                verifyPackage(part, manifest)
                if (target.exists()) check(target.delete()) { "无法替换安装包缓存" }
                check(part.renameTo(target)) { "保存安装包失败" }
                target
            } catch (e: Exception) {
                part.delete()
                throw e
            }
        }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, manifest: UpdateManifest) {
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val archive =
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                ?: error("安装包格式无效")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "安装包不属于 Launcher" }
        val version =
            if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode
            else archive.versionCode.toLong()
        require(version == manifest.versionCode.toLong()) { "安装包版本与更新信息不符" }
        val expected =
            if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners
            else installed.signatures
        val actual =
            if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners
            else archive.signatures
        require(
            !actual.isNullOrEmpty() &&
                !expected.isNullOrEmpty() &&
                actual.map { UpdateVerifier.sha256(it.toByteArray()) }.toSet() ==
                    expected.map { UpdateVerifier.sha256(it.toByteArray()) }.toSet()
        ) {
            "APK 签名与已安装版本不同。调试版需先手动安装正式版。"
        }
    }
}
