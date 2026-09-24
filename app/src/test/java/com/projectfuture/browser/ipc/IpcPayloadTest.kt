package com.projectfuture.browser.ipc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class IpcPayloadTest {
    private val cacheDir: File = Files.createTempDirectory("ipc-test").toFile()

    @Test fun largePayloadRoundTripsThroughAFileThatIsDeletedAfterReading() {
        // Larger than Binder's whole ~1MB transaction buffer, like a long article's display list.
        val bytes = ByteArray(3_000_000) { (it % 251).toByte() }
        val file = IpcPayload.writeToFile(bytes, cacheDir)
        assertArrayEquals(bytes, IpcPayload.readAndDelete(file.absolutePath, cacheDir))
        assertFalse(file.exists())
        assertNull(IpcPayload.readAndDelete(file.absolutePath, cacheDir))
    }

    @Test fun refusesToReadFilesOutsideThePayloadDirectory() {
        val outside = File(cacheDir, "secret.txt").apply { writeText("x") }
        assertNull(IpcPayload.readAndDelete(outside.absolutePath, cacheDir))
        assertNull(IpcPayload.readAndDelete(File(cacheDir, "ipc-payloads/../secret.txt").path, cacheDir))
        assert(outside.exists())
    }
}
