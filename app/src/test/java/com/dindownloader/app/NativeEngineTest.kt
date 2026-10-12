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
import java.util.concurrent.TimeUnit

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
                if (path == "/never.mp4") {
                    return MockResponse().setResponseCode(200)
                        .addHeader("Content-Type", "video/mp4")
                        .setHeadersDelay(3, TimeUnit.SECONDS)
                        .setBody("late response")
                }
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
    fun choosesEmbeddedPlayerQuality() {
        val page = """<script>var player = {"mediaDefinitions":[
            {"quality":"720","videoUrl":"https://media.example/720.mp4"},
            {"quality":"360","videoUrl":"https://media.example/360.mp4"}]};</script>"""
        val links = NativeEngine.extractCandidates("https://site.example/watch", page, 360)
        assertTrue(links.isNotEmpty())
        assertEquals("https://media.example/360.mp4", links.first())
    }

    @Test
    fun recognizesTwitchVodsAndClips() {
        assertEquals("12345678", TwitchResolver.identify(
            "https://www.twitch.tv/videos/12345678")?.vod)
        assertEquals("HappyBraveSloth-Test", TwitchResolver.identify(
            "https://clips.twitch.tv/HappyBraveSloth-Test")?.clip)
        assertEquals("HappyBraveSloth-Test", TwitchResolver.identify(
            "https://www.twitch.tv/streamer/clip/HappyBraveSloth-Test")?.clip)
        assertNull(TwitchResolver.identify("https://not-twitch.tv/videos/12345678"))
    }

    @Test
    fun recognizesLegacyTwitchVodsFromScreenshot() {
        val old = "https://www.twitch.tv/steel/v/28944715"
        assertEquals("28944715", TwitchResolver.identify(old)?.vod)
        assertEquals("28944715", TwitchResolver.identify(
            "https://www.twitch.tv/steel/video/28944715")?.vod)
        assertEquals("28944715", TwitchResolver.identify(
            "https://player.twitch.tv/?video=v28944715")?.vod)
        assertEquals("28944715", TwitchResolver.identify(
            "https://www.twitch.tv/videos/28944715")?.vod)
        assertTrue(TwitchResolver.isTwitchUrl("https://www.twitch.tv/steel"))
        assertNull(TwitchResolver.identify("https://www.twitch.tv/steel"))
        assertFalse(TwitchResolver.isTwitchUrl("https://fake.twitch.tv.evil.example/videos/28944715"))
    }

    @Test
    fun scansEscapedUrlsInEmbeddedPlayerConfig() {
        val html = """<script>var player={"file":"https:\/\/cdn.example.com\/archive\/full.m3u8",
            "src":"https:\u002F\u002Fcdn.example.com\u002Ffull.mp4"};</script>"""
        val links = NativeEngine.extractCandidates("https://example.net/view/123", html)
        assertTrue(links.toString(), links.contains("https://cdn.example.com/archive/full.m3u8"))
        assertTrue(links.toString(), links.contains("https://cdn.example.com/full.mp4"))
    }

    @Test
    fun twitchClipChoosesQualityAndSignsUrl() {
        val data = JSONObject().put("clip", JSONObject()
            .put("title", "Test clip")
            .put("playbackAccessToken", JSONObject()
                .put("signature", "signature!").put("value", "{\"ok\":true}"))
            .put("videoQualities", org.json.JSONArray()
                .put(JSONObject().put("quality", "1080").put("sourceURL", "https://cdn.example/1080.mp4"))
                .put(JSONObject().put("quality", "720").put("sourceURL", "https://cdn.example/720.mp4"))))
        val selected = TwitchResolver.clipFromData("https://clips.twitch.tv/ExampleClip", data, 720)
        assertTrue(selected.mediaUrl.contains("/720.mp4"))
        assertTrue(selected.mediaUrl.contains("sig="))
        assertTrue(selected.mediaUrl.contains("token="))
        assertEquals("Test clip", selected.title)
    }

    @Test
    fun twitchVodProducesAuthenticatedHls() {
        val request = TwitchResolver.vodRequest("1122334455")
        assertTrue(request.getString("query").contains("videoPlaybackAccessToken"))
        val data = JSONObject().put("videoPlaybackAccessToken", JSONObject()
            .put("signature", "sigABC").put("value", "{\"chansub\":true}"))
        val media = TwitchResolver.vodFromData(
            "https://www.twitch.tv/videos/1122334455", "1122334455", data)
        assertTrue(media.mediaUrl.startsWith("https://usher.ttvnw.net/vod/1122334455.m3u8"))
        assertTrue(media.mediaUrl.contains("sig=sigABC"))
        assertEquals("hls", media.kind)
    }

    @Test
    fun hlsReportsBytesBeforeSegmentCompletes() {
        endpoint("/media.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\nlarge.ts\n#EXT-X-ENDLIST\n")
        val content = "X".repeat(240_000)
        endpoint("/large.ts", "video/mp2t", content)
        val temp = Files.createTempDirectory("hls-progress").toFile()
        try {
            var intermediate = false
            val link = "$host/media.m3u8"
            val finished = NativeTransfer().transfer(MediaInfo(link, link, "video", kind="hls"),
                0, temp) { bytes, _, percent ->
                if (bytes > 0 && bytes < content.length && percent < 100) intermediate = true
            }
            assertTrue("HLS progress must update during a segment", intermediate)
            assertEquals(content.length.toLong(), finished.file.length())
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun hlsMasterWithOptionalAudioDoesNotFailVideoVariant() {
        endpoint("/mixed.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"commentary\",URI=\"audio.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720\nmain.m3u8\n")
        endpoint("/main.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXTINF:4,\nvideo.ts\n#EXT-X-ENDLIST\n")
        endpoint("/video.ts", "video/mp2t", "DATA")
        val info = NativeEngine.playlist(
            MediaInfo("$host/mixed.m3u8", "$host/mixed.m3u8", "video", kind="hls"), 720)
        assertEquals(1, info.segments.size)
    }

    @Test
    fun parsesYouTubeUrlsAndPlayerResponse() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, YouTubeResolver.videoId("https://youtu.be/" + id))
        assertEquals(id, YouTubeResolver.videoId("https://www.youtube.com/watch?v=" + id))
        assertEquals(id, YouTubeResolver.videoId("https://youtube.com/shorts/" + id))
        assertNull(YouTubeResolver.videoId("https://fakeyoutube.com/watch?v=" + id))
        val fake = """<script>var ytInitialPlayerResponse = {
            "videoDetails":{"title":"Example {video}", "lengthSeconds":"238"},
            "streamingData":{"formats":[
              {"url":"https://cdn.example/low.mp4", "height":360,
               "mimeType":"video/mp4; codecs=\"avc1.4d401e,mp4a.40.2\"", "contentLength":"10000"},
              {"url":"https://cdn.example/high.mp4", "height":720,
               "mimeType":"video/mp4; codecs=\"avc1.4d401f,mp4a.40.2\"", "contentLength":"30000"}]}
            };</script>"""
        val response = YouTubeResolver.jsonObjectFollowing(fake, "ytInitialPlayerResponse")
        assertNotNull(response)
        val selected = YouTubeResolver.select(response!!, "https://youtu.be/" + id, 480)
        assertEquals("https://cdn.example/low.mp4", selected.mediaUrl)
        assertEquals(238L, selected.expectedDurationSeconds)
        assertEquals(10000L, selected.estimatedBytes)
    }

    @Test
    fun refusesToLabelHtmlAndJsonAsFinishedVideos() {
        assertFalse(MediaValidation.recognizable("<html><body>login</body></html>".toByteArray(), "video/mp4"))
        assertFalse(MediaValidation.recognizable("{\"status\":\"unauthorized\"}".toByteArray(), "video/mp4"))
        val mp4 = ByteArray(32)
        "ftyp".toByteArray().copyInto(mp4, 4)
        assertTrue(MediaValidation.recognizable(mp4, "video/mp4"))
        assertFalse(MediaValidation.recognizable(ByteArray(100) { 42 }, "video/mp4"))
    }

    @Test
    fun parsesUpdatedTwitchClipAssets() {
        val clip = JSONObject().put("clip", JSONObject()
            .put("title", "An actual clip")
            .put("playbackAccessToken", JSONObject().put("signature","ABC").put("value","abc"))
            .put("assets", org.json.JSONArray().put(JSONObject()
                .put("videoQualities", org.json.JSONArray()
                    .put(JSONObject().put("quality","720").put("sourceURL","https://cdn.test/720.mp4"))
                    .put(JSONObject().put("quality","480").put("sourceURL","https://cdn.test/480.mp4"))))))
        val selected = TwitchResolver.clipFromData("https://clips.twitch.tv/ExampleClip",clip,480)
        assertTrue(selected.mediaUrl.contains("/480.mp4"))
        assertEquals("An actual clip",selected.title)
        assertEquals("ShareClipRenderStatus",TwitchResolver.clipRequest("ExampleClip").getString("operationName"))
    }

    @Test
    fun refusesShortHlsTeaserWhenWholeVideoIsMuchLonger() {
        endpoint("/preview.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXTINF:4.0,\ntiny.ts\n#EXT-X-ENDLIST\n")
        val page = "$host/preview.m3u8"
        val temp = Files.createTempDirectory("reject-preview").toFile()
        try {
            val info = MediaInfo(page,page,"Full video",kind="hls",
                expectedDurationSeconds=180L)
            val thrown = runCatching {
                NativeTransfer().transfer(info, 720, temp) { _,_,_ -> }
            }.exceptionOrNull()
            assertTrue(thrown is java.io.IOException)
            assertTrue(thrown!!.message.orEmpty().contains("предпросмотр"))
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun metadataProbeStopsWhenServerDoesNotRespond() {
        val url = "$host/never.mp4"
        val began = System.nanoTime()
        val error = runCatching {
            NativeEngine.boundedProbe(url, 0, null, 650)
        }.exceptionOrNull()
        val elapsed = (System.nanoTime() - began) / 1_000_000
        assertNotNull("A stalled metadata server must not be treated as success", error)
        assertTrue("Probe should stop near the deadline, took " + elapsed + " ms",
            elapsed < 3000)
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
