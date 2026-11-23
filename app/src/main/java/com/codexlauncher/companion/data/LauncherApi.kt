package com.codexlauncher.companion.data

import android.annotation.SuppressLint
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

class ApiException(val status: Int, message: String, val code: String = "") : IOException(message)

interface CompanionApi {
    val connectionInfo: ConnectionInfo
        get() = ConnectionInfo()

    fun updateEndpoints(endpoints: List<String>) {}

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

    suspend fun createUpload(request: UploadRequest): AttachmentUpload =
        throw ApiException(404, "请升级 PC 以使用附件")

    suspend fun uploadStatus(id: String): AttachmentUpload = throw ApiException(404, "请升级 PC 以使用附件")

    suspend fun uploadChunk(
        id: String,
        index: Int,
        total: Long,
        bytes: ByteArray,
    ): AttachmentUpload = throw ApiException(404, "请升级 PC 以使用附件")

    suspend fun completeUpload(id: String): AttachmentUpload =
        throw ApiException(404, "请升级 PC 以使用附件")

    suspend fun cancelUpload(id: String) {}

    suspend fun downloadAttachment(
        thread: String,
        id: String,
        preview: Boolean,
        destination: File,
    ) {
        throw ApiException(404, "请升级 PC 以使用附件")
    }

    fun close()
}

class LauncherApi(endpoint: String, fingerprint: String, private val token: String? = null) :
    CompanionApi {
    private val routing = ConnectionRouting(endpoint)
    private val base: HttpUrl
        get() = routing.current

    @Volatile
    override var connectionInfo = ConnectionInfo(endpoint)
        private set

    override fun updateEndpoints(endpoints: List<String>) = routing.update(endpoints)

    private val pin = fingerprint.lowercase()

    private fun elapsedNow(): Long = System.nanoTime() / 1_000_000

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
                // Addresses come from pairing, an authenticated PC, or a user edit.
                // Every destination must present the same pinned leaf certificate.
                routing.allows(hostname) &&
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

    // Slow history, long-lived events and attachment transfers cannot queue the heartbeat.
    private val healthClient =
        client
            .newBuilder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequestsPerHost = 4
                    maxRequests = 8
                }
            )
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    private val streamClient = client.newBuilder().dispatcher(Dispatcher()).build()

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
        return coroutineScope {
            val endpoints = routing.plan(elapsedNow())
            val results = Channel<Result<Pair<HttpUrl, Pair<Snapshot, Long>>>>(endpoints.size)
            val calls = endpoints.mapIndexed { index, endpoint ->
                launch {
                    delay(index * 200L)
                    val start = elapsedNow()
                    try {
                        val text =
                            send(
                                "GET",
                                "snapshot",
                                url =
                                    endpoint
                                        .newBuilder()
                                        .addPathSegments("api/v1/snapshot")
                                        .build(),
                                transportOverride = healthClient,
                            )
                        val value =
                            withContext(Dispatchers.Default) {
                                WireJson.decodeFromString<Snapshot>(text)
                            }
                        require(
                            value.protocolVersion == 1 &&
                                value.workers.size <= 256 &&
                                value.logs.size <= 200
                        ) {
                            "电脑返回的状态版本或大小不兼容"
                        }
                        results.send(Result.success(endpoint to (value to (elapsedNow() - start))))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        routing.failed(endpoint, elapsedNow())
                        results.send(Result.failure(e))
                    }
                }
            }
            var failure: Throwable = IOException("电脑暂时无法连接")
            try {
                repeat(endpoints.size) {
                    val result = results.receive()
                    result.getOrNull()?.let { (endpoint, response) ->
                        routing.succeeded(endpoint)
                        connectionInfo =
                            ConnectionInfo(endpoint.toString().trimEnd('/'), response.second)
                        if (response.first.connectionEndpoints.isNotEmpty())
                            routing.update(response.first.connectionEndpoints)
                        return@coroutineScope response.first
                    }
                    val error = result.exceptionOrNull()!!
                    if (
                        failure !is SSLException && failure !is ApiException ||
                            error is SSLException ||
                            error is ApiException
                    )
                        failure = error
                }
                throw failure
            } finally {
                // The bounded local channel can be collected with this scope. Closing it
                // before cancelled producers exit can race an otherwise successful reply.
                calls.forEach { it.cancel() }
            }
        }
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
        val text = read("GET", url = url)
        val value: WorkerDetails =
            withContext(Dispatchers.Default) { WireJson.decodeFromString(text) }
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
        val text =
            read(
                "POST",
                input.toString(),
                base.newBuilder().addPathSegments("api/v2/query/$resource").build(),
            )
        val value = withContext(Dispatchers.Default) { WireJson.parseToJsonElement(text) }
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
            streamClient
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
        healthClient.dispatcher.cancelAll()
        streamClient.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun attachmentUrl(path: String): HttpUrl =
        base.newBuilder().addPathSegments("api/v2/attachments/$path").build()

    override suspend fun createUpload(request: UploadRequest): AttachmentUpload =
        WireJson.decodeFromString(
            send("POST", "", WireJson.encodeToString(request), attachmentUrl("uploads"))
        )

    override suspend fun uploadStatus(id: String): AttachmentUpload {
        require(id.matches(Regex("[a-fA-F0-9-]{36}")))
        return WireJson.decodeFromString(send("GET", "", url = attachmentUrl("uploads/$id")))
    }

    override suspend fun completeUpload(id: String): AttachmentUpload =
        WireJson.decodeFromString(send("POST", "", "{}", attachmentUrl("uploads/$id/complete")))

    override suspend fun cancelUpload(id: String) {
        require(id.matches(Regex("[a-fA-F0-9-]{36}")))
        send("DELETE", "", url = attachmentUrl("uploads/$id"))
    }

    override suspend fun uploadChunk(
        id: String,
        index: Int,
        total: Long,
        bytes: ByteArray,
    ): AttachmentUpload {
        require(
            id.matches(Regex("[a-fA-F0-9-]{36}")) &&
                index >= 0 &&
                bytes.size in 1..ATTACHMENT_MIB.toInt()
        )
        val start = index.toLong() * ATTACHMENT_MIB
        val request =
            Request.Builder()
                .url(attachmentUrl("uploads/$id/chunks/$index"))
                .header("Authorization", "Bearer $token")
                .header("Cache-Control", "no-store")
                .header("Content-Range", "bytes $start-${start + bytes.size - 1}/$total")
                .header("X-Chunk-SHA256", bytesSha256(bytes))
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
        val response = transfer(request, ATTACHMENT_MIB)
        return WireJson.decodeFromString(response.toString(Charsets.UTF_8))
    }

    override suspend fun downloadAttachment(
        thread: String,
        id: String,
        preview: Boolean,
        destination: File,
    ) {
        require(id.matches(Regex("att_[a-fA-F0-9-]{36}")))
        val request =
            Request.Builder()
                .url(
                    attachmentUrl("$id/${if (preview) "preview" else "content"}")
                        .newBuilder()
                        .addQueryParameter("threadId", thread)
                        .build()
                )
                .header("Authorization", "Bearer $token")
                .header("Cache-Control", "no-store")
                .get()
                .build()
        transfer(request, if (preview) ATTACHMENT_MIB else 50 * ATTACHMENT_MIB, destination)
    }

    private suspend fun transfer(
        request: Request,
        limit: Long,
        destination: File? = null,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val call =
            client
                .newBuilder()
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
                .newCall(request)
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
                            if (!it.isSuccessful) {
                                val source = body.source()
                                if (source.request(65537)) throw IOException("错误响应超过上限")
                                val error = runCatching {
                                    WireJson.parseToJsonElement(source.readUtf8()).obj()
                                }
                                    .getOrDefault(JsonObject(emptyMap()))
                                throw ApiException(
                                    it.code,
                                    error["message"].text("附件传输失败 (${it.code})"),
                                    error["code"].text(),
                                )
                            }
                            if (body.contentLength() > limit) throw IOException("附件响应超过大小限制")
                            val memory =
                                if (destination == null) java.io.ByteArrayOutputStream() else null
                            val output = destination?.outputStream() ?: memory!!
                            output.use { sink ->
                                body.byteStream().use { input ->
                                    var total = 0L
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        if (call.isCanceled()) throw IOException("附件传输已暂停")
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        total += read
                                        if (total > limit) throw IOException("附件响应超过大小限制")
                                        sink.write(buffer, 0, read)
                                    }
                                }
                            }
                            if (continuation.isActive)
                                continuation.resume(memory?.toByteArray() ?: byteArrayOf())
                        }
                    } catch (e: Exception) {
                        destination?.delete()
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        )
    }

    private suspend fun read(method: String, payload: String? = null, url: HttpUrl): String {
        val started = elapsedNow()
        try {
            return send(method, "", payload, url)
        } catch (e: IOException) {
            // Retry only read operations, once, and only after a quick transport/server failure.
            if (
                e is SSLException ||
                    e is ApiException && e.status !in listOf(408, 429, 502, 503, 504) ||
                    elapsedNow() - started > 8_000
            )
                throw e
            delay(if (e is ApiException && e.status == 429) 1_000 else 350)
            val next = base
            val retryUrl = url.newBuilder().host(next.host).port(next.port).build()
            return send(method, "", payload, retryUrl)
        }
    }

    private suspend fun send(
        method: String,
        path: String,
        payload: String? = null,
        url: HttpUrl = base.newBuilder().addPathSegments("api/v1/$path").build(),
        transportOverride: OkHttpClient? = null,
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
            transportOverride
                ?: if (url.encodedPath.startsWith("/api/v2/"))
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
