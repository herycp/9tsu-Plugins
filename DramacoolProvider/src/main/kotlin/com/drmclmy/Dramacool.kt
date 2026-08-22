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

    // ==================== EKSTRAKSI VIDEO DENGAN API JSON ====================
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
     * Mencoba memanggil API JSON dari URL iframe.
     * URL iframe biasanya: https://vidbasic.top/embed/kev99can2gk
     * API URL: tambahkan ?json= atau &json=
     */
    private suspend fun fetchApiJson(iframeUrl: String): String? {
        return try {
            val baseUrl = iframeUrl.split("?").first()
            val apiUrl = if (baseUrl.contains("?")) "$baseUrl&json=" else "$baseUrl?json="
            val response = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent))
            if (response.code == 200) {
                response.text
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getVideoLogs(episodeUrl: String): String {
        val docRes = app.get(episodeUrl, headers = mapOf("User-Agent" to userAgent))
        val html = docRes.text
        val doc = docRes.document

        val allUrls = mutableSetOf<String>()
        val logs = mutableListOf<String>()
        val apiResults = mutableListOf<String>()

        // ===== 1. Cari iframe utama =====
        var mainIframeUrl: String? = null
        doc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                src = fixUrlScheme(src)
                if (src.contains("vidbasic.top") || src.contains("embed")) {
                    mainIframeUrl = src
                }
                allUrls.add(src)
                logs.add("🔗 Iframe: $src")
            }
        }

        // ===== 2. Coba API JSON dari iframe utama =====
        if (mainIframeUrl != null) {
            logs.add("📡 Mencoba API JSON dari iframe: $mainIframeUrl")
            val apiResponse = fetchApiJson(mainIframeUrl)
            if (apiResponse != null) {
                logs.add("📄 API Response: $apiResponse")
                try {
                    val jsonObject = JSONObject(apiResponse)
                    val streamtape = jsonObject.optString("streamtape")
                    val mixdrop = jsonObject.optString("mixdrop")
                    val vidhide = jsonObject.optString("vidhide")
                    val streamwish = jsonObject.optString("streamwish")
                    val doodstream = jsonObject.optString("doodstream")
                    val gdrive = jsonObject.optString("gdrive")

                    val allLinks = listOf(
                        "streamtape" to streamtape,
                        "mixdrop" to mixdrop,
                        "vidhide" to vidhide,
                        "streamwish" to streamwish,
                        "doodstream" to doodstream,
                        "gdrive" to gdrive
                    )

                    var hasValid = false
                    for ((source, link) in allLinks) {
                        if (link.isNotEmpty()) {
                            val fixed = fixUrlScheme(link)
                            allUrls.add(fixed)
                            apiResults.add("✅ $source: $fixed")
                            hasValid = true
                        }
                    }
                    if (!hasValid) {
                        apiResults.add("⚠️ Tidak ada link valid di API")
                    }
                } catch (e: Exception) {
                    logs.add("❌ Parse JSON gagal: ${e.message}")
                    apiResults.add("❌ Parse JSON gagal: ${e.message}")
                }
            } else {
                logs.add("❌ API JSON tidak merespon")
                apiResults.add("❌ API JSON tidak merespon")
            }
        } else {
            logs.add("ℹ️ Tidak ada iframe ditemukan")
        }

        // ===== 3. Coba juga dari elemen .Standard Server.selected (jika ada) =====
        val serverElement = doc.selectFirst(".Standard Server.selected")
        var videoUrl = serverElement?.attr("data-video")
        if (videoUrl != null) {
            videoUrl = fixUrlScheme(videoUrl)
            logs.add("📡 Standard Server.selected: $videoUrl")
            // Jika berbeda dengan iframe, coba juga
            if (videoUrl != mainIframeUrl) {
                val apiResponse2 = fetchApiJson(videoUrl)
                if (apiResponse2 != null) {
                    logs.add("📄 API Response (server): $apiResponse2")
                    try {
                        val jsonObject = JSONObject(apiResponse2)
                        val streamtape = jsonObject.optString("streamtape")
                        val mixdrop = jsonObject.optString("mixdrop")
                        val vidhide = jsonObject.optString("vidhide")
                        val streamwish = jsonObject.optString("streamwish")
                        val doodstream = jsonObject.optString("doodstream")
                        val gdrive = jsonObject.optString("gdrive")

                        listOf(
                            "streamtape" to streamtape,
                            "mixdrop" to mixdrop,
                            "vidhide" to vidhide,
                            "streamwish" to streamwish,
                            "doodstream" to doodstream,
                            "gdrive" to gdrive
                        ).forEach { (source, link) ->
                            if (link.isNotEmpty()) {
                                val fixed = fixUrlScheme(link)
                                allUrls.add(fixed)
                                apiResults.add("✅ $source (server): $fixed")
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        // ===== 4. Video source =====
        doc.select("video source, video").forEach { v ->
            var src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) {
                src = fixUrlScheme(src)
                allUrls.add(src)
                logs.add("🎬 Video source: $src")
            }
        }

        // ===== 5. Script =====
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
                val fixed = fixUrlScheme(url)
                allUrls.add(fixed)
                logs.add("📜 Script extracted: $fixed")
            }
            val jsonPattern = Regex("""(\{.*?(?:file|src|video|url)\s*:\s*"[^"]+".*?\})""")
            jsonPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (file != null && file.isNotBlank()) {
                        val fixed = fixUrlScheme(file)
                        allUrls.add(fixed)
                        logs.add("📦 JSON script: $fixed")
                    }
                } catch (e: Exception) {}
            }
        }

        // ===== 6. Data attributes =====
        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            var video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) {
                video = fixUrlScheme(video)
                allUrls.add(video)
                logs.add("🏷️ Data attr: $video")
            }
        }

        // ===== 7. General regex =====
        extractVideoUrls(html).forEach { url ->
            val fixed = fixUrlScheme(url)
            allUrls.add(fixed)
            logs.add("🔎 General regex: $fixed")
        }

        // ===== Build hasil =====
        val result = StringBuilder()
        result.appendLine("🔍 LOG VIDEO (${allUrls.size} URL unik):")
        result.appendLine()

        if (apiResults.isNotEmpty()) {
            result.appendLine("📡 HASIL API PARSING:")
            apiResults.forEach { result.appendLine("  $it") }
            result.appendLine()
        }

        result.appendLine("📋 SEMUA URL:")
        if (allUrls.isEmpty()) {
            result.appendLine("  ❌ Tidak ada URL ditemukan")
        } else {
            allUrls.forEach { result.appendLine("  • $it") }
        }
        result.appendLine()

        if (logs.isNotEmpty()) {
            result.appendLine("📝 LOG DETAIL:")
            logs.take(20).forEach { result.appendLine("  $it") }
            if (logs.size > 20) {
                result.appendLine("  ... dan ${logs.size - 20} log lainnya")
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

        // ===== 1. Cari iframe utama =====
        var mainIframeUrl: String? = null
        doc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                src = fixUrlScheme(src)
                if (src.contains("vidbasic.top") || src.contains("embed")) {
                    mainIframeUrl = src
                }
                allUrls.add(src)
            }
        }

        // ===== 2. Coba API JSON dari iframe =====
        if (mainIframeUrl != null) {
            val apiResponse = fetchApiJson(mainIframeUrl)
            if (apiResponse != null) {
                try {
                    val jsonObject = JSONObject(apiResponse)
                    val streamtape = jsonObject.optString("streamtape")
                    val mixdrop = jsonObject.optString("mixdrop")
                    val vidhide = jsonObject.optString("vidhide")
                    val streamwish = jsonObject.optString("streamwish")
                    val doodstream = jsonObject.optString("doodstream")
                    val gdrive = jsonObject.optString("gdrive")

                    listOf(streamtape, mixdrop, vidhide, streamwish, doodstream, gdrive).forEach { link ->
                        if (link.isNotEmpty()) {
                            allUrls.add(fixUrlScheme(link))
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        // ===== 3. Standard Server.selected =====
        val serverElement = doc.selectFirst(".Standard Server.selected")
        var videoUrl = serverElement?.attr("data-video")
        if (videoUrl != null) {
            videoUrl = fixUrlScheme(videoUrl)
            if (videoUrl != mainIframeUrl) {
                val apiResponse2 = fetchApiJson(videoUrl)
                if (apiResponse2 != null) {
                    try {
                        val jsonObject = JSONObject(apiResponse2)
                        val streamtape = jsonObject.optString("streamtape")
                        val mixdrop = jsonObject.optString("mixdrop")
                        val vidhide = jsonObject.optString("vidhide")
                        val streamwish = jsonObject.optString("streamwish")
                        val doodstream = jsonObject.optString("doodstream")
                        val gdrive = jsonObject.optString("gdrive")

                        listOf(streamtape, mixdrop, vidhide, streamwish, doodstream, gdrive).forEach { link ->
                            if (link.isNotEmpty()) {
                                allUrls.add(fixUrlScheme(link))
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        // ===== 4. Lainnya =====
        doc.select("video source, video").forEach { v ->
            var src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(fixUrlScheme(src))
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

        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            var video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(fixUrlScheme(video))
        }

        extractVideoUrls(html).forEach { url -> allUrls.add(fixUrlScheme(url)) }

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
