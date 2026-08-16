package com.gomio

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Base64

class FiveMioProvider : MainAPI() {
    override var mainUrl = "https://5mio.org"
    override var name = "5mio"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries)
    override var lang = "ja"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

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
        } catch (_: Exception) { str }
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

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("""(?:第\s*0\s*話|Episode\s*0|Ep\s*0|Eps\s*0)""", RegexOption.IGNORE_CASE), "").trim()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/youtube-baraeti" to "Arsip Video",
        "$mainUrl/monday" to "Senin",
        "$mainUrl/tuesday" to "Selasa",
        "$mainUrl/wednesday" to "Rabu",
        "$mainUrl/thursday" to "Kamis",
        "$mainUrl/friday" to "Jumat",
        "$mainUrl/saturday" to "Sabtu",
        "$mainUrl/sunday" to "Minggu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            val cleanData = request.data.removeSuffix("/")
            "$cleanData/page/$page/"
        } else {
            request.data
        }

        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val homeItems = doc.select("article.cactus-post-item, .cactus-post-item, article.post, .post, .entry, .type-post, .item").mapNotNull { element ->
            val titleElement = element.selectFirst("h3.cactus-post-title a, h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
                ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            if (title.isBlank() || href.isBlank() || !href.startsWith("http")) return@mapNotNull null

            val imgElement = element.selectFirst("img")
            var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
                ?: getAttrOrNull(imgElement, "data-lazy-src") ?: getAttrOrNull(imgElement, "data-original")
            if (posterUrl?.startsWith("data:image") == true) posterUrl = null

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        var page = 0
        var hasMore = true
        val maxPages = 10

        while (hasMore && page < maxPages) {
            val params = mapOf(
                "action" to "load_more",
                "page" to page.toString(),
                "searchPage" to "true",
                "template" to "html/loop/content",
                "vars[s]" to cleanQuery
            )
            try {
                val response = app.post(
                    ajaxUrl,
                    data = params,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/?s=${cleanQuery.replace(" ", "+")}"
                    )
                )
                val html = response.text
                if (html.isBlank()) break
                val doc = Jsoup.parse(html)
                val items = doc.select("article.cactus-post-item, .cactus-post-item, article.post, .post, .entry, .type-post, .item")
                if (items.isEmpty()) {
                    hasMore = false
                    break
                }
                items.forEach { element ->
                    val titleElement = element.selectFirst("h3.cactus-post-title a, h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
                        ?: return@forEach
                    val title = titleElement.text().trim()
                    var link = titleElement.attr("href")
                    if (link.isBlank()) return@forEach

                    if (!link.startsWith("http")) {
                        link = mainUrl + (if (link.startsWith("/")) "" else "/") + link
                    }

                    if (title.isNotBlank() && link.startsWith(mainUrl)) {
                        val imgElement = element.selectFirst("img")
                        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
                            ?: getAttrOrNull(imgElement, "data-lazy-src") ?: getAttrOrNull(imgElement, "data-original")
                        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

                        results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
                page++
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        val rawTitle = doc.selectFirst("h1.single-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: doc.title()
        val title = cleanTitle(rawTitle)

        var posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
        if (posterUrl.isNullOrBlank()) {
            val imgElement = doc.selectFirst(".picture-content img, .entry-content img, .post-thumbnail img, article img")
            posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
                ?: getAttrOrNull(imgElement, "data-lazy-src") ?: getAttrOrNull(imgElement, "data-original")
            if (posterUrl?.startsWith("data:image") == true) posterUrl = null
        }

        val descriptionElement = doc.selectFirst(".body-content, .entry-content, .post-content, .content")
        val description = if (descriptionElement != null) {
            val cloned = descriptionElement.clone()
            cloned.select(".overlay-hidden-content, .hidden-content, .post-metadata, .sharedaddy, .jp-relatedposts").remove()
            cloned.text().trim().replace(Regex("\\s+"), " ")
        } else {
            doc.selectFirst("meta[name='description']")?.attr("content")?.trim() ?: ""
        }

        val relatedItems = mutableListOf<SearchResponse>()
        val relatedContainer = doc.selectFirst(".post-list-in-single, .related-posts, .post-related, .related-content")
        if (relatedContainer != null) {
            relatedContainer.select("article.cactus-post-item, .cactus-post-item, .post-item").forEach { item ->
                val linkElement = item.selectFirst("h3.cactus-post-title a, h2 a, h3 a, h4 a, .entry-title a")
                if (linkElement != null) {
                    val relTitle = linkElement.text().trim()
                    val relUrl = linkElement.attr("href")
                    if (relTitle.isNotBlank() && relUrl.isNotBlank() && relUrl.startsWith(mainUrl) && !relUrl.equals(url)) {
                        val img = item.selectFirst("img")
                        val poster = getAttrOrNull(img, "data-src") ?: getAttrOrNull(img, "src")
                            ?: getAttrOrNull(img, "data-lazy-src") ?: getAttrOrNull(img, "data-original")
                        relatedItems.add(newTvSeriesSearchResponse(relTitle, relUrl, TvType.TvSeries) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.TvSeries, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.recommendations = relatedItems
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

        // iframes
        doc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                if (src.contains("muxalor.guru")) {
                    val idMatch = Regex("""muxalor\.guru/embed/([^?]+)""").find(src)
                    val videoId = idMatch?.groupValues?.get(1)
                    if (videoId != null) {
                        try {
                            val apiUrl = "https://obnoxious-elysia-herycp-161a17d4.koyeb.app/api/playlist?id=$videoId"
                            callback.invoke(
                                newExtractorLink(
                                    name = "muxalor",
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
                    return@forEach
                }

                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http")) {
                    allUrls.add(src)
                    try {
                        val embedRes = app.get(src, referer = data, headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to data
                        ))
                        extractVideoUrls(embedRes.text).forEach { url -> allUrls.add(url) }
                    } catch (_: Exception) {}
                }
            }
        }

        // video elements
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(src)
        }

        // scripts
        doc.select("script").forEach { script ->
            var scriptData = script.data()
            try {
                if (scriptData.contains("eval(") || scriptData.contains("pako") || scriptData.contains("atob")) {
                    val unpacked = getAndUnpack(scriptData)
                    if (unpacked.isNotBlank()) scriptData = unpacked
                }
            } catch (_: Exception) {}

            val decoded = decodeBase64IfPossible(scriptData)
            if (decoded != scriptData) scriptData = decoded
            extractVideoUrls(scriptData).forEach { url -> allUrls.add(url) }

            val jsonPattern = Regex("""(\{.*?(?:file|src|video|url)\s*:\s*"[^"]+".*?\})""")
            jsonPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null)
                        ?: json.optString("video", null) ?: json.optString("url", null)
                    if (file != null && file.isNotBlank()) allUrls.add(file)
                    val sources = json.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val srcObj = sources.getJSONObject(i)
                            val src = srcObj.optString("file", null) ?: srcObj.optString("src", null)
                                ?: srcObj.optString("url", null)
                            if (src != null) allUrls.add(src)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // data-* attributes
        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(video)
        }

        // general regex
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
                        name = if (isM3) "5mio - HLS" else "5mio - MP4",
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
