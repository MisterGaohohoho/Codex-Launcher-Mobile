package com.codexlauncher.companion.data

import android.annotation.SuppressLint
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val status: Int, message: String) : IOException(message)

interface CompanionApi {
    suspend fun pair(code: PairingCode, name: String): PairResponse

    suspend fun snapshot(): Snapshot

    suspend fun details(run: String, worker: Int, attempt: Long): WorkerDetails

    suspend fun start(request: StartRequest): ActionResponse

    suspend fun stopRun(request: StopRequest): ActionResponse

    suspend fun keepAlive(request: KeepAliveRequest): ActionResponse

    suspend fun query(resource: String, input: JsonObject = JsonObject(emptyMap())): JsonObject =
        throw ApiException(404, "请更新 PC 端以使用此功能")

    suspend fun command(request: AdminCommand): JsonObject =
        throw ApiException(404, "请更新 PC 端以使用此功能")

    fun events(epoch: String, since: Long): Flow<StreamBatch> = emptyFlow()

    fun close()
}

class LauncherApi(endpoint: String, fingerprint: String, private val token: String? = null) :
    CompanionApi {
    private val base = validateEndpoint(endpoint)
    private val pin = fingerprint.lowercase()

    init {
        require(pin.matches(Regex("[0-9a-f]{64}"))) { "证书指纹无效" }
    }

    // The QR supplies the exact self-signed leaf fingerprint. Every handshake
    // verifies that pin and validity; the hostname verifier also binds the IP.
    @SuppressLint("CustomX509TrustManager")
    private val trust =
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are unsupported")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate =
                    chain?.firstOrNull() ?: throw CertificateException("No server certificate")
                certificate.checkValidity()
                if (
                    !MessageDigest.isEqual(
                        sha256(certificate).toByteArray(Charsets.US_ASCII),
                        pin.toByteArray(Charsets.US_ASCII),
                    )
                ) {
                    throw CertificateException("Paired certificate mismatch")
                }
            }
        }
    private val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    private val client =
        OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier { hostname, session ->
                // A LAN IP can change. Trust is the QR's exact endpoint AND leaf pin,
                // never a blanket hostname/CA bypass for arbitrary destinations.
                hostname == base.host &&
                    runCatching {
                            sha256(session.peerCertificates.first() as X509Certificate) == pin
                        }
                        .getOrDefault(false)
            }
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

    override suspend fun pair(code: PairingCode, name: String): PairResponse {
        val value: PairResponse =
            WireJson.decodeFromString(
                send(
                    "POST",
                    "pair",
                    WireJson.encodeToString(PairRequest(code.token, name.take(64))),
                )
            )
        require(value.protocolVersion == 1 && value.deviceToken.matches(Regex("[a-fA-F0-9]{64}"))) {
            "电脑返回了不兼容的配对信息"
        }
        return value
    }

    override suspend fun snapshot(): Snapshot {
        val value: Snapshot = WireJson.decodeFromString(send("GET", "snapshot"))
        require(value.protocolVersion == 1 && value.workers.size <= 256 && value.logs.size <= 200) {
            "电脑返回的状态版本或大小不兼容"
        }
        return value
    }

    override suspend fun details(run: String, worker: Int, attempt: Long): WorkerDetails {
        require(worker in 1..256 && attempt > 0)
        val url =
            base
                .newBuilder()
                .addPathSegments("api/v1/workers/$worker")
                .addQueryParameter("runId", run)
                .addQueryParameter("attempt", attempt.toString())
                .build()
        val value: WorkerDetails = WireJson.decodeFromString(send("GET", "", url = url))
        require(value.runId == run && value.workerId == worker && value.attempt == attempt) {
            "进程详情已变化，请刷新"
        }
        return value
    }

    override suspend fun start(request: StartRequest): ActionResponse =
        WireJson.decodeFromString(send("POST", "start", WireJson.encodeToString(request)))

    override suspend fun stopRun(request: StopRequest): ActionResponse =
        WireJson.decodeFromString(send("POST", "stop", WireJson.encodeToString(request)))

    override suspend fun keepAlive(request: KeepAliveRequest): ActionResponse =
        WireJson.decodeFromString(send("PATCH", "keep-alive", WireJson.encodeToString(request)))

    override suspend fun query(resource: String, input: JsonObject): JsonObject {
        require(resource.matches(Regex("[a-z-]{1,64}")))
        val value =
            WireJson.parseToJsonElement(
                send(
                    "POST",
                    "",
                    input.toString(),
                    base.newBuilder().addPathSegments("api/v2/query/$resource").build(),
                )
            )
        return value as? JsonObject ?: buildJsonObject { put("data", value) }
    }

    override suspend fun command(request: AdminCommand): JsonObject =
        WireJson.parseToJsonElement(
                send(
                    "POST",
                    "",
                    WireJson.encodeToString(request),
                    base.newBuilder().addPathSegments("api/v2/command").build(),
                )
            )
            .obj()

    override fun events(epoch: String, since: Long): Flow<StreamBatch> = callbackFlow {
        val url =
            base
                .newBuilder()
                .addPathSegments("api/v2/events")
                .addQueryParameter("epoch", epoch)
                .addQueryParameter("since", since.toString())
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer $token")
                .build()
        val call =
            client
                .newBuilder()
                .callTimeout(0, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
                .newCall(request)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    channel.close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful)
                                throw ApiException(it.code, "实时连接不可用 (${it.code})")
                            val source = it.body?.source() ?: throw IOException("Missing stream")
                            var data = StringBuilder()
                            while (!call.isCanceled() && !source.exhausted()) {
                                val line = source.readUtf8LineStrict(4 * 1024 * 1024)
                                if (line.isEmpty()) {
                                    if (data.isNotEmpty()) {
                                        val batch =
                                            WireJson.parseToJsonElement(data.toString())
                                                .obj()["workbench"]
                                        if (
                                            batch != null &&
                                                trySendBlocking(
                                                        WireJson.decodeFromJsonElement<StreamBatch>(
                                                            batch
                                                        )
                                                    )
                                                    .isFailure
                                        )
                                            break
                                    }
                                    data = StringBuilder()
                                } else if (line.startsWith("data:")) {
                                    if (data.length + line.length > 4 * 1024 * 1024)
                                        throw IOException("Stream frame too large")
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.substring(5).trimStart())
                                }
                            }
                        }
                        channel.close()
                    } catch (e: Exception) {
                        channel.close(e)
                    }
                }
            }
        )
        awaitClose { call.cancel() }
    }
        .buffer(2)

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private suspend fun send(
        method: String,
        path: String,
        payload: String? = null,
        url: HttpUrl = base.newBuilder().addPathSegments("api/v1/$path").build(),
    ): String = suspendCancellableCoroutine { continuation ->
        val builder =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .method(
                    method,
                    payload?.toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
        token?.let { builder.header("Authorization", "Bearer $it") }
        val transport =
            if (url.encodedPath.startsWith("/api/v2/"))
                client
                    .newBuilder()
                    .readTimeout(40, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .build()
            else client
        val maxResponseBytes =
            if (url.encodedPath.startsWith("/api/v2/")) 4L * 1024 * 1024 else 1024L * 1024
        val call = transport.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val body = it.body ?: throw IOException("电脑返回了空响应")
                            if (body.contentLength() > maxResponseBytes)
                                throw IOException("响应超过读取上限")
                            val source = body.source()
                            if (source.request(maxResponseBytes + 1L)) throw IOException("响应超过读取上限")
                            val text = source.readUtf8()
                            if (!it.isSuccessful) {
                                val message = runCatching {
                                    WireJson.decodeFromString<ApiErrorBody>(text).message.take(200)
                                }
                                    .getOrDefault("电脑请求失败 (${it.code})")
                                throw ApiException(it.code, message)
                            }
                            if (continuation.isActive) continuation.resume(text)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        )
    }

    companion object {
        fun sha256(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") {
                "%02x".format(it)
            }
    }
}
