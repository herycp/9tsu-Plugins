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
import java.net.URLDecoder
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
            Regex("""https?://[^\s"'<>]+/master\.m3u8[^\s"'<>]*""")
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

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim().replace(" ", "+")

        val invalidTitles = listOf("back to homepage", "home", "beranda", "menu", "skip to content", "not found", "404")

        // 1. REST API
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

        // 4. DuckDuckGo fallback
        if (results.isEmpty()) {
            try {
                val ddgUrl = "https://html.duckduckgo.com/html/?q=site:9tsu.vip+${query.replace(" ", "+")}"
                val ddgRes = app.get(ddgUrl, headers = mapOf("User-Agent" to userAgent))
                val doc = ddgRes.document
                doc.select(".result").forEach { result ->
                    val titleElement = result.selectFirst(".result__a")
                    val title = titleElement?.text()?.trim() ?: return@forEach
                    val href = titleElement?.attr("href") ?: return@forEach
                    val realUrl = URLDecoder.decode(href.replace("/l/?uddg=", ""), "UTF-8")
                    if (realUrl.contains(mainUrl) && !invalidTitles.any { title.contains(it, ignoreCase = true) }) {
                        results.add(newTvSeriesSearchResponse(title, realUrl, TvType.TvSeries) { 
                            this.posterUrl = null
                        })
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        return results.distinctBy { it.url }
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

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, .video-title")?.text()?.trim() ?: doc.title()
        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

        val description = doc.selectFirst(".entry-content, .post-content")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.TvSeries, url) { 
            this.posterUrl = posterUrl
            this.plot = description
        }
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

        val allUrls = mutableSetOf<String>()

        // 1. iframe
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) allUrls.add(src)
        }

        // 2. video/source
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(src)
        }

        // 3. script (dengan unpack dan decode)
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

            allUrls.addAll(extractVideoUrls(scriptData))

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
        allUrls.addAll(extractVideoUrls(html))

        // 6. fetch embed URLs
        val embedUrls = allUrls.filter { it.contains("dremoxa") || it.contains("demoxa") || it.contains("vtbe") || it.contains("dood") || it.contains("streamtape") || it.contains("mixdrop") || it.contains("playlist") }.toList()
        for (embedUrl in embedUrls) {
            try {
                val embedRes = app.get(embedUrl, referer = data, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val embedHtml = embedRes.text
                allUrls.addAll(extractVideoUrls(embedHtml))
                try {
                    val unpacked = getAndUnpack(embedHtml)
                    if (unpacked.isNotBlank()) allUrls.addAll(extractVideoUrls(unpacked))
                } catch (e: Exception) {}
            } catch (e: Exception) {}
        }

        // 7. process all URLs
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
