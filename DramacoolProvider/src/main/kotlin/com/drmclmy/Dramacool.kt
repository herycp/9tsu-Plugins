package com.drmclmy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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

    private fun extractEpisodeNumber(title: String): Int? {
        val patterns = listOf(
            Regex("""(?i)Episode\s*(\d+)"""),
            Regex("""(?i)EP\s*(\d+)"""),
            Regex("""(?i)E(\d+)"""),
            Regex("""#(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null && num > 0) return num
            }
        }
        return null
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

    // AES Decrypt untuk VidBasic
    private fun decryptVidBasic(encrypted: String): String {
        val keyBytes = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
        val ivBytes = "5259228356829423".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val decoded = Base64.getDecoder().decode(encrypted)
        val decrypted = cipher.doFinal(decoded)
        return String(decrypted, Charsets.UTF_8)
    }

    // Proses VidBasic: AES Decrypt + API JSON
    private suspend fun processVidBasic(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anySuccess = false

        // ===== 1. AES DECRYPT (Link dari server VidBasic sendiri) =====
        try {
            val response = app.get(embedUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
            val html = response.text

            val dataVideoRegex = Regex("""data-video="([^"]+)">Standard""")
            var dataVideo = dataVideoRegex.find(html)?.groupValues?.get(1)
            if (dataVideo.isNullOrEmpty()) {
                val doc = org.jsoup.Jsoup.parse(html)
                dataVideo = doc.selectFirst(".Standard Server.selected")?.attr("data-video")
            }

            if (!dataVideo.isNullOrEmpty()) {
                val fullUrl = if (dataVideo.startsWith("//")) "https:$dataVideo" else dataVideo

                val response2 = app.get(
                    fullUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Referer" to embedUrl,
                        "Origin" to "https://${java.net.URL(embedUrl).host}"
                    )
                )
                val html2 = response2.text

                val cryptoRegex = Regex("""data-name="crypto"\s*data-value="([^"]+)"""")
                val encrypted = cryptoRegex.find(html2)?.groupValues?.get(1)

                if (!encrypted.isNullOrEmpty()) {
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
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0",
                                    "Referer" to fullUrl
                                )
                            }
                        )
                        anySuccess = true
                    }
                }
            }
        } catch (e: Exception) {}

        // ===== 2. API JSON (Link dari provider lain) =====
        try {
            val apiUrl = if (embedUrl.contains("?")) "$embedUrl&json=" else "$embedUrl?json="
            val response = app.get(apiUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
            val jsonText = response.text
            val json = JSONObject(jsonText)

            val allKeys = json.keys().asSequence().toList()
            for (key in allKeys) {
                val value = json.optString(key, null)
                if (!value.isNullOrEmpty() && (value.startsWith("http") || value.startsWith("//"))) {
                    val fixedLink = if (value.startsWith("//")) "https:$value" else value
                    val result = loadExtractor(fixedLink, subtitleCallback, callback)
                    if (result) anySuccess = true
                }
            }
        } catch (e: Exception) {}

        return anySuccess
    }

    // ==================== LOG UNTUK DEBUG ====================
    private suspend fun getVideoLogs(episodeUrl: String): String {
        val docRes = app.get(episodeUrl, headers = mapOf("User-Agent" to userAgent))
        val html = docRes.text
        val doc = docRes.document

        val allUrls = mutableSetOf<String>()
        val logs = mutableListOf<String>()

        doc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                src = fixUrlScheme(src)
                allUrls.add(src)
                logs.add("🔗 Iframe: $src")
                if (src.contains("vidbasic.top") || src.contains("vidb.top")) {
                    logs.add("  ✅ VidBasic detected")
                }
            }
        }

        doc.select("[data-video]").forEach { el ->
            var video = el.attr("data-video")
            if (video.isNotBlank()) {
                video = fixUrlScheme(video)
                allUrls.add(video)
                logs.add("🏷️ data-video: $video")
            }
        }

        doc.select("video source, video").forEach { v ->
            var src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) {
                src = fixUrlScheme(src)
                allUrls.add(src)
                logs.add("🎬 Video source: $src")
            }
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
            extractVideoUrls(scriptData).forEach { url ->
                allUrls.add(fixUrlScheme(url))
                logs.add("📜 Script extracted: $url")
            }
        }

        extractVideoUrls(html).forEach { url ->
            allUrls.add(fixUrlScheme(url))
            logs.add("🔎 General regex: $url")
        }

        val result = StringBuilder()
        result.appendLine("🔍 LOG VIDEO (${allUrls.size} URL unik):")
        result.appendLine()
        result.appendLine("📋 SEMUA URL:")
        if (allUrls.isEmpty()) {
            result.appendLine("  ❌ Tidak ada URL ditemukan")
        } else {
            allUrls.forEach { result.appendLine("  • $it") }
        }
        result.appendLine()
        if (logs.isNotEmpty()) {
            result.appendLine("📝 LOG DETAIL:")
            logs.take(15).forEach { result.appendLine("  $it") }
            if (logs.size > 15) {
                result.appendLine("  ... dan ${logs.size - 15} log lainnya")
            }
        }

        return result.toString()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title = document.selectFirst(".details .info h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val posterUrl = document.selectFirst(".details .img img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }

        val description = document.select(".details .info p").mapNotNull { p ->
            if (p.select("span").isEmpty() && p.text().length > 50) {
                p.text().trim()
            } else null
        }.joinToString("\n\n").ifEmpty {
            document.select(".details .info").first()?.text()?.substringAfter("Description:")?.trim()
        }

        val episodeItems = document.select("ul.list-episode-item-2.all-episode li a")
        val validEpisodes = episodeItems.mapNotNull { el ->
            val titleText = el.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
            val link = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epNum = extractEpisodeNumber(titleText)
            if (epNum != null && epNum > 0) {
                Triple(titleText, link, epNum)
            } else null
        }.sortedByDescending { it.third }

        if (validEpisodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }

        val episodesWithLogs = coroutineScope {
            val limitedEpisodes = validEpisodes.take(10)
            val deferredLogs = limitedEpisodes.map { (_, link, _) ->
                async { getVideoLogs(link) }
            }
            val logs = deferredLogs.awaitAll()

            limitedEpisodes.mapIndexed { index, (titleText, link, epNum) ->
                val log = logs.getOrNull(index)?.takeIf { it.isNotBlank() }
                Quad(titleText, link, epNum, log)
            }
        }

        val fullEpisodes = if (validEpisodes.size > 10) {
            validEpisodes.drop(10).map { (titleText, link, epNum) ->
                Quad(titleText, link, epNum, null)
            } + episodesWithLogs
        } else {
            episodesWithLogs
        }

        val episodes = fullEpisodes.sortedByDescending { it.third }.map { quad ->
            newEpisode(quad.first) {
                this.data = quad.second
                this.description = quad.fourth ?: "Tidak ada log video (mungkin episode > 10)."
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    // ==================== loadLinks ====================
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

        // Kumpulkan semua URL
        doc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                allUrls.add(fixUrlScheme(src))
            }
        }

        doc.select("video source, video").forEach { v ->
            var src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) {
                allUrls.add(fixUrlScheme(src))
            }
        }

        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            var video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) {
                allUrls.add(fixUrlScheme(video))
            }
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
                    if (file != null && file.isNotBlank()) allUrls.add(fixUrlScheme(file))
                } catch (e: Exception) {}
            }
        }

        extractVideoUrls(html).forEach { url -> allUrls.add(fixUrlScheme(url)) }

        var linkFound = false

        for (rawUrl in allUrls) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) continue

            // Jika URL adalah VidBasic, proses khusus
            if (cleanUrl.contains("vidbasic.top") || cleanUrl.contains("vidb.top")) {
                val result = processVidBasic(cleanUrl, subtitleCallback, callback)
                if (result) linkFound = true
                continue
            }

            // Coba extractor yang terdaftar
            if (loadExtractor(cleanUrl, subtitleCallback, callback)) {
                linkFound = true
                continue
            }

            // Fallback: jika .m3u8 atau .mp4
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
