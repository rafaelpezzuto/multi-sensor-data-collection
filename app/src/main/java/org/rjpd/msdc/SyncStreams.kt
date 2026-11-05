package org.rjpd.msdc

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

private const val SYNC_BUFFER_SIZE = 8192

internal fun writeSyncZip(source: File, target: File, cancellationContext: CoroutineContext) {
    cancellationContext.ensureActive()
    ZipOutputStream(target.outputStream()).use { zipOut ->
        for (child in source.walkTopDown()) {
            cancellationContext.ensureActive()
            if (!child.isFile) continue
            zipOut.putNextEntry(ZipEntry(child.relativeTo(source).path))
            child.inputStream().use { copySyncStream(it, zipOut, cancellationContext) }
            zipOut.closeEntry()
        }
    }
    cancellationContext.ensureActive()
}

/** Leave stream ownership to the caller, but never copy another chunk after cancellation. */
internal fun copySyncStream(input: InputStream, output: OutputStream, cancellationContext: CoroutineContext) {
    val buffer = ByteArray(SYNC_BUFFER_SIZE)
    while (true) {
        cancellationContext.ensureActive()
        val count = input.read(buffer)
        cancellationContext.ensureActive()
        if (count == -1) break
        output.write(buffer, 0, count)
    }
}
