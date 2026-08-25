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
        val keyBytes = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
        val ivBytes = "5259228356829423".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val cleanEncrypted = encrypted.trim().replace(Regex("\\s+"), "")

        val decoded = try {
            Base64.getMimeDecoder().decode(cleanEncrypted)
        } catch (e: IllegalArgumentException) {
            decodeBase64Lenient(cleanEncrypted)
        }

        val decrypted = cipher.doFinal(decoded)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun decryptVidBasicSubtitle(vttContent: String): String {
        val patterns = listOf(
            Regex("""^WEBVTT"""),
            Regex("""^\d+$"""),
            Regex("""^\d{2}:\d{2}:\d{2}""")
        )
        return vttContent.lines().mapIndexed { _, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || patterns.any { it.containsMatchIn(trimmed) }) {
                line
            } else {
                try {
                    val decrypted = decryptVidBasic(trimmed)
                    decrypted.replace(Regex("""[\u0000-\u0008\u000B-\u001F\uFEFF]"""), "").trim()
                } catch (e: Exception) {
                    line
                }
            }
        }.joinToString("\n")
    }

    private suspend fun processVidBasic(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anySuccess = false

        try {
            val host = java.net.URL(embedUrl).host
            val headersMap = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://$host/",
                "Origin" to "https://$host"
            )

            val response = app.get(embedUrl, headers = headersMap)
            val html = response.text

            val dataVideoRegex = Regex("""data-video="([^"]+)">Standard""")
            var dataVideo = dataVideoRegex.find(html)?.groupValues?.get(1)
            
            if (dataVideo.isNullOrEmpty()) {
                val doc = org.jsoup.Jsoup.parse(html)
                dataVideo = doc.selectFirst("li[data-video]:contains(Standard)")?.attr("data-video")
                    ?: doc.selectFirst("[data-video]")?.attr("data-video")
            }

            if (!dataVideo.isNullOrEmpty()) {
                val fullUrl = when {
                    dataVideo.startsWith("http") -> dataVideo
                    dataVideo.startsWith("//") -> "https:$dataVideo"
                    else -> "https://$host$dataVideo"
                }

                val html2 = app.get(fullUrl, headers = headersMap).text

                // ---- SUBTITLE ----
                val subParam = Regex("""[\?&]sub=([^&"'>]+)""").let {
                    it.find(fullUrl)?.groupValues?.get(1) ?: it.find(embedUrl)?.groupValues?.get(1)
                }

                if (!subParam.isNullOrEmpty()) {
                    try {
                        val decodedSubParam = URLDecoder.decode(subParam, "UTF-8")
                        val decryptedSubUrl = decryptVidBasic(decodedSubParam)
                        
                        if (decryptedSubUrl.startsWith("http")) {
                            val encryptedVtt = app.get(decryptedSubUrl, headers = headersMap).text
                            val decryptedVtt = decryptVidBasicSubtitle(encryptedVtt)

                            var cleanVtt = decryptedVtt.replace("\r\n", "\n").replace("\r", "\n").trim()
                            if (cleanVtt.startsWith("WEBVTT")) {
                                cleanVtt = cleanVtt.substring(6).trim()
                            }
                            val finalVtt = "WEBVTT\n\n$cleanVtt"

                            if (finalVtt.isNotBlank()) {
                                var subtitleUrl = ""

                                // === 1. Upload ke Catbox menggunakan app.post ===
                                try {
                                    val boundary = "----CloudStreamBoundary${System.currentTimeMillis()}"
                                    val bodyString = """
                                        --$boundary
                                        Content-Disposition: form-data; name="reqtype"

                                        fileupload
                                        --$boundary
                                        Content-Disposition: form-data; name="time"

                                        1h
                                        --$boundary
                                        Content-Disposition: form-data; name="fileToUpload"; filename="sub.vtt"
                                        Content-Type: text/vtt

                                        $finalVtt
                                        --$boundary--
                                    """.trimIndent().replace("\n", "\r\n")

                                    val uploadHeaders = mapOf(
                                        "Content-Type" to "multipart/form-data; boundary=$boundary"
                                    )
                                    val uploadResponse = app.post("https://catbox.moe/user/api.php", headers = uploadHeaders, requestBody = bodyString).text.trim()
                                    println("[VidBasic] Catbox response: $uploadResponse")

                                    if (uploadResponse.startsWith("http")) {
                                        subtitleUrl = uploadResponse
                                        println("[VidBasic] Upload successful: $subtitleUrl")
                                    } else {
                                        println("[VidBasic] Catbox upload failed, response: $uploadResponse")
                                    }
                                } catch (e: Exception) {
                                    println("[VidBasic] Catbox upload error: ${e.message}")
                                }

                                // === 2. Jika Catbox gagal, coba Litterbox ===
                                if (subtitleUrl.isBlank()) {
                                    try {
                                        val boundary = "----CloudStreamBoundary${System.currentTimeMillis()}"
                                        val bodyString = """
                                            --$boundary
                                            Content-Disposition: form-data; name="reqtype"

                                            fileupload
                                            --$boundary
                                            Content-Disposition: form-data; name="time"

                                            1h
                                            --$boundary
                                            Content-Disposition: form-data; name="fileToUpload"; filename="sub.vtt"
                                            Content-Type: text/vtt

                                            $finalVtt
                                            --$boundary--
                                        """.trimIndent().replace("\n", "\r\n")

                                        val uploadHeaders = mapOf(
                                            "Content-Type" to "multipart/form-data; boundary=$boundary"
                                        )
                                        val uploadResponse = app.post("https://litterbox.catbox.moe/api", headers = uploadHeaders, requestBody = bodyString).text.trim()
                                        println("[VidBasic] Litterbox response: $uploadResponse")

                                        if (uploadResponse.startsWith("http")) {
                                            subtitleUrl = uploadResponse
                                            println("[VidBasic] Litterbox upload successful: $subtitleUrl")
                                        } else {
                                            println("[VidBasic] Litterbox upload failed, response: $uploadResponse")
                                        }
                                    } catch (e: Exception) {
                                        println("[VidBasic] Litterbox upload error: ${e.message}")
                                    }
                                }

                                // === 3. Fallback: Simpan ke file lokal ===
                                if (subtitleUrl.isBlank()) {
                                    try {
                                        val subFile = File.createTempFile("sub_", ".vtt")
                                        subFile.writeText(finalVtt)
                                        subFile.setReadable(true, false)
                                        subtitleUrl = "file://${subFile.absolutePath}"
                                        println("[VidBasic] Subtitle saved to local file: $subtitleUrl")
                                    } catch (e: Exception) {
                                        println("[VidBasic] Local file save error: ${e.message}")
                                        // === 4. Fallback terakhir: data URI ===
                                        val vttBase64 = Base64.getEncoder().encodeToString(finalVtt.toByteArray(Charsets.UTF_8))
                                        subtitleUrl = "data:text/vtt;charset=utf-8;base64,$vttBase64"
                                        println("[VidBasic] Using data URI (length: ${subtitleUrl.length})")
                                    }
                                }

                                // === Kirim subtitle ===
                                if (subtitleUrl.isNotBlank()) {
                                    subtitleCallback.invoke(newSubtitleFile("en", subtitleUrl))
                                    println("[VidBasic] Subtitle sent via ${subtitleUrl.take(20)}...")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[VidBasic] Subtitle processing error: ${e.message}")
                    }
                }

                // ---- VIDEO ----
                val cryptoRegex = Regex("""data-name="crypto"\s*data-value="([^"]+)"""")
                val encrypted = cryptoRegex.find(html2)?.groupValues?.get(1)

                if (!encrypted.isNullOrEmpty()) {
                    try {
                        val decrypted = decryptVidBasic(encrypted)

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
                        }
                    } catch (e: Exception) {
                        println("[VidBasic] Video decryption error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("[VidBasic] Main process error: ${e.message}")
            e.printStackTrace()
        }

        // ---- JSON API FALLBACK ----
        try {
            val apiUrl = if (embedUrl.contains("?")) "$embedUrl&json=" else "$embedUrl?json="
            val response = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent))
            val jsonText = response.text
            
            // Handle kemungkinan response berupa array kosong atau object
            val json = if (jsonText.startsWith("[")) {
                JSONObject().apply { put("data", org.json.JSONArray(jsonText)) }
            } else {
                JSONObject(jsonText)
            }
            
            val keys = json.keys().asSequence().toList()
            
            for (key in keys) {
                val value = json.optString(key, null)
                if (!value.isNullOrEmpty() && (value.startsWith("http") || value.startsWith("//"))) {
                    val fixedLink = fixUrlScheme(value)
                    val result = loadExtractor(fixedLink, subtitleCallback, callback)
                    if (result) anySuccess = true
                }
            }
        } catch (e: Exception) {
            println("[VidBasic] JSON API error: ${e.message}")
        }

        return anySuccess
    }

    // ==================== LOAD (DEBUGGING MODE) ====================
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
        } ?: ""

        val episodeItems = document.select("ul.list-episode-item-2.all-episode li a")
        val episodeRegex = Regex("""(?i)(?:Episode|EP|E)\s*(\d+(?:\.\d+)?)""")

        val episodes = episodeItems.mapNotNull { el ->
            val titleText = el.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
            val link = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            
            val epMatch = episodeRegex.find(titleText)
            val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(titleText) {
                this.data = link
                this.episode = epNum
            }
        }.sortedByDescending { it.episode ?: 0 }

        // ==========================================
        // BLOK DEBUGGING: INJEKSI LOG KE PLOT
        // ==========================================
        try {
            val testEp = episodes.lastOrNull()?.data
            if (testEp != null) {
                val epDoc = app.get(testEp, headers = mapOf("User-Agent" to userAgent)).document
                var embedUrl = ""
                epDoc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    if (src.contains("vidbasic") || src.contains("vidb")) embedUrl = fixUrlScheme(src)
                }

                if (embedUrl.isNotBlank()) {
                    val host = java.net.URL(embedUrl).host
                    val headersMap = mapOf("User-Agent" to userAgent, "Referer" to "https://$host/", "Origin" to "https://$host")
                    val html = app.get(embedUrl, headers = headersMap).text
                    
                    val dataVideoRegex = Regex("""data-video="([^"]+)">Standard""")
                    var dataVideo = dataVideoRegex.find(html)?.groupValues?.get(1)
                    if (dataVideo.isNullOrEmpty()) {
                        val parsed = org.jsoup.Jsoup.parse(html)
                        dataVideo = parsed.selectFirst("li[data-video]:contains(Standard)")?.attr("data-video")
                            ?: parsed.selectFirst("[data-video]")?.attr("data-video")
                    }

                    if (!dataVideo.isNullOrEmpty()) {
                        val fullUrl = when {
                            dataVideo.startsWith("http") -> dataVideo
                            dataVideo.startsWith("//") -> "https:$dataVideo"
                            else -> "https://$host$dataVideo"
                        }

                        val subParam = Regex("""[\?&]sub=([^&"'>]+)""").let {
                            it.find(fullUrl)?.groupValues?.get(1) ?: it.find(embedUrl)?.groupValues?.get(1)
                        }

                        if (!subParam.isNullOrEmpty()) {
                            val decodedSubParam = URLDecoder.decode(subParam, "UTF-8")
                            val decryptedSubUrl = decryptVidBasic(decodedSubParam)

                            if (decryptedSubUrl.startsWith("http")) {
                                val encryptedVtt = app.get(decryptedSubUrl, headers = headersMap).text
                                val decryptedVtt = decryptVidBasicSubtitle(encryptedVtt)
                                var cleanVtt = decryptedVtt.replace("\r\n", "\n").replace("\r", "\n").trim()
                                if (cleanVtt.startsWith("WEBVTT")) cleanVtt = cleanVtt.substring(6).trim()
                                val finalVtt = "WEBVTT\n\n$cleanVtt"
                                
                                description += "\n\n=== SUBTITLE PREVIEW ===\n${finalVtt.take(300)}..."
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            description += "\n\n[!] Debug error: ${e.message}"
        }
        // ==========================================

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
