package org.rjpd.msdc

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S3SyncUnitTest {
    private val mib = 1024L * 1024

    @Test
    fun renderKeyBase_fillsPlaceholdersAndDropsEmptySegments() {
        val name = "Rota1-2026-10-19-15-43-14-478"
        assertEquals("Bras/Rota 1/$name", renderKeyBase("{directory}/{subdirectory}/{dataset}", " Bras ", "Rota 1", name))
        assertEquals("sao-paulo/bras/raw/D1P1/2026-10-19-15-43-14-478",
            renderKeyBase("sao-paulo/bras/raw/{subdirectory}/{timestamp}", "Bras", "D1P1", name))
        // "Default", empty and duplicate levels disappear instead of leaving empty segments.
        assertEquals(name, renderKeyBase("{directory}/{subdirectory}/{dataset}", "Default", "", name))
        assertEquals("Bras/$name", renderKeyBase("{directory}/{subdirectory}/{dataset}", "Bras", "bras", name))
        // Zip datasets keep their base name; slashes typed into a level cannot create extra levels.
        assertEquals("a-b/x", renderKeyBase("{directory}/{dataset}", "a/b", "", "x.zip"))
    }

    @Test
    fun partSizeFor_keepsConfiguredSizeAndGrowsPastTenThousandParts() {
        assertEquals(16 * mib, partSizeFor(2L * 1024 * mib, 16))
        val huge = 200L * 1024 * mib
        val size = partSizeFor(huge, 16)
        assertTrue(huge / size <= 10_000)
        assertEquals(0, size % mib)
    }

    @Test
    fun hashFile_matchesS3MultipartEtag() = runBlocking {
        val part1 = ByteArray((5 * mib).toInt()) { (it * 31).toByte() }
        val part2 = ByteArray(1024) { 7 }
        val file = File.createTempFile("hash", ".bin").apply { writeBytes(part1 + part2); deleteOnExit() }
        val digests = hashFile(file, 5 * mib)
        fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes)
        assertEquals(Base64.getEncoder().encodeToString(md5(part1 + part2)), digests.md5Base64)
        assertEquals(2, digests.partMd5Base64.size)
        assertEquals(SigV4.sha256Hex(part2), digests.partSha256[1])
        assertEquals(md5(md5(part1) + md5(part2)).toHex() + "-2", digests.multipartEtag)
    }

    @Test
    fun hashFile_emptyFileIsOnePart() = runBlocking {
        val file = File.createTempFile("empty", ".bin").apply { deleteOnExit() }
        val digests = hashFile(file, 16 * mib)
        assertEquals(1, digests.partSha256.size)
        assertEquals(SigV4.EMPTY_SHA256, digests.partSha256[0])
    }

    @Test
    fun matches_acceptsOurChecksumRcloneOrEtag() {
        val digests = FileDigests("bWQ1", listOf("AAAAAAAAAAAAAAAAAAAAAA==", "AQEBAQEBAQEBAQEBAQEBAQ=="), listOf("a", "b"))
        assertTrue(digests.matches(S3ObjectInfo(10, "opaque", "bWQ1"), 10))
        assertTrue(digests.matches(S3ObjectInfo(10, digests.multipartEtag, null), 10))
        assertTrue(digests.matches(S3ObjectInfo(10, digests.md5Hex, null), 10))
        assertFalse(digests.matches(S3ObjectInfo(11, digests.multipartEtag, "bWQ1"), 10))
        assertFalse(digests.matches(S3ObjectInfo(10, "different-2", "b3RoZXI="), 10))
    }

    @Test
    fun planObjects_uploadsMetadataLastAndKeepsLayout() {
        val dir = Files.createTempDirectory("dataset").toFile()
        listOf("video.mp4", "metadata.json", "gps.csv", "extra/notes.txt").forEach { File(dir, it).apply { parentFile.mkdirs(); writeText(it) } }
        val planned = planObjects(dir, "base", null)
        assertEquals(listOf("extra/notes.txt", "gps.csv", "video.mp4", "metadata.json"), planned.map { it.relativePath })
        assertEquals("base/extra/notes.txt", planned[0].key)
        dir.deleteRecursively()
    }

    @Test
    fun contentTypeFor_knownExtensions() {
        assertEquals("video/mp4", contentTypeFor("video.mp4"))
        assertEquals("text/csv", contentTypeFor("sensors.three.csv"))
        assertEquals("application/octet-stream", contentTypeFor("labels"))
    }
}
