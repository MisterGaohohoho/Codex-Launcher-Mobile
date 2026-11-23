package com.codexlauncher.companion.data

import kotlinx.serialization.Serializable

/** The certificate identifies a PC; an address can change without creating another PC. */
@Serializable
data class ComputerProfiles(
    val version: Int = 2,
    val activeId: String? = null,
    val computers: List<PairedComputer> = emptyList(),
) {
    val active: PairedComputer?
        get() = computers.firstOrNull { it.id == activeId }

    fun upsert(
        computer: PairedComputer,
        activate: Boolean = true,
        preserveNickname: Boolean = true,
    ): ComputerProfiles {
        val previous = computers.firstOrNull { it.id == computer.id }
        val saved =
            computer.copy(
                // Existing draft/outbox paths used the stored fingerprint text.
                certificateSha256 = previous?.certificateSha256 ?: computer.certificateSha256,
                nickname =
                    if (preserveNickname) computer.nickname.ifBlank { previous?.nickname.orEmpty() }
                    else computer.nickname,
                connectionEndpoints =
                    (computer.connectionEndpoints + previous?.connectionEndpoints.orEmpty())
                        .distinct()
                        .take(16),
            )
        val next =
            if (previous == null) computers + saved
            else computers.map { if (it.id == saved.id) saved else it }
        require(next.size <= MAX_COMPUTERS) { "最多保存 $MAX_COMPUTERS 台电脑，请先移除不再使用的电脑" }
        return copy(
            activeId = if (activate || activeId == null) saved.id else activeId,
            computers = next,
        )
    }

    fun select(id: String): ComputerProfiles {
        require(computers.any { it.id == id }) { "这台电脑尚未配对" }
        return copy(activeId = id)
    }

    fun remove(id: String): ComputerProfiles {
        val remaining = computers.filterNot { it.id == id }
        return copy(
            computers = remaining,
            activeId = activeId?.takeIf { it != id } ?: remaining.firstOrNull()?.id,
        )
    }

    fun validate(): ComputerProfiles {
        require(version == 2 && computers.size <= MAX_COMPUTERS) { "电脑列表版本或大小无效" }
        require(computers.map { it.id }.distinct().size == computers.size) { "电脑身份重复" }
        require(activeId == null && computers.isEmpty() || active != null) { "当前电脑记录无效" }
        computers.forEach {
            validateEndpoint(it.endpoint)
            require(it.id.matches(Regex("[0-9a-f]{64}"))) { "电脑证书指纹无效" }
            require(it.deviceToken.isNotBlank() && it.deviceToken.length <= 1024) { "电脑授权无效" }
            require(it.nickname.length <= 64 && it.nickname.none(Char::isISOControl)) { "电脑备注无效" }
            require(it.connectionEndpoints.size <= 16) { "电脑地址过多" }
            it.connectionEndpoints.forEach(::validateEndpoint)
        }
        return this
    }

    companion object {
        const val MAX_COMPUTERS = 16
    }
}
