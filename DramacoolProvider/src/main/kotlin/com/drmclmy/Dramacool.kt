package com.drmclmy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.Base64

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

    // ==================== EKSTRAKSI EPISODE ====================
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
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    // ==================== EKSTRAKSI VIDEO UNTUK DEBUG DI PLOT ====================
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

    /**
     * Scrape halaman episode untuk mendapatkan semua URL video.
     * Hasilnya akan ditampilkan di deskripsi episode (plot) untuk debugging.
     */
    private suspend fun getVideoLogs(episodeUrl: String): String {
        val docRes = app.get(episodeUrl, headers = mapOf("User-Agent" to userAgent))
        val html = docRes.text
        val doc = docRes.document

        val allUrls = mutableSetOf<String>()
        val logs = mutableListOf<String>()

        // 1. JSON API
        val serverElement = doc.selectFirst(".Standard Server.selected")
        var videoUrl = serverElement?.attr("data-video")
        if (videoUrl != null) {
            val jsonUrl = if (videoUrl.contains("?")) "$videoUrl&json=" else "$videoUrl?json="
            try {
                val jsonResponse = app.get(jsonUrl, headers = mapOf("User-Agent" to userAgent)).text
                val jsonObject = JSONObject(jsonResponse)
                val streamtape = jsonObject.optString("streamtape")
                val mixdrop = jsonObject.optString("mixdrop")
                val vidhide = jsonObject.optString("vidhide")
                val streamwish = jsonObject.optString("streamwish")
                val linkToTry = streamtape.takeIf { it.isNotEmpty() }
                    ?: mixdrop.takeIf { it.isNotEmpty() }
                    ?: vidhide.takeIf { it.isNotEmpty() }
                    ?: streamwish.takeIf { it.isNotEmpty() }
                if (linkToTry != null) {
                    allUrls.add(linkToTry)
                    logs.add("✅ JSON API: $linkToTry")
                } else {
                    logs.add("⚠️ JSON API: tidak ada link")
                }
            } catch (e: Exception) {
                logs.add("❌ JSON API error: ${e.message}")
            }
        }

        // 2. Iframe
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                allUrls.add(src)
                logs.add("🔗 Iframe: $src")
                try {
                    val embedRes = app.get(src, referer = episodeUrl, headers = mapOf("User-Agent" to userAgent))
                    extractVideoUrls(embedRes.text).forEach { url ->
                        allUrls.add(url)
                        logs.add("  ↳ Iframe extracted: $url")
                    }
                } catch (e: Exception) {
                    logs.add("  ❌ Iframe fetch failed: ${e.message}")
                }
            }
        }

        // 3. Video source
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) {
                allUrls.add(src)
                logs.add("🎬 Video source: $src")
            }
        }

        // 4. Script
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
                allUrls.add(url)
                logs.add("📜 Script extracted: $url")
            }
            // JSON dalam script
            val jsonPattern = Regex("""(\{.*?(?:file|src|video|url)\s*:\s*"[^"]+".*?\})""")
            jsonPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (file != null && file.isNotBlank()) {
                        allUrls.add(file)
                        logs.add("📦 JSON script: $file")
                    }
                } catch (e: Exception) {}
            }
        }

        // 5. Data attributes
        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) {
                allUrls.add(video)
                logs.add("🏷️ Data attr: $video")
            }
        }

        // 6. General regex
        extractVideoUrls(html).forEach { url ->
            allUrls.add(url)
            logs.add("🔎 General regex: $url")
        }

        if (logs.isEmpty()) {
            return "❌ Tidak ada URL video ditemukan sama sekali."
        }

        return "🔍 LOG VIDEO (${allUrls.size} URL unik):\n" + logs.joinToString("\n")
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
        val episodesWithNumbers = episodeItems.mapNotNull { el ->
            val titleText = el.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
            val link = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epNum = extractEpisodeNumber(titleText) ?: 0
            Triple(titleText, link, epNum)
        }.sortedByDescending { it.third }

        if (episodesWithNumbers.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }

        // Ambil log video untuk setiap episode (maksimal 10 agar cepat)
        val episodesWithLogs = coroutineScope {
            val limitedEpisodes = episodesWithNumbers.take(10)
            val deferredLogs = limitedEpisodes.map { (_, link, _) ->
                async { getVideoLogs(link) }
            }
            val logs = deferredLogs.awaitAll()

            limitedEpisodes.mapIndexed { index, (titleText, link, epNum) ->
                val log = logs.getOrNull(index)?.takeIf { it.isNotBlank() }
                Triple(titleText, link, epNum, log)
            }
        }

        // Gabungkan dengan episode sisanya (tanpa log)
        val fullEpisodes = if (episodesWithNumbers.size > 10) {
            episodesWithNumbers.drop(10).map { (titleText, link, epNum) ->
                Triple(titleText, link, epNum, null)
            } + episodesWithLogs
        } else {
            episodesWithLogs
        }

        val episodes = fullEpisodes.sortedByDescending { it.third }.map { (titleText, link, _, log) ->
            newEpisode(titleText) {
                this.data = link
                // Tampilkan log di deskripsi episode jika ada
                this.plot = log ?: "Tidak ada log video (mungkin episode > 10)."
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    // ==================== loadLinks (tetap ada untuk pemutaran) ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Sama seperti sebelumnya, tapi tanpa subtitle debug karena sudah ada di plot
        if (data.isBlank()) return false

        val docRes = app.get(data, headers = mapOf("User-Agent" to userAgent))
        val html = docRes.text
        val doc = docRes.document

        val allUrls = mutableSetOf<String>()

        // 1. JSON API
        val serverElement = doc.selectFirst(".Standard Server.selected")
        var videoUrl = serverElement?.attr("data-video")
        if (videoUrl != null) {
            val jsonUrl = if (videoUrl.contains("?")) "$videoUrl&json=" else "$videoUrl?json="
            try {
                val jsonResponse = app.get(jsonUrl, headers = mapOf("User-Agent" to userAgent)).text
                val jsonObject = JSONObject(jsonResponse)
                val streamtape = jsonObject.optString("streamtape")
                val mixdrop = jsonObject.optString("mixdrop")
                val vidhide = jsonObject.optString("vidhide")
                val streamwish = jsonObject.optString("streamwish")
                val linkToTry = streamtape.takeIf { it.isNotEmpty() }
                    ?: mixdrop.takeIf { it.isNotEmpty() }
                    ?: vidhide.takeIf { it.isNotEmpty() }
                    ?: streamwish.takeIf { it.isNotEmpty() }
                if (linkToTry != null) allUrls.add(linkToTry)
            } catch (e: Exception) {}
        }

        // 2. Iframe
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                allUrls.add(src)
                try {
                    val embedRes = app.get(src, referer = data, headers = mapOf("User-Agent" to userAgent))
                    extractVideoUrls(embedRes.text).forEach { url -> allUrls.add(url) }
                } catch (e: Exception) {}
            }
        }

        // 3. Video source
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(src)
        }

        // 4. Script
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
            extractVideoUrls(scriptData).forEach { url -> allUrls.add(url) }
            val jsonPattern = Regex("""(\{.*?(?:file|src|video|url)\s*:\s*"[^"]+".*?\})""")
            jsonPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (file != null && file.isNotBlank()) allUrls.add(file)
                } catch (e: Exception) {}
            }
        }

        // 5. Data attributes
        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(video)
        }

        // 6. General regex
        extractVideoUrls(html).forEach { url -> allUrls.add(url) }

        var linkFound = false
        for (rawUrl in allUrls) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) continue

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
