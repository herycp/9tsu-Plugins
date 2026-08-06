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

    // ==================== DEBUG (untuk ditampilkan di deskripsi) ====================
    private suspend fun generateDebugInfo(url: String): String {
        val debug = StringBuilder()
        debug.append("========== DEBUG 9tsu ==========\n")
        debug.append("URL: $url\n\n")

        try {
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

            val iframeUrls = doc.select("iframe").mapNotNull { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
                if (src.isNotBlank()) src else null
            }

            debug.append("Jumlah iframe ditemukan: ${iframeUrls.size}\n")
            iframeUrls.forEachIndexed { index, iframeUrl ->
                debug.append("\n--- Iframe #${index + 1} ---\n")
                debug.append("URL: $iframeUrl\n")
                when {
                    iframeUrl.contains("ok.ru") -> debug.append("Provider: Ok.ru (akan diproses oleh loadExtractor)\n")
                    iframeUrl.contains("pulvexa.space") -> {
                        debug.append("Provider: Pulvexa\n")
                        val idMatch = Regex("""pulvexa\.space/embed/([^?]+)""").find(iframeUrl)
                        val videoId = idMatch?.groupValues?.get(1)
                        if (videoId != null) {
                            debug.append("Video ID: $videoId\n")
                            val apiUrl = "https://obnoxious-elysia-herycp-161a17d4.koyeb.app/api/playlist?id=$videoId"
                            debug.append("API URL: $apiUrl (akan digunakan sebagai playlist M3U8)\n")
                        } else {
                            debug.append("Video ID tidak ditemukan.\n")
                        }
                    }
                    else -> debug.append("Provider: Lainnya (akan dicoba dengan loadExtractor atau ekstraksi manual)\n")
                }
            }

            // Tambahkan info jika ada video langsung di halaman
            val videoSrcs = doc.select("video source, video").mapNotNull { v ->
                v.attr("src").ifBlank { v.attr("data-src") }.takeIf { it.isNotBlank() }
            }
            if (videoSrcs.isNotEmpty()) {
                debug.append("\nVideo source ditemukan di halaman:\n")
                videoSrcs.forEach { debug.append("  $it\n") }
            }

        } catch (e: Exception) {
            debug.append("Error saat debug: ${e.message}\n")
        }

        debug.append("\n========== END DEBUG ==========")
        return debug.toString()
    }
    // =================================================================================

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, .video-title")?.text()?.trim() ?: doc.title()
        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

        var description = doc.selectFirst(".entry-content, .post-content")?.text()?.trim() ?: ""

        // Tambahkan debug ke deskripsi
        try {
            description += "\n\n" + generateDebugInfo(url)
        } catch (e: Exception) {
            description += "\n\nGagal generate debug: ${e.message}"
        }

        return newMovieLoadResponse(title, url, TvType.TvSeries, url) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    // ==================== loadLinks (berdasarkan file lama + Pulvexa) ====================
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
        val embedUrls = mutableSetOf<String>()

        // 1. iframe - pisahkan Pulvexa untuk ditangani langsung
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                if (src.contains("pulvexa.space")) {
                    // Tangani Pulvexa langsung
                    val idMatch = Regex("""pulvexa\.space/embed/([^?]+)""").find(src)
                    val videoId = idMatch?.groupValues?.get(1)
                    if (videoId != null) {
                        try {
                            val apiUrl = "https://obnoxious-elysia-herycp-161a17d4.koyeb.app/api/playlist?id=$videoId"
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
                            // Tidak perlu ditambahkan ke allUrls
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    // Iframe lainnya, simpan untuk diproses nanti (termasuk ok.ru)
                    embedUrls.add(src)
                }
            }
        }

        // 2. video/source (langsung di halaman)
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(src)
        }

        // 3. script - ekstrak dari script (seperti di file lama)
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

            // Cari JSON di script
            val jsonPattern = Regex("""(\{.*?(?:file|src|video|url)\s*:\s*"[^"]+".*?\})""")
            jsonPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (file != null && file.isNotBlank()) allUrls.add(file)
                    val sources = json.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val srcObj = sources.getJSONObject(i)
                            val src = srcObj.optString("file", null) ?: srcObj.optString("src", null) ?: srcObj.optString("url", null)
                            if (src != null) allUrls.add(src)
                        }
                    }
                } catch (e: Exception) {}
            }

            val varPattern = Regex("""var\s+player\s*=\s*(\{.*?\})""")
            varPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (file != null) allUrls.add(file)
                } catch (e: Exception) {}
            }
        }

        // 4. data-* attributes
        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(video)
        }

        // 5. general regex on full html
        extractVideoUrls(html).forEach { url -> allUrls.add(url) }

        // 6. Proses embed URLs (selain Pulvexa yang sudah ditangani)
        for (embedUrl in embedUrls) {
            try {
                val embedRes = app.get(embedUrl, referer = data, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to data,
                    "Origin" to embedUrl.substringBefore("/", "").replace("https://", "").replace("http://", "")
                ))
                val embedHtml = embedRes.text
                extractVideoUrls(embedHtml).forEach { url -> allUrls.add(url) }
                try {
                    val unpacked = getAndUnpack(embedHtml)
                    if (unpacked.isNotBlank()) {
                        extractVideoUrls(unpacked).forEach { url -> allUrls.add(url) }
                    }
                } catch (e: Exception) {}
                val decodedEmbed = decodeBase64IfPossible(embedHtml)
                if (decodedEmbed != embedHtml) {
                    extractVideoUrls(decodedEmbed).forEach { url -> allUrls.add(url) }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 7. API endpoint jika ada ID di URL (seperti di file lama)
        val postId = doc.selectFirst("article")?.attr("id")?.replace("post-", "") ?: doc.selectFirst("[data-post-id]")?.attr("data-post-id")
        if (postId != null) {
            val apiEndpoints = listOf(
                "$mainUrl/wp-json/wp/v2/posts/$postId?_embed",
                "$mainUrl/wp-json/oembed/1.0/embed?url=$data"
            )
            for (endpoint in apiEndpoints) {
                try {
                    val apiRes = app.get(endpoint, headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest"))
                    if (apiRes.code == 200) {
                        extractVideoUrls(apiRes.text).forEach { url -> allUrls.add(url) }
                    }
                } catch (e: Exception) {}
            }
        }

        // 8. Cari link di elemen dengan class 'player' atau 'video-container'
        doc.select(".player, .video-container, .embed-container").forEach { container ->
            container.select("a[href], source, iframe").forEach { el ->
                val link = el.attr("href").ifBlank { el.attr("src") }.ifBlank { el.attr("data-src") }
                if (link.isNotBlank()) allUrls.add(link)
            }
        }

        // 9. Coba endpoint /get_player atau /api/source (seperti di file lama)
        val playerScript = doc.select("script").find { it.data().contains("get_player") || it.data().contains("api/source") }
        if (playerScript != null) {
            val match = Regex("""get_player\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(playerScript.data())
            val id = match?.groupValues?.get(1)
            if (id != null) {
                try {
                    val apiUrl = "$mainUrl/wp-admin/admin-ajax.php?action=get_player&id=$id"
                    val apiRes = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest"))
                    if (apiRes.code == 200) {
                        extractVideoUrls(apiRes.text).forEach { url -> allUrls.add(url) }
                    }
                } catch (e: Exception) {}
            }
        }

        // Proses semua URL yang ditemukan
        var linkFound = false
        for (rawUrl in allUrls) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) continue

            // Coba loadExtractor (ini yang menangani Ok.ru dan lainnya)
            if (loadExtractor(cleanUrl, subtitleCallback, callback)) {
                linkFound = true
                continue
            }

            // Jika tidak, coba langsung sebagai M3U8/MP4
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

        return linkFound
    }
}
