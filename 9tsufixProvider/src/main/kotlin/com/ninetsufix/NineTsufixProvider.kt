package com.ninetsufix

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

class NineTsuFixProvider : MainAPI() {
    override var mainUrl = "https://9tsu.in"
    override var name = "9tsu (Fix)"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Daftar kategori yang HARUS dianggap sebagai single page (bukan series)
    private val categoryPages = setOf(
        "/drama", "/monday", "/tuesday", "/wednesday", "/thursday",
        "/friday", "/saturday", "/sunday", "/daily", "/movie", "/spmovies"
    )

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
        "$mainUrl/drama" to "Drama",
        "$mainUrl/monday" to "Monday",
        "$mainUrl/tuesday" to "Tuesday",
        "$mainUrl/wednesday" to "Wednesday",
        "$mainUrl/thursday" to "Thursday",
        "$mainUrl/friday" to "Friday",
        "$mainUrl/saturday" to "Saturday",
        "$mainUrl/sunday" to "Sunday",
        "$mainUrl/daily" to "Daily",
        "$mainUrl/movie" to "Movie",
        "$mainUrl/spmovies" to "SP Movies"
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

    // ==================== PENCARIAN ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val searchUrl = "$mainUrl/?s=${cleanQuery.replace(" ", "+")}"
        try {
            val doc = app.get(searchUrl, headers = mapOf("User-Agent" to userAgent)).document
            doc.select("article, .post, .entry, .type-post, .item, .result-item, .blog-item").forEach { element ->
                val titleElement = element.selectFirst("h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
                    ?: element.select("a").firstOrNull { it.text().trim().isNotBlank() }
                    ?: return@forEach
                val title = titleElement.text().trim()
                val link = titleElement.attr("href")
                if (title.isBlank() || link.isBlank() || !link.startsWith("http")) return@forEach

                val imgElement = element.selectFirst("img")
                var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
                if (posterUrl?.startsWith("data:image") == true) posterUrl = null

                results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return results.distinctBy { it.url }
    }

    // ==================== CEK APAKAH URL ADALAH KATEGORI ====================
    private fun isCategoryPage(url: String): Boolean {
        val path = url.replace(mainUrl, "").split("?")[0]
        return categoryPages.any { path == it || path.startsWith("$it/") }
    }

    // ==================== EKSTRAK EPISODE LINKS DARI HALAMAN ====================
    private fun extractEpisodeLinks(doc: Document): List<String> {
        return doc.select("div.cactus-sub-wrap article.cactus-post-item a[href*='/douga/']")
            .mapNotNull { element ->
                val href = element.attr("href")
                when {
                    href.startsWith("http") -> href
                    href.startsWith("/") -> "https://9tsu.in$href"
                    else -> null
                }
            }.distinct()
    }

    // ==================== DAPATKAN SERIES URL DARI BREADCRUMB ====================
    private fun getSeriesUrlFromBreadcrumb(doc: Document): String? {
        val breadcrumbNav = doc.selectFirst("nav.rank-math-breadcrumb, nav[aria-label='breadcrumbs'], .breadcrumb")
        if (breadcrumbNav != null) {
            // Ambil semua elemen a dan span (item terakhir biasanya span)
            val items = breadcrumbNav.children()
            // Cari link terakhir yang bukan span dan bukan home, dan bukan kategori
            var lastLink: Element? = null
            for (item in items) {
                if (item.tagName() == "a") {
                    val href = item.attr("href")
                    if (href.isNotBlank() && !href.equals(mainUrl) && !href.contains("/douga/") && !isCategoryPage(href)) {
                        lastLink = item
                    }
                }
            }
            if (lastLink != null) {
                val href = lastLink.attr("href")
                if (href.isNotBlank()) {
                    return href
                }
            }
        }
        return null
    }

    // ==================== BUILD SERIES RESPONSE ====================
    private suspend fun buildSeriesResponse(doc: Document, url: String, episodeLinks: List<String>): TvSeriesLoadResponse {
        val seriesTitle = doc.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() ?: doc.title()

        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

        val descriptionElement = doc.selectFirst(".body-content")
        val description = if (descriptionElement != null) {
            val cloned = descriptionElement.clone()
            cloned.select(".overlay-hidden-content, .hidden-content, .post-metadata").remove()
            cloned.text().trim().replace(Regex("\\s+"), " ")
        } else {
            doc.selectFirst(".entry-content, .post-content")?.text()?.trim()?.replace(Regex("\\s+"), " ") ?: ""
        }

        // Balik urutan episode karena halaman menampilkan terbaru di atas (descending)
        val reversedLinks = episodeLinks.reversed()

        val episodes = reversedLinks.map { link ->
            val episodeElement = doc.select("a[href='$link']").firstOrNull()
            val episodeTitle = episodeElement?.text()?.trim() ?: "Episode"
            // Pastikan link absolut
            val absoluteLink = if (link.startsWith("http")) link else "https://9tsu.in$link"
            newEpisode(episodeTitle) {
                this.data = absoluteLink
            }
        }

        return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    // ==================== LOAD SINGLE PAGE ====================
    private suspend fun loadSinglePage(doc: Document, url: String): MovieLoadResponse {
        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, .video-title")?.text()?.trim() ?: doc.title()

        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

        val descriptionElement = doc.selectFirst(".body-content")
        val description = if (descriptionElement != null) {
            val cloned = descriptionElement.clone()
            cloned.select(".overlay-hidden-content, .hidden-content, .post-metadata").remove()
            cloned.text().trim().replace(Regex("\\s+"), " ")
        } else {
            doc.selectFirst(".entry-content, .post-content")?.text()?.trim()?.replace(Regex("\\s+"), " ") ?: ""
        }

        return newMovieLoadResponse(title, url, TvType.TvSeries, url) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    // ==================== LOAD ====================
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        // Jika URL adalah kategori hari, langsung tampilkan single page
        if (isCategoryPage(url)) {
            return loadSinglePage(doc, url)
        }

        // Jika URL adalah episode (/douga/)
        if (url.contains("/douga/")) {
            // Coba dapatkan series URL dari breadcrumb
            var seriesUrl = getSeriesUrlFromBreadcrumb(doc)

            if (seriesUrl != null && seriesUrl != url) {
                try {
                    val seriesDoc = app.get(seriesUrl, headers = mapOf("User-Agent" to userAgent)).document
                    val episodeLinks = extractEpisodeLinks(seriesDoc)

                    if (episodeLinks.isNotEmpty()) {
                        // Pastikan ini bukan kategori
                        if (!isCategoryPage(seriesUrl)) {
                            return buildSeriesResponse(seriesDoc, seriesUrl, episodeLinks)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Jika breadcrumb gagal, coba cari link series dari rel='category'
            val seriesLink = doc.select("a[rel='category']").firstOrNull()
            if (seriesLink != null) {
                val href = seriesLink.attr("href")
                if (href.isNotBlank() && !href.contains("/douga/") && !isCategoryPage(href)) {
                    try {
                        val seriesDoc = app.get(href, headers = mapOf("User-Agent" to userAgent)).document
                        val episodeLinks = extractEpisodeLinks(seriesDoc)
                        if (episodeLinks.isNotEmpty()) {
                            return buildSeriesResponse(seriesDoc, href, episodeLinks)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            // Jika semua gagal, tampilkan single page
            return loadSinglePage(doc, url)
        }

        // URL bukan episode dan bukan kategori
        // Cek apakah ini halaman series (berisi banyak episode)
        val episodeLinks = extractEpisodeLinks(doc)

        // Periksa breadcrumb: jika memiliki 3 level atau lebih (Home > Kategori > Series)
        val breadcrumbNav = doc.selectFirst("nav.rank-math-breadcrumb, nav[aria-label='breadcrumbs'], .breadcrumb")
        val breadcrumbLevels = breadcrumbNav?.select("a")?.size ?: 0

        // Jika ada episode dan breadcrumb menunjukkan ini series (bukan kategori hari)
        if (episodeLinks.isNotEmpty() && breadcrumbLevels >= 3 && !isCategoryPage(url)) {
            return buildSeriesResponse(doc, url, episodeLinks)
        }

        // Jika tidak ada episode atau ini kategori, tampilkan single page
        return loadSinglePage(doc, url)
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
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                when {
                    // qevrinto.guru - langsung API
                    src.contains("qevrinto.guru") -> {
                        val idMatch = Regex("""qevrinto\.guru/embed/([^?]+)""").find(src)
                        val videoId = idMatch?.groupValues?.get(1)
                        if (videoId != null) {
                            try {
                                val apiUrl = "https://obnoxious-elysia-herycp-161a17d4.koyeb.app/api/playlist?id=$videoId"
                                callback.invoke(
                                    newExtractorLink(
                                        name = "qevrinto",
                                        source = this.name,
                                        url = apiUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = data
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }
                    // Ok.ru
                    src.contains("ok.ru") -> {
                        allUrls.add(src)
                        try {
                            val embedRes = app.get(src, referer = data, headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to data
                            ))
                            val embedHtml = embedRes.text
                            extractVideoUrls(embedHtml).forEach { url -> allUrls.add(url) }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    // Iframe lainnya
                    else -> {
                        try {
                            val embedRes = app.get(src, referer = data, headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to data
                            ))
                            val embedHtml = embedRes.text
                            extractVideoUrls(embedHtml).forEach { url -> allUrls.add(url) }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        }

        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(src)
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
            extractVideoUrls(scriptData).forEach { url -> allUrls.add(url) }

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
        }

        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(video)
        }

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
