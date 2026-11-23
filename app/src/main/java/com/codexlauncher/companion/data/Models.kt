package com.codexlauncher.companion.data

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

val WireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class PairingCode(
    @SerialName("type") val type: String,
    val version: Int,
    val endpoint: String,
    val certificateSha256: String,
    val token: String,
    val expiresAt: String,
    val computerName: String,
) {
    companion object {
        fun parse(text: String, now: Instant = Instant.now()): PairingCode {
            require(text.length <= 4096) { "配对内容过长" }
            val value =
                try {
                    Json.decodeFromString<PairingCode>(text.trim())
                } catch (_: Exception) {
                    throw IllegalArgumentException("请扫描或粘贴电脑“手机连接”页生成的完整配对内容")
                }
            require(value.type == "codex-launcher-pair" && value.version == 1) {
                "配对版本不兼容，请更新电脑和手机端"
            }
            validateEndpoint(value.endpoint)
            require(
                value.certificateSha256.matches(Regex("[a-fA-F0-9]{64}")) &&
                    value.token.matches(Regex("[a-fA-F0-9]{64}"))
            ) {
                "配对证书或令牌无效"
            }
            val expiry =
                try {
                    Instant.parse(value.expiresAt)
                } catch (_: Exception) {
                    throw IllegalArgumentException("配对有效期无效")
                }
            require(expiry > now) { "配对码已过期，请在电脑上重新生成；请确保两端时间正确" }
            require(value.computerName.isNotBlank() && value.computerName.length <= 128) {
                "电脑名称无效"
            }
            return value
        }
    }
}

fun validateEndpoint(value: String): HttpUrl {
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("连接地址无效")
    require(
        url.isHttps &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.encodedPath == "/" &&
            url.query == null &&
            url.fragment == null &&
            url.port >= 1024
    ) {
        "只允许无附加参数的本地 HTTPS 地址"
    }
    val parts = url.host.split('.')
    val bytes = parts.map { it.toIntOrNull() ?: -1 }
    // Require an exact dotted-decimal IPv4 address. Dropping nonnumeric labels
    // would incorrectly accept a public hostname such as 10.0.0.1.example.com.
    require(
        parts.size == 4 &&
            parts.zip(bytes).all { (part, byte) -> byte in 0..255 && part == byte.toString() } &&
            (bytes[0] == 10 ||
                bytes[0] == 127 ||
                bytes[0] == 192 && bytes[1] == 168 ||
                bytes[0] == 172 && bytes[1] in 16..31 ||
                bytes[0] == 169 && bytes[1] == 254 ||
                bytes[0] == 100 && bytes[1] in 64..127)
    ) {
        "请使用局域网或 Tailscale 地址"
    }
    return url
}

@Serializable data class PairRequest(val pairingToken: String, val deviceName: String)

@Serializable
data class PairResponse(
    val deviceId: String,
    val deviceToken: String,
    val computerName: String,
    val canControl: Boolean,
    val expiresAt: String,
    val protocolVersion: Int,
    val connectionEndpoints: List<String> = emptyList(),
)

@Serializable
data class PairedComputer(
    val endpoint: String,
    val certificateSha256: String,
    val deviceId: String,
    val deviceToken: String,
    val computerName: String,
    val canControl: Boolean,
    val expiresAt: String,
    val nickname: String = "",
    val connectionEndpoints: List<String> = emptyList(),
) {
    val id: String
        get() = certificateSha256.lowercase()

    val displayName: String
        get() = nickname.ifBlank { computerName }
}

@Serializable
data class RunConfiguration(
    val provider: String = "",
    val executionMode: String = "",
    val model: String = "",
    val concurrency: Long = 1,
    val keepAliveEnabled: Boolean = false,
    val contentMode: String = "configured",
    val contentModeSupported: Boolean = false,
    val questionBankAvailable: Boolean = false,
    val keepAliveIntervalSeconds: Long = 300,
    val emailEnabled: Boolean = false,
    val startAvailable: Boolean = false,
)

@Serializable
data class RunSummary(
    val runId: String,
    val state: String,
    val phase: String,
    val attempt: Long,
    val retryCount: Long,
    val activeWorkers: Long,
    val concurrency: Long,
    val startedAt: String,
    val updatedAt: String,
    val elapsedSeconds: Long,
    val keepAliveEnabled: Boolean,
    val nextRequestAt: String? = null,
) {
    val active: Boolean
        get() = state == "starting" || state == "running"
}

@Serializable
data class WorkerSummary(
    val workerId: Int,
    val attempt: Long,
    val state: String,
    val startedAt: String,
    val updatedAt: String,
    val summary: String,
)

@Serializable
data class LogLine(
    val id: Long,
    val time: String,
    val level: String,
    val workerId: Int? = null,
    val text: String,
)

@Serializable
data class Snapshot(
    val protocolVersion: Int,
    val serverTime: String,
    val computerName: String,
    val version: String,
    val status: RunSummary? = null,
    val configuration: RunConfiguration = RunConfiguration(),
    val workers: List<WorkerSummary> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val historyTruncated: Boolean = false,
    val configurationRevision: String = "",
    val capabilities: List<String> = emptyList(),
    val commandEpoch: String = "",
    val connectionEndpoints: List<String> = emptyList(),
)

@Serializable
data class WorkerDetails(
    val runId: String,
    val workerId: Int,
    val attempt: Long,
    val requestKind: String,
    val label: String,
    val model: String,
    val request: String,
    val response: String,
    val diagnostic: String,
    val completed: Boolean,
    val truncated: Boolean,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

@Serializable data class StartRequest(val requestId: String, val expectedRunId: String?)

@Serializable data class StopRequest(val requestId: String, val runId: String)

@Serializable
data class KeepAliveRequest(
    val requestId: String,
    val runId: String?,
    val enabled: Boolean? = null,
    val mode: String? = null,
)

@Serializable data class ActionResponse(val runId: String? = null, val message: String)

@Serializable data class ApiErrorBody(val message: String)
