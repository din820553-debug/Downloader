package com.dindownloader.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NativeEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var host: String
    private val pages = ConcurrentHashMap<String, Pair<String, String>>()
    @Volatile private var rangedBody: ByteArray? = null

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?') ?: "/"
                if (path == "/video.mp4" && rangedBody != null) {
                    val full = rangedBody!!
                    val wanted = request.getHeader("Range")
                    val offset = if (wanted == "bytes=5-") 5 else 0
                    return MockResponse().setResponseCode(if (offset == 0) 200 else 206)
                        .addHeader("Content-Type", "video/mp4")
                        .apply {
                            if (offset > 0) {
                                addHeader("Content-Range", "bytes $offset-11/12")
                            }
                        }
                        .setBody(okio.Buffer().write(full.copyOfRange(offset, full.size)))
                }
                val item = pages[path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", item.first)
                    .setBody(item.second)
            }
        }
        server.start()
        host = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun endpoint(path: String, type: String, body: String) {
        pages[path] = type to body
    }

    @Test
    fun savesIntoOriginSiteFolder() {
        assertEquals("Download/Downloader/youtube.com",
            SiteFolders.relativePath("https://www.youtube.com/watch?v=x"))
        assertEquals("Download/Downloader/twitch.tv",
            SiteFolders.relativePath("https://m.twitch.tv/clip/a"))
        assertEquals("Download/Downloader/example.org",
            SiteFolders.relativePath("https://example.org/video.mp4"))
    }

    @Test
    fun extractsVideoFromHtml() {
        val html = """<meta property="og:video" content="/media/movie.mp4">
            <video><source src="https://cdn.example.org/video.m3u8"></video>"""
        val values = NativeEngine.extractCandidates("https://example.com/post", html)
        assertTrue(values.contains("https://example.com/media/movie.mp4"))
        assertTrue(values.contains("https://cdn.example.org/video.m3u8"))
    }

    @Test
    fun resumesTransferByHttpRange() {
        val bytes = "ABCDEFGHIJKL".toByteArray()
        rangedBody = bytes
        val url = "$host/video.mp4"
        val temp = Files.createTempDirectory("downloader-resume").toFile()
        try {
            java.io.File(temp,"media.part").writeBytes(bytes.copyOfRange(0,5))
            java.io.File(temp,"media.resume.json").writeText(
                JSONObject().put("source",url).put("total",12L).toString()
            )
            var observed = 0L
            val result = NativeTransfer().transfer(MediaInfo(url,url,"movie",
                estimatedBytes=12L), 0, temp) { count, _, _ -> observed = count }
            assertArrayEquals(bytes, result.file.readBytes())
            assertEquals(12L, observed)
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun selectsHlsVariantAndConcatenatesSegments() {
        endpoint("/master.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=9000,RESOLUTION=1920x1080\nhigh.m3u8\n")
        endpoint("/low.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:4,\nfirst.ts\n" +
            "#EXTINF:4,\nsecond.ts\n#EXT-X-ENDLIST\n")
        endpoint("/high.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXTINF:4,\nhigh.ts\n#EXT-X-ENDLIST\n")
        endpoint("/first.ts","video/mp2t","ABC")
        endpoint("/second.ts","video/mp2t","DEF")
        endpoint("/high.ts","video/mp2t","HIGH")
        val url = "$host/master.m3u8"
        val temp = Files.createTempDirectory("downloader-hls").toFile()
        try {
            val result = NativeTransfer().transfer(MediaInfo(url,url,"stream",kind="hls"),
                720, temp) { _, _, _ -> }
            assertEquals("ABCDEF", result.file.readText())
            assertEquals("video/mp2t", result.mime)
        } finally { temp.deleteRecursively() }
    }
}
