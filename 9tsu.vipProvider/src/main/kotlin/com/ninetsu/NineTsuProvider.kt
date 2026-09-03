package com.ninetsu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64
import java.net.URLEncoder

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "ja" 
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

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("""(?:第\s*0\s*話|Episode\s*0|Ep\s*0|Eps\s*0)""", RegexOption.IGNORE_CASE), "").trim()
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

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return newSearchResponseList(emptyList(), false)

        val ajaxPage = (page - 1).coerceAtLeast(0)
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        
        val params = mapOf(
            "action" to "load_more",
            "page" to ajaxPage.toString(),
            "searchPage" to "true",
            "template" to "html/loop/content",
            "vars[s]" to cleanQuery
        )

        return try {
            val response = app.post(
                ajaxUrl,
                data = params,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/?s=${URLEncoder.encode(cleanQuery, "UTF-8")}",
                    "Origin" to mainUrl,
                    "Accept" to "*/*"
                )
            )

            val html = response.text
            if (html.length < 20) {
                return newSearchResponseList(emptyList(), false)
            }

            val doc = Jsoup.parse(html)
            val items = doc.select("article.cactus-post-item, .cactus-post-item, article.post, .post, .entry, .type-post, .item, .cactus-listing-wrap .cactus-post-item, .cactus-sub-wrap .cactus-post-item")

            val results = items.mapNotNull { element ->
                val titleElement = element.selectFirst("h3.cactus-post-title a, h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
                    ?: element.select("a").firstOrNull { it.text().trim().isNotBlank() }
                    ?: return@mapNotNull null

                val title = titleElement.text().trim()
                var link = titleElement.attr("href")

                if (link.isBlank()) return@mapNotNull null

                if (!link.startsWith("http")) {
                    link = mainUrl + (if (link.startsWith("/")) "" else "/") + link
                }

                if (title.isNotBlank() && link.startsWith(mainUrl)) {
                    val imgElement = element.selectFirst("img")
                    var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
                    if (posterUrl?.startsWith("data:image") == true) posterUrl = null

                    newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                } else null
            }.distinctBy { it.url }

            val hasNext = results.isNotEmpty() && !html.contains("invi no-posts")
            newSearchResponseList(results, hasNext)
        } catch (e: Exception) {
            newSearchResponseList(emptyList(), false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        val rawTitle = doc.selectFirst("h1.entry-title, h1.post-title, h1, .video-title")?.text()?.trim() ?: doc.title()
        val title = cleanTitle(rawTitle)

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

        // Ekstraksi Related Items
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
        val nestedIframes = mutableSetOf<String>()
        
        doc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                if (src.startsWith("//")) src = "https:$src"
                if (!src.startsWith("http")) return@forEach

                // Penanganan Nested Iframe Khusus
                if (src.contains("blogspherenews.xyz/embed/")) {
                    try {
                        val innerDoc = app.get(src, referer = data, headers = mapOf("User-Agent" to userAgent)).document
                        val innerIframe = innerDoc.selectFirst("iframe")
                        var innerSrc = innerIframe?.attr("src")?.ifBlank { innerIframe.attr("data-src") }
                        if (!innerSrc.isNullOrBlank()) {
                            if (innerSrc.startsWith("//")) innerSrc = "https:$innerSrc"
                            src = innerSrc // Timpa src untuk diekstrak oleh blok 'when'
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                
                when {
                    src.contains("korxime.guru") -> {
                        val idMatch = Regex("""korxime\.guru/embed/([^?]+)""").find(src)
                        val videoId = idMatch?.groupValues?.get(1)
                        if (videoId != null) {
                            try {
                                val apiUrl = "https://obnoxious-elysia-herycp-161a17d4.koyeb.app/api/playlist?id=$videoId"
                                callback.invoke(newExtractorLink("korxime", this.name, apiUrl, ExtractorLinkType.M3U8) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                })
                            } catch (e: Exception) {}
                        }
                    }
                    src.contains("ok.ru") -> {
                        allUrls.add(src)
                    }
                    else -> {
                        allUrls.add(src)
                        try {
                            val embedRes = app.get(src, referer = data, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                            val embedHtml = embedRes.text
                            extractVideoUrls(embedHtml).forEach { url -> allUrls.add(url) }
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        
        // video/source
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(src)
        }

        // script
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

        // data-* attributes
        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(video)
        }

        // general regex
        extractVideoUrls(html).forEach { url -> allUrls.add(url) }

        var linkFound = false
        for (rawUrl in allUrls) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) continue

            // Penanganan khusus link ok.ru menggunakan Extractor bawaan + Backup Extractor Vidomon
            if (cleanUrl.contains("ok.ru")) {
                loadExtractor(cleanUrl, subtitleCallback, callback)
                
                VidomonOkruExtractor().getUrl(
                    url = cleanUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                linkFound = true
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
        
        // Eksekusi fallback untuk nested iframe jika tidak ada link yang ditemukan
        if (!linkFound && nestedIframes.isNotEmpty()) {
            for (nestedUrl in nestedIframes) {
                val nestedFound = loadLinks(nestedUrl, isCasting, subtitleCallback, callback)
                if (nestedFound) {
                    linkFound = true
                }
            }
        }

        return linkFound
    }
}