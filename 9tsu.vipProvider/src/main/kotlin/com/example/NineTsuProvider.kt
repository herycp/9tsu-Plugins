package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Untuk menyimpan debug Ok.ru
    private var okRuDebug = ""

    private fun getAttrOrNull(element: Element?, attr: String): String? {
        val value = element?.attr(attr)?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/daily" to "Harian (Daily)",
        "$mainUrl/drama-monday1" to "Drama Senin",
        "$mainUrl/drama-tuesday1" to "Drama Selasa",
        "$mainUrl/drama-wednesdaydouga" to "Drama Rabu",
        "$mainUrl/drama-thursdaydouga" to "Drama Kamis",
        "$mainUrl/drama-fridaydouga" to "Drama Jumat",
        "$mainUrl/drama-saturdaydouga" to "Drama Sabtu",
        "$mainUrl/drama-sundaydouga" to "Drama Minggu",
        "$mainUrl/dramaend" to "Drama Tamat (End)",
        "$mainUrl/premium" to "Kategori Premium"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            val cleanData = request.data.removeSuffix("/")
            "$cleanData/page/$page/"
        } else {
            request.data
        }

        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val homeItems = doc.select("article, .post, .entry, .type-post, .item, .video-item, .blog-item").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            if (title.isBlank() || href.isBlank() || !href.startsWith("http")) return@mapNotNull null

            val imgElement = element.selectFirst("img")
            var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
            if (posterUrl?.startsWith("data:image") == true) posterUrl = null

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, homeItems)
    }

    // ==================== PENCARIAN (via 9tsu.in) ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val searchUrl = "https://9tsu.in/?s=${cleanQuery.replace(" ", "+")}"
        try {
            val doc = app.get(searchUrl, headers = mapOf("User-Agent" to userAgent)).document
            doc.select("article, .post, .entry, .type-post, .item, .result-item, .blog-item").forEach { element ->
                val titleElement = element.selectFirst("h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
                    ?: element.select("a").firstOrNull { it.text().trim().isNotBlank() }
                    ?: return@forEach
                val title = titleElement.text().trim()
                var link = titleElement.attr("href")
                if (link.isBlank()) return@forEach

                // Ubah domain 9tsu.in menjadi 9tsu.vip dan hilangkan /douga/
                if (link.startsWith("https://9tsu.in/douga/")) {
                    link = link.replace("https://9tsu.in/douga/", "https://9tsu.vip/")
                } else if (link.startsWith("https://9tsu.in/")) {
                    link = link.replace("https://9tsu.in/", "https://9tsu.vip/")
                } else if (link.startsWith("http://9tsu.in/")) {
                    link = link.replace("http://9tsu.in/", "https://9tsu.vip/")
                }

                if (title.isNotBlank() && link.startsWith("https://9tsu.vip/")) {
                    val imgElement = element.selectFirst("img")
                    var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
                    if (posterUrl?.startsWith("data:image") == true) posterUrl = null

                    results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return results.distinctBy { it.url }
    }
    // ====================================================================

    // ==================== DEBUG FUNCTION ====================
    private suspend fun generateDebugInfo(url: String): String {
        val debug = StringBuilder()
        debug.append("========== DEBUG 9tsu ==========\n")
        debug.append("URL: $url\n\n")

        try {
            val docRes = app.get(url, headers = mapOf("User-Agent" to userAgent))
            val doc = docRes.document

            val iframeUrls = doc.select("iframe").mapNotNull { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
                if (src.isNotBlank()) src else null
            }

            debug.append("Jumlah iframe ditemukan: ${iframeUrls.size}\n")
            if (iframeUrls.isEmpty()) {
                debug.append("Tidak ada iframe di halaman.\n")
                return debug.toString()
            }

            iframeUrls.forEachIndexed { index, iframeUrl ->
                debug.append("\n--- Iframe #${index + 1} ---\n")
                debug.append("URL: $iframeUrl\n")

                when {
                    iframeUrl.contains("ok.ru") -> {
                        debug.append("Provider: Ok.ru\n")
                        debug.append("loadExtractor akan dipanggil untuk Ok.ru.\n")
                        // Tambahkan info dari loadExtractor jika ada
                        if (okRuDebug.isNotEmpty()) {
                            debug.append("\nHasil loadExtractor:\n")
                            debug.append(okRuDebug)
                        }
                    }
                    iframeUrl.contains("pulvexa.space") -> {
                        debug.append("Provider: Pulvexa\n")
                        val idMatch = Regex("""pulvexa\.space/embed/([^?]+)""").find(iframeUrl)
                        val videoId = idMatch?.groupValues?.get(1)
                        if (videoId != null) {
                            debug.append("Video ID: $videoId\n")
                            val apiUrl = "https://obnoxious-elysia-herycp-161a17d4.koyeb.app/api/playlist?id=$videoId"
                            debug.append("API URL: $apiUrl\n")
                            debug.append("Link akan menggunakan URL API sebagai playlist (karena API mengembalikan M3U8).\n")
                            // Opsional: coba cek apakah API merespon M3U8
                            try {
                                val testResponse = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent))
                                debug.append("Test response code: ${testResponse.code}\n")
                                if (testResponse.code == 200) {
                                    val preview = testResponse.text.take(50)
                                    debug.append("Preview konten: $preview...\n")
                                }
                            } catch (e: Exception) {
                                debug.append("Test API gagal: ${e.message}\n")
                            }
                        } else {
                            debug.append("Video ID tidak ditemukan.\n")
                        }
                    }
                    else -> {
                        debug.append("Provider: Lainnya (fallback)\n")
                        try {
                            val embedRes = app.get(iframeUrl, referer = url, headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to url
                            ))
                            val embedHtml = embedRes.text
                            val urls = extractVideoUrls(embedHtml)
                            if (urls.isNotEmpty()) {
                                debug.append("URL ditemukan:\n")
                                urls.forEach { debug.append("  $it\n") }
                            } else {
                                debug.append("Tidak ada URL.\n")
                            }
                        } catch (e: Exception) {
                            debug.append("Error: ${e.message}\n")
                        }
                    }
                }
            }

            // Fallback seluruh halaman
            debug.append("\n--- Fallback seluruh halaman ---\n")
            val allUrls = mutableSetOf<String>()
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
            }
            doc.select("video source, video").forEach { v ->
                val src = v.attr("src").ifBlank { v.attr("data-src") }
                if (src.isNotBlank()) allUrls.add(src)
            }
            doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
                val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
                if (video.isNotBlank()) allUrls.add(video)
            }
            extractVideoUrls(docRes.text).forEach { url -> allUrls.add(url) }
            if (allUrls.isNotEmpty()) {
                debug.append("URL tambahan:\n")
                allUrls.forEach { debug.append("  $it\n") }
            } else {
                debug.append("Tidak ada URL.\n")
            }

        } catch (e: Exception) {
            debug.append("Error umum: ${e.message}\n")
        }

        debug.append("\n========== END DEBUG ==========")
        return debug.toString()
    }
    // ========================================================

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, .video-title")?.text()?.trim() ?: doc.title()
        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

        var description = doc.selectFirst(".entry-content, .post-content")?.text()?.trim() ?: ""

        // Reset debug
        okRuDebug = ""

        // Tambahkan debug info ke deskripsi
        try {
            val debugInfo = generateDebugInfo(url)
            description += "\n\n$debugInfo"
        } catch (e: Exception) {
            description += "\n\nGagal generate debug: ${e.message}"
        }

        return newMovieLoadResponse(title, url, TvType.TvSeries, url) {
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

        var linkFound = false

        // 1. Kumpulkan semua iframe
        val iframeUrls = doc.select("iframe").mapNotNull { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) src else null
        }

        if (iframeUrls.isNotEmpty()) {
            for (iframeUrl in iframeUrls) {
                // --- Ok.ru ---
                if (iframeUrl.contains("ok.ru")) {
                    val okDebug = StringBuilder()
                    okDebug.append("Ok.ru Debug:\n")
                    okDebug.append("  iframe URL: $iframeUrl\n")

                    try {
                        okDebug.append("  Mencoba loadExtractor...\n")
                        var extractorSuccess = false

                        // Buat custom callback untuk menangkap link dari loadExtractor
                        val capturedLinks = mutableListOf<ExtractorLink>()
                        val captureCallback: (ExtractorLink) -> Unit = { link ->
                            capturedLinks.add(link)
                            callback.invoke(link)
                        }

                        val success = loadExtractor(iframeUrl, subtitleCallback, captureCallback)
                        if (success && capturedLinks.isNotEmpty()) {
                            okDebug.append("  ✅ loadExtractor berhasil!\n")
                            okDebug.append("  Jumlah link ditemukan: ${capturedLinks.size}\n")
                            capturedLinks.forEachIndexed { index, link ->
                                okDebug.append("    Link #${index + 1}: ${link.url}\n")
                                okDebug.append("      Name: ${link.name}\n")
                                okDebug.append("      Type: ${link.type}\n")
                                okDebug.append("      Quality: ${link.quality}\n")
                            }
                            linkFound = true
                            okRuDebug = okDebug.toString()
                            continue
                        } else {
                            okDebug.append("  ❌ loadExtractor gagal atau tidak menemukan link.\n")
                        }
                    } catch (e: Exception) {
                        okDebug.append("  ❌ loadExtractor error: ${e.message}\n")
                        e.printStackTrace()
                    }

                    // Jika gagal, coba ekstrak manual dari halaman iframe
                    try {
                        okDebug.append("  Mencoba ekstraksi manual...\n")
                        val embedRes = app.get(iframeUrl, referer = data, headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to data
                        ))
                        val embedHtml = embedRes.text
                        okDebug.append("  Panjang HTML iframe: ${embedHtml.length}\n")

                        val urls = extractVideoUrls(embedHtml)
                        if (urls.isNotEmpty()) {
                            okDebug.append("  ✅ Manual extraction menemukan ${urls.size} URL:\n")
                            urls.forEachIndexed { index, url ->
                                okDebug.append("    URL #${index + 1}: $url\n")
                                callback.invoke(
                                    newExtractorLink(
                                        name = "Ok.ru (manual)",
                                        source = this.name,
                                        url = url,
                                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = data
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                linkFound = true
                            }
                        } else {
                            okDebug.append("  ❌ Manual extraction tidak menemukan URL.\n")
                            // Tampilkan preview HTML untuk debug
                            okDebug.append("  Preview HTML: ${embedHtml.take(500)}...\n")
                        }
                    } catch (e: Exception) {
                        okDebug.append("  ❌ Manual extraction error: ${e.message}\n")
                    }

                    okRuDebug = okDebug.toString()
                    continue
                }

                // --- Pulvexa ---
                if (iframeUrl.contains("pulvexa.space")) {
                    val idMatch = Regex("""pulvexa\.space/embed/([^?]+)""").find(iframeUrl)
                    val videoId = idMatch?.groupValues?.get(1)
                    if (videoId != null) {
                        try {
                            val apiUrl = "https://obnoxious-elysia-herycp-161a17d4.koyeb.app/api/playlist?id=$videoId"
                            // API langsung mengembalikan konten M3U8, jadi kita gunakan URL API sebagai playlist.
                            callback.invoke(
                                newExtractorLink(
                                    name = "Pulvexa",
                                    source = this.name,
                                    url = apiUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            linkFound = true
                        } catch (e: Exception) {
                            println("Pulvexa error: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                    continue
                }

                // --- Iframe lainnya (fallback) ---
                try {
                    val embedRes = app.get(iframeUrl, referer = data, headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to data
                    ))
                    val embedHtml = embedRes.text
                    val urls = extractVideoUrls(embedHtml)
                    for (videoUrl in urls) {
                        if (videoUrl.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    name = "9tsu - Video",
                                    source = this.name,
                                    url = videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            linkFound = true
                        }
                    }
                } catch (e: Exception) {
                    println("Fallback iframe error: ${e.message}")
                }
            }
        }

        // 2. Jika belum ada link, coba ekstrak dari seluruh HTML halaman
        if (!linkFound) {
            val allUrls = mutableSetOf<String>()

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
            }

            doc.select("video source, video").forEach { v ->
                val src = v.attr("src").ifBlank { v.attr("data-src") }
                if (src.isNotBlank()) allUrls.add(src)
            }

            doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
                val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
                if (video.isNotBlank()) allUrls.add(video)
            }

            extractVideoUrls(html).forEach { url -> allUrls.add(url) }

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
                            name = if (isM3) "9tsu - HLS" else "9tsu - MP4",
                            source = this.name,
                            url = cleanUrl,
                            type = if (isM3) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linkFound = true
                }
            }
        }

        return linkFound
    }
}
