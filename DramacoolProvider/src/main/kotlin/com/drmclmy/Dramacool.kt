package com.drmclmy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.File

class Dramacool : MainAPI() {
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://dramacool.my"
    override var name = "Dramacool"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "recently-added" to "Recently Added",
        "recently-added-movie" to "Recently Added Movies",
        "most-popular-drama" to "Most Popular",
        "popular-ongoing-series" to "Ongoing Series",
        "popular-completed-series" to "Completed Series"
    )

    private fun fixUrlScheme(url: String): String {
        var fixed = url.trim()
        if (fixed.startsWith("//")) {
            fixed = "https:$fixed"
        }
        return fixed
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}?page=$page"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val items = document.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun convertToSeriesLink(episodeLink: String): String {
        val slug = episodeLink
            .substringAfterLast("/")
            .replace(Regex("-episode-\\d+\\.html$"), "")
        return "$mainUrl/drama-detail/$slug"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3.title")?.text()?.trim() ?: return null
        val episodeLink = fixUrlNull(attr("href")) ?: return null
        val seriesLink = convertToSeriesLink(episodeLink)
        val img = selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-original") ?: img?.attr("src"))
        return newAnimeSearchResponse(title, seriesLink, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?type=movies&keyword=${query.replace(" ", "+")}"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        return document.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
    }

    // ==================== EKSTRAKSI VIDEO ====================
    private fun unescapeJs(str: String): String {
        return str.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun decodeBase64IfPossible(str: String): String {
        return try {
            val decoded = String(Base64.getDecoder().decode(str))
            if (decoded.isNotBlank()) decoded else str
        } catch (e: Exception) { str }
    }

    private fun extractVideoUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/playlist\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/manifest\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/master\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/index\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/stream\.m3u8[^\s"'<>]*""")
        )
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val url = match.value
                if (url.isNotBlank()) urls.add(unescapeJs(url))
            }
        }
        return urls.distinct()
    }

    private fun decodeBase64Lenient(input: String): ByteArray {
        var base64 = input.trim().replace(Regex("\\s+"), "")
        while (base64.length % 4 != 0) {
            base64 += "="
        }
        return Base64.getMimeDecoder().decode(base64)
    }

    private fun decryptVidBasic(encrypted: String): String {
        println("[VidBasic] decryptVidBasic input length: ${encrypted.length}")
        val keyBytes = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
        val ivBytes = "5259228356829423".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val cleanEncrypted = encrypted.trim().replace(Regex("\\s+"), "")
        println("[VidBasic] cleanEncrypted length: ${cleanEncrypted.length}")

        val decoded = try {
            Base64.getMimeDecoder().decode(cleanEncrypted)
        } catch (e: IllegalArgumentException) {
            println("[VidBasic] MimeDecoder failed, using lenient fallback...")
            decodeBase64Lenient(cleanEncrypted)
        }
        println("[VidBasic] Base64 decoded length: ${decoded.size} bytes")

        val decrypted = cipher.doFinal(decoded)
        val result = String(decrypted, Charsets.UTF_8)
        println("[VidBasic] Decrypted result: ${result.take(100)}...")
        return result
    }

    private fun decryptVidBasicSubtitle(vttContent: String): String {
        println("[VidBasic] decryptVidBasicSubtitle input lines: ${vttContent.lines().size}")
        val patterns = listOf(
            Regex("""^WEBVTT"""),
            Regex("""^\d+$"""),
            Regex("""^\d{2}:\d{2}:\d{2}""")
        )
        val result = vttContent.lines().mapIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || patterns.any { it.containsMatchIn(trimmed) }) {
                line
            } else {
                try {
                    decryptVidBasic(trimmed)
                } catch (e: Exception) {
                    println("[VidBasic] Failed to decrypt line $index: ${e.message}")
                    line
                }
            }
        }.joinToString("\n")
        println("[VidBasic] Subtitle final length: ${result.length}")
        return result
    }

    // Variabel untuk menyimpan subtitle 5 baris pertama (untuk ditampilkan di plot)
    private var subtitlePreview = ""

    private suspend fun processVidBasic(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val log = StringBuilder()
        log.append("WEBVTT\n\n")
        log.append("=== VidBasic Debug Log ===\n")
        log.append("Timestamp: ${System.currentTimeMillis()}\n")
        log.append("Embed URL: $embedUrl\n\n")
        var anySuccess = false
        subtitlePreview = ""

        try {
            val host = java.net.URL(embedUrl).host
            val headersMap = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://$host/",
                "Origin" to "https://$host"
            )
            log.append("Host: $host\n")
            log.append("Headers: $headersMap\n\n")

            val response = app.get(embedUrl, headers = headersMap)
            val html = response.text
            log.append("Fetched embed page, length: ${html.length}\n")
            log.append("First 500 chars of HTML:\n${html.take(500)}\n\n")

            val dataVideoRegex = Regex("""data-video="([^"]+)">Standard""")
            var dataVideo = dataVideoRegex.find(html)?.groupValues?.get(1)
            log.append("dataVideo from regex: $dataVideo\n")
            
            if (dataVideo.isNullOrEmpty()) {
                val doc = org.jsoup.Jsoup.parse(html)
                dataVideo = doc.selectFirst("li[data-video]:contains(Standard)")?.attr("data-video")
                    ?: doc.selectFirst("[data-video]")?.attr("data-video")
                log.append("dataVideo from JSoup: $dataVideo\n")
            }

            if (!dataVideo.isNullOrEmpty()) {
                val fullUrl = when {
                    dataVideo.startsWith("http") -> dataVideo
                    dataVideo.startsWith("//") -> "https:$dataVideo"
                    else -> "https://$host$dataVideo"
                }
                log.append("Full video URL: $fullUrl\n\n")

                val html2 = app.get(fullUrl, headers = headersMap).text
                log.append("Video page length: ${html2.length}\n")
                log.append("First 500 chars of video page:\n${html2.take(500)}\n\n")

                // ---- SUBTITLE ----
                val subParam = Regex("""[\?&]sub=([^&"'>]+)""").let {
                    it.find(fullUrl)?.groupValues?.get(1) ?: it.find(embedUrl)?.groupValues?.get(1)
                }
                log.append("subParam found: $subParam\n")

                if (!subParam.isNullOrEmpty()) {
                    try {
                        val decodedSubParam = URLDecoder.decode(subParam, "UTF-8")
                        log.append("Decoded subParam: $decodedSubParam\n")
                        val decryptedSubUrl = decryptVidBasic(decodedSubParam)
                        log.append("Decrypted sub URL: $decryptedSubUrl\n")
                        if (decryptedSubUrl.startsWith("http")) {
                            log.append("Fetching encrypted VTT from: $decryptedSubUrl\n")
                            val encryptedVtt = app.get(decryptedSubUrl, headers = headersMap).text
                            log.append("Encrypted VTT length: ${encryptedVtt.length}\n")
                            
                            val decryptedVtt = decryptVidBasicSubtitle(encryptedVtt)
                            log.append("Decrypted VTT length: ${decryptedVtt.length}\n")

                            // Normalisasi
                            val normalizedVtt = decryptedVtt
                                .replace("\r\n", "\n")
                                .replace("\r", "\n")
                                .trim()
                            log.append("Normalized VTT (first 500 chars):\n${normalizedVtt.take(500)}\n")

                            // Simpan 5 baris pertama subtitle untuk ditampilkan di plot
                            val lines = normalizedVtt.lines()
                            subtitlePreview = lines.take(5).joinToString("\n")
                            log.append("Subtitle preview (5 lines):\n$subtitlePreview\n")

                            if (normalizedVtt.isNotBlank() && normalizedVtt.startsWith("WEBVTT")) {
                                // Simpan ke file sementara
                                val subFile = File.createTempFile("sub_vidbasic", ".vtt")
                                subFile.writeText(normalizedVtt)
                                val fileUri = "file://${subFile.absolutePath}"
                                log.append("Subtitle saved to: $fileUri\n")
                                log.append("File size: ${subFile.length()} bytes\n")

                                subtitleCallback.invoke(SubtitleFile("English (VidBasic)", fileUri))
                                log.append("✅ Subtitle callback invoked\n")
                            } else {
                                log.append("❌ Decrypted VTT does not start with WEBVTT\n")
                            }
                        } else {
                            log.append("❌ Decrypted sub URL is not HTTP: $decryptedSubUrl\n")
                        }
                    } catch (e: Exception) {
                        log.append("❌ Subtitle processing error: ${e.message}\n")
                        log.append("Stack trace:\n${e.stackTraceToString()}\n")
                    }
                } else {
                    log.append("ℹ️ No sub parameter found\n")
                }

                // ---- VIDEO ----
                val cryptoRegex = Regex("""data-name="crypto"\s*data-value="([^"]+)"""")
                val encrypted = cryptoRegex.find(html2)?.groupValues?.get(1)
                log.append("Encrypted video data: ${encrypted?.take(50)}...\n")

                if (!encrypted.isNullOrEmpty()) {
                    try {
                        val decrypted = decryptVidBasic(encrypted)
                        log.append("Decrypted video URL: $decrypted\n")

                        if (decrypted.startsWith("http")) {
                            val isM3u8 = decrypted.contains(".m3u8")
                            callback(
                                newExtractorLink(
                                    name = if (isM3u8) "VidBasic - HLS" else "VidBasic - Direct",
                                    source = name,
                                    url = decrypted,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = fullUrl
                                    this.quality = 0
                                    this.headers = headersMap
                                }
                            )
                            anySuccess = true
                            log.append("✅ Video link added successfully\n")
                        } else {
                            log.append("❌ Decrypted video is not HTTP: $decrypted\n")
                        }
                    } catch (e: Exception) {
                        log.append("❌ Video decryption error: ${e.message}\n")
                        log.append("Stack trace:\n${e.stackTraceToString()}\n")
                    }
                } else {
                    log.append("ℹ️ No crypto data found\n")
                }
            } else {
                log.append("❌ dataVideo not found\n")
            }
        } catch (e: Exception) {
            log.append("❌ Exception in main process: ${e.message}\n")
            log.append("Stack trace:\n${e.stackTraceToString()}\n")
        }

        // ---- JSON API FALLBACK ----
        try {
            val apiUrl = if (embedUrl.contains("?")) "$embedUrl&json=" else "$embedUrl?json="
            log.append("\nTrying JSON API: $apiUrl\n")
            val response = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent))
            val jsonText = response.text
            log.append("JSON response length: ${jsonText.length}\n")
            
            val json = JSONObject(jsonText)
            val keys = json.keys().asSequence().toList()
            log.append("JSON keys: $keys\n")
            
            for (key in keys) {
                val value = json.optString(key, null)
                if (!value.isNullOrEmpty() && (value.startsWith("http") || value.startsWith("//"))) {
                    val fixedLink = fixUrlScheme(value)
                    log.append("Loading extractor for: $fixedLink\n")
                    val result = loadExtractor(fixedLink, subtitleCallback, callback)
                    if (result) {
                        anySuccess = true
                        log.append("✅ JSON extractor succeeded for $fixedLink\n")
                    }
                }
            }
        } catch (e: Exception) {
            log.append("❌ JSON API error: ${e.message}\n")
        }

        log.append("\n=== Final result: $anySuccess ===\n")
        log.append("=== End of debug log ===\n")

        // Kirim subtitle debug
        val finalLog = log.toString()
        try {
            val debugFile = File.createTempFile("sub_debug", ".vtt")
            debugFile.writeText(finalLog)
            val debugUri = "file://${debugFile.absolutePath}"
            subtitleCallback.invoke(SubtitleFile("VidBasic Debug", debugUri))
        } catch (e: Exception) {
            // Fallback ke data URI
            val logBase64 = Base64.getEncoder().encodeToString(finalLog.toByteArray(Charsets.UTF_8))
            val dataUri = "data:text/vtt;charset=utf-8;base64,$logBase64"
            subtitleCallback.invoke(SubtitleFile("VidBasic Debug", dataUri))
        }

        return anySuccess
    }

    // ==================== LOAD ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title = document.selectFirst(".details .info h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val posterUrl = document.selectFirst(".details .img img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }

        var description = document.select(".details .info p").mapNotNull { p ->
            if (p.select("span").isEmpty() && p.text().length > 50) {
                p.text().trim()
            } else null
        }.joinToString("\n\n").ifEmpty {
            document.select(".details .info").first()?.text()?.substringAfter("Description:")?.trim()
        }

        // Tambahkan preview subtitle ke description jika ada
        if (subtitlePreview.isNotBlank()) {
            description = (description ?: "") + "\n\n--- Subtitle Preview (5 lines) ---\n$subtitlePreview"
        }

        val episodeItems = document.select("ul.list-episode-item-2.all-episode li a")
        
        val episodeRegex = Regex("""(?i)(?:Episode|EP|E)\s*(\d+(?:\.\d+)?)""")

        val episodes = episodeItems.mapNotNull { el ->
            val titleText = el.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
            val link = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            
            val epMatch = episodeRegex.find(titleText)
            val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()

            Triple(titleText, link, epNum)
        }.sortedByDescending { it.third ?: 0 }.map { (titleText, link, epNum) ->
            newEpisode(titleText) {
                this.data = link
                this.episode = epNum
            }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val docRes = app.get(data, headers = mapOf("User-Agent" to userAgent))
        val html = docRes.text
        val doc = docRes.document

        val allUrls = mutableSetOf<String>()

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(fixUrlScheme(src))
        }

        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(fixUrlScheme(src))
        }

        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(fixUrlScheme(video))
        }

        doc.select("script").forEach { script ->
            var scriptData = script.data()
            try {
                if (scriptData.contains("eval(") || scriptData.contains("pako") || scriptData.contains("atob")) {
                    val unpacked = getAndUnpack(scriptData)
                    if (unpacked.isNotBlank()) scriptData = unpacked
                }
            } catch (e: Exception) {}
            
            val decoded = decodeBase64IfPossible(scriptData)
            if (decoded != scriptData) scriptData = decoded
            
            extractVideoUrls(scriptData).forEach { url -> allUrls.add(fixUrlScheme(url)) }
            
            val jsonPattern = Regex("""(\{.*?(?:file|src|video|url)\s*:\s*"[^"]+".*?\})""")
            jsonPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (!file.isNullOrBlank()) allUrls.add(fixUrlScheme(file))
                } catch (e: Exception) {}
            }
        }

        extractVideoUrls(html).forEach { url -> allUrls.add(fixUrlScheme(url)) }

        var linkFound = false

        for (rawUrl in allUrls) {
            val cleanUrl = fixUrlScheme(rawUrl)
            if (!cleanUrl.startsWith("http")) continue

            if (cleanUrl.contains("vidbasic.top") || cleanUrl.contains("vidb.top")) {
                val result = processVidBasic(cleanUrl, subtitleCallback, callback)
                if (result) linkFound = true
                continue
            }

            if (loadExtractor(cleanUrl, subtitleCallback, callback)) {
                linkFound = true
                continue
            }

            if (cleanUrl.contains(".m3u8") || cleanUrl.endsWith(".mp4")) {
                val isM3 = cleanUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        name = if (isM3) "Dramacool - HLS" else "Dramacool - MP4",
                        source = this.name,
                        url = cleanUrl,
                        type = if (isM3) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                linkFound = true
            }
        }

        return linkFound
    }
}
