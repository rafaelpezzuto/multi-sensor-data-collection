package org.rjpd.msdc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val RESPONSE_WAIT_SECONDS = 5L

class HttpDatasetUploadUnitTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val releaseResponse = CountDownLatch(1)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        releaseResponse.countDown()
        server.close()
    }

    @Test
    fun successfulUploadPreservesMultipartFieldsAndBearerToken() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).build())
        val dataset = dataset("synthetic-collection")

        val result = uploadDatasetArchive(server.url("/upload").toString(), "FAKE_UPLOAD_TOKEN", dataset, dataset.path)
        assertTrue(result.success)
        assertEquals(201, result.statusCode)

        val request = server.takeRequest(RESPONSE_WAIT_SECONDS, TimeUnit.SECONDS)!!
        assertEquals("Bearer FAKE_UPLOAD_TOKEN", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
        val body = request.body!!.utf8()
        assertTrue(body.contains("name=\"dataset_name\"\r\n\r\nsynthetic-collection"))
        assertTrue(body.contains("name=\"directory\"\r\n\r\nSynthetic"))
        assertTrue(body.contains("name=\"subdirectory\"\r\n\r\nPrivate"))
        assertTrue(body.contains("synthetic archive payload"))
    }

    @Test
    fun rejectedUploadKeepsStatusAndErrorDetails() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("synthetic permission denied").build())
        val dataset = dataset("rejected-collection")

        val result = uploadDatasetArchive(server.url("/upload").toString(), "", dataset, dataset.path)
        assertFalse(result.success)
        assertEquals(403, result.statusCode)
        assertTrue(result.message.contains("synthetic permission denied"))
    }

    @Test
    fun cancelledBatchDoesNotUploadNextCollectionOrRecordSuccessAfterDelayedResponse() = runBlocking {
        holdResponses()
        val synced = mutableListOf<String>()
        val collections = listOf(dataset("first-collection"), dataset("second-collection"))
        val batch = launch(Dispatchers.IO) {
            for (collection in collections) {
                val result = uploadDatasetArchive(server.url("/upload").toString(), "", collection, collection.path)
                if (result.success) synced += collection.name
            }
        }

        try {
            assertTrue(server.takeRequest(RESPONSE_WAIT_SECONDS, TimeUnit.SECONDS) != null)
            batch.cancel()
            releaseResponse.countDown()
            withTimeout(3_000) { batch.join() }

            assertTrue(batch.isCancelled)
            assertEquals("Cancellation must prevent a second upload", 1, server.requestCount)
            assertTrue("A response released after cancellation must not record success", synced.isEmpty())
        } finally {
            releaseResponse.countDown()
            batch.cancel()
        }
    }

    @Test
    fun cancellationStopsWithoutWaitingForServerToReleaseResponse() = runBlocking {
        holdResponses()
        val collection = dataset("stalled-collection")
        val upload = launch(Dispatchers.IO) {
            uploadDatasetArchive(server.url("/upload").toString(), "", collection, collection.path)
        }
        try {
            assertTrue(server.takeRequest(RESPONSE_WAIT_SECONDS, TimeUnit.SECONDS) != null)
            upload.cancel()
            withTimeout(3_000) { upload.join() }
            assertTrue(upload.isCancelled)
            assertEquals(1L, releaseResponse.count)
        } finally {
            releaseResponse.countDown()
            upload.cancel()
        }
    }

    private fun holdResponses() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(releaseResponse.await(RESPONSE_WAIT_SECONDS, TimeUnit.SECONDS))
                return MockResponse.Builder().code(200).build()
            }
        }
    }

    private fun dataset(name: String): DatasetSummary {
        val archive = temporaryFolder.newFile("$name.zip").apply { writeText("synthetic archive payload") }
        return DatasetSummary(
            name = name, path = archive, isZip = true, sizeBytes = archive.length(),
            formattedSize = "synthetic", lastModifiedMillis = 0, fileCount = 1,
            fileList = emptyList(), metadataMap = null, directory = "Synthetic", subdirectory = "Private"
        )
    }
}
