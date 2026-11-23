package com.codexlauncher.companion.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Same-directory, fsynced replacement. A failed commit throws and retains the old index. */
internal fun atomicWrite(file: File, bytes: ByteArray) {
    val parent = requireNotNull(file.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "无法创建本机草稿目录" }
    val temporary = File(parent, file.name + "." + UUID.randomUUID() + ".tmp")
    try {
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
