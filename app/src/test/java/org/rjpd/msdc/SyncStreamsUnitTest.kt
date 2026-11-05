package org.rjpd.msdc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncStreamsUnitTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun archivePreservesRelativePathsAndFileBytes() {
        val source = temporaryFolder.newFolder("collection")
        source.resolve("nested").mkdir()
        val bytes = ByteArray(17_013) { (it % 251).toByte() }
        source.resolve("nested/synthetic.bin").writeBytes(bytes)
        source.resolve("metadata.json").writeText("{}")
        val archive = temporaryFolder.newFile("collection.zip")
        writeSyncZip(source, archive, Job())
        ZipFile(archive).use { zip ->
            assertEquals(2, zip.size())
            assertArrayEquals(bytes, zip.getInputStream(zip.getEntry("nested/synthetic.bin")).use { it.readBytes() })
            assertEquals("{}", zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().use { it.readText() })
        }
    }

    @Test
    fun cancelledArchiveDoesNotCreateOutput() {
        val source = temporaryFolder.newFolder("cancelled")
        source.resolve("synthetic.txt").writeText("synthetic")
        val archive = source.resolveSibling("cancelled.zip")
        try {
            writeSyncZip(source, archive, Job().apply { cancel() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(archive.exists())
        }
    }

    @Test
    fun activeCopyPreservesAllBytes() {
        val bytes = ByteArray(32_781) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        copySyncStream(ByteArrayInputStream(bytes), output, Job())
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun cancelledBeforeCopyDoesNotReadSource() {
        var read = false
        val source = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                read = true
                return super.read(buffer, offset, length)
            }
        }
        val job = Job().apply { cancel() }
        try {
            copySyncStream(source, ByteArrayOutputStream(), job)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(read)
        }
    }

    @Test
    fun cancelledDuringReadDoesNotWriteTheReturnedChunk() {
        val job = Job()
        val output = ByteArrayOutputStream()
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                job.cancel()
                return count
            }
        }
        try {
            copySyncStream(source, output, job)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, output.size())
        }
    }

    @Test
    fun cancelledDuringWriteDoesNotReadAnotherChunk() {
        val job = Job()
        var reads = 0
        val source = object : ByteArrayInputStream(ByteArray(32_768)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                return super.read(buffer, offset, length)
            }
        }
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                super.write(buffer, offset, length)
                job.cancel()
            }
        }
        try {
            copySyncStream(source, output, job)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, reads)
            assertEquals(8192, output.size())
        }
    }

    @Test(expected = IOException::class)
    fun readFailureIsNotSwallowed() {
        val source = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("synthetic read failure")
        }
        copySyncStream(source, ByteArrayOutputStream(), Job())
    }
}
