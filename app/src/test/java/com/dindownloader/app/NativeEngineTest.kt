package com.dindownloader.app

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NativeEngineTest {
    private lateinit var server: HttpServer
    private lateinit var host: String

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        host = "http://127.0.0.1:" + server.address.port
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun endpoint(path: String, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        server.createContext(path) { x ->
            x.responseHeaders.add("Content-Type", type)
            val head = x.requestMethod == "HEAD"
            x.sendResponseHeaders(200, if (head) -1 else bytes.size.toLong())
            if (!head) x.responseBody.use { it.write(bytes) }
            else x.close()
        }
    }

    @Test
    fun storesByOriginalWebsite() {
        assertEquals("Download/Downloader/youtube.com",
            SiteFolders.relativePath("https://www.youtube.com/watch?v=x"))
        assertEquals("Download/Downloader/twitch.tv",
            SiteFolders.relativePath("https://m.twitch.tv/clip/a"))
        assertEquals("Download/Downloader/example.org",
            SiteFolders.relativePath("https://example.org/film.mp4"))
    }

    @Test
    fun findsDirectMediaFromHtml() {
        val page = """<meta property="og:video" content="/media/movie.mp4">
            <video><source src="https://cdn.example.org/video.m3u8"></video>"""
        val result = NativeEngine.extractCandidates("https://example.com/post", page)
        assertTrue(result.contains("https://example.com/media/movie.mp4"))
        assertTrue(result.contains("https://cdn.example.org/video.m3u8"))
    }

    @Test
    fun resumesFileUsingByteRange() {
        val full = "ABCDEFGHIJKL".toByteArray()
        server.createContext("/video.mp4") { x ->
            val range = x.requestHeaders.getFirst("Range")
            val start = if (range == "bytes=5-") 5 else 0
            val part = full.copyOfRange(start, full.size)
            x.responseHeaders.add("Content-Type", "video/mp4")
            if (start > 0) x.responseHeaders.add(
                "Content-Range", "bytes $start-11/12"
            )
            x.sendResponseHeaders(if (start == 0) 200 else 206, part.size.toLong())
            x.responseBody.use { it.write(part) }
        }
        server.start()
        val dir = Files.createTempDirectory("resume-native").toFile()
        try {
            val link = "$host/video.mp4"
            java.io.File(dir, "media.part").writeBytes(full.copyOfRange(0,5))
            java.io.File(dir, "media.resume.json").writeText(
                JSONObject().put("source", link).put("total", 12L).toString()
            )
            var finalBytes = 0L
            val output = NativeTransfer().transfer(
                MediaInfo(link, link, "video", estimatedBytes=12L),
                0, dir
            ) { received, _, _ -> finalBytes = received }
            assertArrayEquals(full, output.file.readBytes())
            assertEquals(12L, finalBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun choosesHlsQualityAndJoinsSegments() {
        endpoint("/master.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=9000,RESOLUTION=1920x1080\nhigh.m3u8\n")
        endpoint("/low.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:4,\nfirst.ts\n" +
            "#EXTINF:4,\nsecond.ts\n#EXT-X-ENDLIST\n")
        endpoint("/high.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXTINF:4,\nhigh.ts\n#EXT-X-ENDLIST\n")
        endpoint("/first.ts", "video/mp2t", "ABC")
        endpoint("/second.ts", "video/mp2t", "DEF")
        endpoint("/high.ts", "video/mp2t", "HIGH")
        server.start()
        val dir = Files.createTempDirectory("hls-native").toFile()
        try {
            val url = "$host/master.m3u8"
            val output = NativeTransfer().transfer(
                MediaInfo(url,url,"stream",kind="hls"), 720, dir
            ) { _, _, _ -> }
            assertEquals("ABCDEF", output.file.readText())
            assertEquals("video/mp2t", output.mime)
        } finally { dir.deleteRecursively() }
    }
}
