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

    private fun extractItemsFromDocument(doc: Document, results: MutableList<SearchResponse>, invalidTitles: List<String>) {
        doc.select("article, .post, .entry, .type-post, .item, .result-item, .video-block, .search-item, .blog-item, .hentry, .list-item").forEach { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
                ?: element.select("a").firstOrNull { it.text().trim().isNotBlank() }
                ?: return@forEach

            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            if (title.isBlank() || href.isBlank() || !href.contains(mainUrl)) return@forEach
            if (invalidTitles.any { title.contains(it, ignoreCase = true) }) return@forEach

            val imgElement = element.selectFirst("img")
            var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
            if (posterUrl?.startsWith("data:image") == true) posterUrl = null

            results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl })
        }
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

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim().replace(" ", "+")

        val invalidTitles = listOf("back to homepage", "home", "beranda", "menu", "skip to content", "not found", "404")

        // 1. WP REST API
        try {
            val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$cleanQuery&_embed&per_page=50"
            val apiRes = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest"))
            if (apiRes.code == 200 && apiRes.text.trim().startsWith("[")) {
                val jsonArray = JSONArray(apiRes.text)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val titleRaw = item.getJSONObject("title").optString("rendered", "")
                    val title = titleRaw.replace(Regex("<[^>]*>"), "").replace("&#8211;", "-").trim()
                    val link = item.optString("link", "")
                    if (invalidTitles.any { title.equals(it, ignoreCase = true) }) continue
                    var posterUrl: String? = null
                    if (item.has("_embedded")) {
                        val embedded = item.getJSONObject("_embedded")
                        if (embedded.has("wp:featuredmedia")) {
                            val mediaArray = embedded.getJSONArray("wp:featuredmedia")
                            if (mediaArray.length() > 0) {
                                posterUrl = mediaArray.getJSONObject(0).optString("source_url", null)
                            }
                        }
                    }
                    if (title.isNotBlank() && link.isNotBlank()) {
                        results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = posterUrl })
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 2. Admin AJAX
        if (results.isEmpty()) {
            try {
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val actions = listOf("search", "search_posts", "loadmore", "search_results", "load_posts")
                for (action in actions) {
                    try {
                        val params = mapOf("action" to action, "s" to cleanQuery, "keyword" to cleanQuery, "search" to cleanQuery)
                        val ajaxRes = app.post(
                            ajaxUrl,
                            data = params,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded"
                            )
                        )
                        if (ajaxRes.code == 200) {
                            val text = ajaxRes.text.trim()
                            if (text.startsWith("{")) {
                                val json = JSONObject(text)
                                val html = json.optString("html", null) ?: json.optString("data", null) ?: json.optString("content", null)
                                if (html != null) {
                                    val fragment = org.jsoup.Jsoup.parse(html)
                                    extractItemsFromDocument(fragment, results, invalidTitles)
                                } else {
                                    val posts = json.optJSONArray("posts") ?: json.optJSONArray("results") ?: json.optJSONArray("items")
                                    if (posts != null) {
                                        for (j in 0 until posts.length()) {
                                            val post = posts.getJSONObject(j)
                                            val title = post.optString("title", "").trim()
                                            val link = post.optString("link", "").trim()
                                            val poster = post.optString("image", null) ?: post.optString("thumbnail", null)
                                            if (title.isNotBlank() && link.isNotBlank() && !invalidTitles.any { title.equals(it, ignoreCase = true) }) {
                                                results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = poster })
                                            }
                                        }
                                    }
                                }
                            } else if (text.startsWith("[")) {
                                val jsonArray = JSONArray(text)
                                for (j in 0 until jsonArray.length()) {
                                    val item = jsonArray.getJSONObject(j)
                                    val title = item.optString("title", "").trim()
                                    val link = item.optString("link", "").trim()
                                    val poster = item.optString("image", null) ?: item.optString("thumbnail", null)
                                    if (title.isNotBlank() && link.isNotBlank() && !invalidTitles.any { title.equals(it, ignoreCase = true) }) {
                                        results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = poster })
                                    }
                                }
                            } else {
                                val doc = org.jsoup.Jsoup.parse(text)
                                extractItemsFromDocument(doc, results, invalidTitles)
                            }
                            if (results.isNotEmpty()) break
                        }
                    } catch (e: Exception) { /* ignore */ }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 3. HTML fallback
        if (results.isEmpty()) {
            try {
                val searchUrls = listOf(
                    "$mainUrl/?s=$cleanQuery",
                    "$mainUrl/search/$cleanQuery/",
                    "$mainUrl/search/$cleanQuery"
                )
                for (url in searchUrls) {
                    val res = app.get(url, headers = mapOf(
                        "User-Agent" to userAgent,
                        "X-Requested-With" to "XMLHttpRequest"
                    ))
                    val doc = res.document
                    extractItemsFromDocument(doc, results, invalidTitles)
                    if (results.isNotEmpty()) break
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, .video-title")?.text()?.trim() ?: doc.title()
        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

        return newMovieLoadResponse(title, url, TvType.TvSeries, url) { this.posterUrl = posterUrl }
    }

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

        val embedUrls = mutableSetOf<String>()

        // 1. iframe
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) embedUrls.add(src)
        }

        // 2. video source
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) embedUrls.add(src)
        }

        // 3. script patterns
        doc.select("script").forEach { script ->
            val scriptData = script.data()
            val patterns = listOf(
                Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""src\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""source\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""video\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""url\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""")
            )
            patterns.forEach { pattern ->
                pattern.findAll(scriptData).forEach { match ->
                    embedUrls.add(unescapeJs(match.groupValues[1]))
                }
            }
        }

        // 4. data attributes
        doc.select("[data-video], [data-src], [data-url], [data-file]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }
            if (video.isNotBlank()) embedUrls.add(video)
        }

        // 5. general regex
        val generalRegex = Regex("""(https?://[^\s"'<>]+?(?:/embed/|/e/|/v/|dremoxa|demoxa|vtbe|vidmoly|streamtape|dood|mixdrop|playlist|\.m3u8|\.mp4)[^\s"'<>]*)""")
        generalRegex.findAll(html).forEach { match ->
            embedUrls.add(unescapeJs(match.groupValues[1]).replace("\\", ""))
        }

        var linkFound = false

        for (rawUrl in embedUrls) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) continue

            // Try external extractor
            if (loadExtractor(cleanUrl, subtitleCallback, callback)) {
                linkFound = true
                continue
            }

            // Direct video link
            if (cleanUrl.contains(".m3u8") || cleanUrl.endsWith(".mp4")) {
                val isM3 = cleanUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        name = if (isM3) "9tsu - Raw HLS" else "9tsu - Raw MP4",
                        source = this.name,
                        url = cleanUrl,
                        type = if (isM3) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                linkFound = true
                continue
            }

            // --- PENANGANAN KHUSUS DREMOXA (diperbaiki) ---
            if (cleanUrl.contains("dremoxa") || cleanUrl.contains("demoxa") || cleanUrl.contains("vtbe")) {
                try {
                    // Ambil halaman embed dengan header yang benar
                    val embedRes = app.get(cleanUrl, referer = data, headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to "https://dremoxa.space",
                        "Referer" to data
                    ))
                    val embedHtml = embedRes.text

                    // Cari link playlist.m3u8 secara langsung (paling akurat)
                    val m3u8Regex = Regex("""https?://dremoxa\.space/playlist/[a-f0-9]+/playlist\.m3u8[^\s"'<>]*""")
                    var streamUrl = m3u8Regex.find(embedHtml)?.value
                    if (streamUrl == null) {
                        // Coba di script yang di-unpack
                        val unpacked = try { getAndUnpack(embedHtml) } catch (e: Exception) { "" }
                        val combined = embedHtml + unpacked
                        streamUrl = m3u8Regex.find(combined)?.value
                    }

                    if (streamUrl != null) {
                        callback.invoke(
                            newExtractorLink(
                                name = "9tsu - Dremoxa",
                                source = this.name,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = cleanUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Origin" to "https://dremoxa.space",
                                    "Referer" to cleanUrl,
                                    "User-Agent" to userAgent
                                )
                            }
                        )
                        linkFound = true
                    } else {
                        // Fallback: metode sebelumnya (unpack dan eval)
                        val unpacked = try { getAndUnpack(embedHtml) } catch (e: Exception) { "" }
                        val combined = embedHtml + unpacked
                        val fallbackRegex = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""")
                        fallbackRegex.findAll(combined).map { unescapeJs(it.value) }.distinct().forEach { url ->
                            callback.invoke(
                                newExtractorLink(
                                    name = "9tsu - Demoxa Unpacked",
                                    source = this.name,
                                    url = url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = cleanUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf("Origin" to "https://dremoxa.space")
                                }
                            )
                            linkFound = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return linkFound
    }
}
