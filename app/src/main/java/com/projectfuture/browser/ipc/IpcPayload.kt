package com.projectfuture.browser.ipc

import android.os.Bundle
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Carries a byte payload between the engine and UI processes without going through Binder's
 * transaction buffer, which is about 1MB per process and roughly half that for the one-way calls a
 * `Messenger` makes. A long article's display list (Wikipedia's "Cat" page encodes to about 1.3MB)
 * or one large image exceeded it, `Messenger.send` threw, and the page stayed blank.
 *
 * Small payloads still ride inline in the `Bundle`. Larger ones are written to a file in the app's
 * cache directory, which every process of this app shares, and only the path crosses the process
 * boundary. The receiver reads the file once and deletes it.
 */
object IpcPayload {
    const val INLINE_LIMIT = 64 * 1024
    private const val FILE_KEY_SUFFIX = "__file"
    private const val DIR_NAME = "ipc-payloads"
    // A payload whose message never arrived (the receiving process died) is left behind; the next
    // large write sweeps anything this old.
    private const val STALE_AFTER_MS = 5 * 60 * 1000L

    fun put(bundle: Bundle, key: String, bytes: ByteArray, cacheDir: File) {
        if (bytes.size <= INLINE_LIMIT) {
            bundle.putByteArray(key, bytes)
        } else {
            bundle.putString(key + FILE_KEY_SUFFIX, writeToFile(bytes, cacheDir).absolutePath)
        }
    }

    fun take(bundle: Bundle, key: String, cacheDir: File): ByteArray? {
        bundle.getByteArray(key)?.let { return it }
        val path = bundle.getString(key + FILE_KEY_SUFFIX) ?: return null
        return readAndDelete(path, cacheDir)
    }

    fun writeToFile(bytes: ByteArray, cacheDir: File): File {
        val dir = File(cacheDir, DIR_NAME).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val file = File.createTempFile("payload", ".bin", dir)
        file.writeBytes(bytes)
        return file
    }

    /** Reads a payload file and deletes it. Only files inside this app's payload directory are accepted. */
    fun readAndDelete(path: String, cacheDir: File): ByteArray? {
        val file = File(path).canonicalFile
        if (file.parentFile != File(cacheDir, DIR_NAME).canonicalFile) return null
        return try {
            file.readBytes()
        } catch (_: IOException) {
            null
        } finally {
            file.delete()
        }
    }
}

/**
 * Length-prefixed UTF-8 string. `DataOutputStream.writeUTF` throws for any string over 64KB of
 * encoded bytes, and a single text run or link on a real page can be that long.
 */
internal fun DataOutputStream.writeLongString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

internal fun DataInputStream.readLongString(): String {
    val bytes = ByteArray(readInt())
    readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}
