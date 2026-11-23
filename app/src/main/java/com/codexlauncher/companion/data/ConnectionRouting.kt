package com.codexlauncher.companion.data

import java.net.NetworkInterface
import okhttp3.HttpUrl

data class ConnectionInfo(val endpoint: String = "", val latencyMs: Long = 0) {
    val label: String
        get() =
            if (endpoint.isBlank()) ""
            else if (isTailscale(validateEndpoint(endpoint))) "Tailscale" else "本地网络"
}

internal fun isTailscale(url: HttpUrl): Boolean {
    val parts = url.host.split('.').map { it.toIntOrNull() ?: -1 }
    return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
}

internal fun localIpv4Networks(): List<Pair<Int, Int>> = runCatching {
    NetworkInterface.getNetworkInterfaces()
        .toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.interfaceAddresses }
        .mapNotNull { address ->
            val bytes = address.address.address
            val prefix = address.networkPrefixLength.toInt()
            if (bytes.size != 4 || prefix !in 8..30 || (bytes[0].toInt() and 255) == 100) null
            else bytes.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) } to prefix
        }
}
    .getOrDefault(emptyList())

/** Only the paired endpoint, user-entered endpoints and pinned PC responses enter this list. */
internal class ConnectionRouting(
    endpoint: String,
    private val networks: () -> List<Pair<Int, Int>> = ::localIpv4Networks,
) {
    private val original = validateEndpoint(endpoint)
    @Volatile
    var current: HttpUrl = original
        private set

    @Volatile private var candidates: List<HttpUrl> = listOf(original)
    private val failures = mutableMapOf<HttpUrl, Long>()

    @Synchronized
    fun update(values: List<String>) {
        candidates =
            (listOf(original, current) +
                    values.take(16).mapNotNull { runCatching { validateEndpoint(it) }.getOrNull() })
                .distinct()
                .take(16)
    }

    fun allows(host: String) = candidates.any { it.host == host }

    @Synchronized
    fun plan(now: Long): List<HttpUrl> {
        val local = networks()
        fun nearby(url: HttpUrl): Boolean {
            val ip = url.host.split('.').fold(0) { value, part -> (value shl 8) or part.toInt() }
            return !isTailscale(url) &&
                local.any { (address, prefix) ->
                    val mask = -1 shl (32 - prefix)
                    (ip and mask) == (address and mask)
                }
        }
        val eligible = candidates.filter {
            it == current || failures[it]?.let { until -> now >= until } != false
        }
        val localFirst = eligible.filter(::nearby)
        return (localFirst + listOf(current) + eligible).distinct().take(4)
    }

    @Synchronized
    fun succeeded(url: HttpUrl) {
        current = url
        failures.remove(url)
    }

    @Synchronized
    fun failed(url: HttpUrl, now: Long) {
        failures[url] = now + 30_000
    }
}
