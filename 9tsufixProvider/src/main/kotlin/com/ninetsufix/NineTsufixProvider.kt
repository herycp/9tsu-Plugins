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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

class NineTsuFixProvider : MainAPI() {
    override var mainUrl = "https://9tsu.in"
    override var name = "9tsu (Fix)"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "ja" 
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"

    private val categoryPages = setOf(
        "/drama", "/monday", "/tuesday", "/wednesday", "/thursday",
        "/friday", "/saturday", "/sunday", "/daily", "/movie", "/spmovies",
        "/premium", "/housou-shuuryou", "/dramaend"
    )

    private data class EpisodeInfo(val link: String, val title: String)

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

    private fun normalizeJapaneseNumbers(text: String): String {
        return text.map { char ->
            when (char) {
                '０' -> '0'
                '１' -> '1'
                '２' -> '2'
                '３' -> '3'
                '４' -> '4'
                '５' -> '5'
                '６' -> '6'
                '７' -> '7'
                '８' -> '8'
                '９' -> '9'
                else -> char
            }
        }.joinToString("")
    }

    private fun extractEpisodeNumber(title: String): String? {
        val normalizedTitle = normalizeJapaneseNumbers(title)
        val regex = Regex("""(?i)第?\s*([\d.,-]+)\s*(?:話|夜|貫|話・夜)|(?:#|EP)\s*([\d.,-]+)|(前編|後編|中編|前篇|後篇)""")
        val match = regex.find(normalizedTitle)
        
        if (match != null) {
            val group1 = match.groups[1]?.value
            val group2 = match.groups[2]?.value
            val group3 = match.groups[3]?.value
            
            return group1 ?: group2 ?: group3
        }
        return null
    }

    private fun parseEpisodeStringToInt(epStr: String?): Int? {
        if (epStr == null) return null

        when (epStr) {
            "前編", "前篇" -> return 1
            "中編" -> return 2
            "後編", "後篇" -> return 3 
        }

        val match = Regex("""\d+""").find(epStr)
        return match?.value?.toIntOrNull()
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
                    "Referer" to "$mainUrl/?s=${cleanQuery.replace(" ", "+")}",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            )

            val html = response.text
            if (html.length < 20 || html.contains("invi no-posts")) {
                return newSearchResponseList(emptyList(), false)
            }

            val doc = Jsoup.parse(html)
            val items = doc.select("article, .post, .entry, .type-post, .item, .result-item, .blog-item, article.cactus-post-item, .cactus-post-item")

            val results = items.mapNotNull { element ->
                val titleElement = element.selectFirst("h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
                    ?: element.select("a").firstOrNull { it.text().trim().isNotBlank() }
                    ?: return@mapNotNull null

                val title = titleElement.text().trim()
                val link = titleElement.attr("href")

                if (title.isBlank() || link.isBlank() || !link.startsWith("http")) return@mapNotNull null

                val imgElement = element.selectFirst("img")
                var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
                if (posterUrl?.startsWith("data:image") == true) posterUrl = null

                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }.distinctBy { it.url }

            val hasNext = results.isNotEmpty()
            newSearchResponseList(results, hasNext)
        } catch (e: Exception) {
            newSearchResponseList(emptyList(), false)
        }
    }

    private fun isCategoryPage(url: String): Boolean {
        val path = url.replace(mainUrl, "").split("?")[0]
        return categoryPages.any { path == it || path.startsWith("$it/") }
    }

    private fun extractEpisodeInfo(doc: Document): List<EpisodeInfo> {
        return doc.select("article.cactus-post-item a[href*='/douga/']")
            .mapNotNull { element ->
                val href = element.attr("href")
                val link = when {
                    href.startsWith("http") -> href
                    href.startsWith("/") -> "https://9tsu.in$href"
                    else -> null
                } ?: return@mapNotNull null
                val title = element.attr("title").takeIf { it.isNotBlank() } ?: element.text().trim()
                if (title.isBlank()) return@mapNotNull null
                EpisodeInfo(link, title)
            }.distinctBy { it.link }
    }

    private fun getSeriesUrlFromBreadcrumb(doc: Document): String? {
        val breadcrumbNav = doc.selectFirst("nav.rank-math-breadcrumb, nav[aria-label='breadcrumbs'], .breadcrumb")
        if (breadcrumbNav != null) {
            val links = breadcrumbNav.select("a")
            for (i in links.size - 1 downTo 0) {
                val link = links[i]
                val href = link.attr("href")
                if (href.isNotBlank() && !href.equals(mainUrl) && !href.contains("/douga/") && !isCategoryPage(href)) {
                    return href
                }
            }
        }
        return null
    }

    private suspend fun loadAllEpisodes(seriesUrl: String): List<EpisodeInfo> {
        val allEpisodes = mutableListOf<EpisodeInfo>()
        val slug = seriesUrl.removePrefix(mainUrl).trimStart('/').split("/")[0].takeIf { it.isNotBlank() }
        if (slug == null) return emptyList()

        try {
            var page = 0
            var hasMore = true

            while (hasMore) {
                try {
                    val ajaxUrl = "https://9tsu.in/wp-admin/admin-ajax.php"
                    val params = mutableMapOf<String, String>()
                    params["action"] = "load_more"
                    params["page"] = page.toString()
                    params["template"] = "html/loop/content"
                    params["vars[category_name]"] = slug

                    val response = app.post(
                        ajaxUrl,
                        data = params,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to seriesUrl
                        )
                    )

                    if (response.code == 200) {
                        val text = response.text
                        val isEnd = text.contains("""<div class="invi no-posts">""")

                        if (text.isBlank() || text.length < 20) {
                            hasMore = false
                            break
                        }

                        val fragment = Jsoup.parse(text)
                        val episodes = extractEpisodeInfo(fragment)

                        if (episodes.isNotEmpty()) {
                            allEpisodes.addAll(episodes)
                        } else {
                            hasMore = false
                            break
                        }

                        if (isEnd) {
                            hasMore = false
                            break
                        }
                    } else {
                        hasMore = false
                        break
                    }
                } catch (e: Exception) {
                    hasMore = false
                    break
                }
                page++
                kotlinx.coroutines.delay(200)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return allEpisodes.distinctBy { it.link }
    }

    private suspend fun buildSeriesResponse(
        doc: Document,
        url: String,
        episodeList: List<EpisodeInfo>
    ): LoadResponse {
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

        if (episodeList.size == 1 && extractEpisodeNumber(episodeList.first().title) == null) {
            return newMovieLoadResponse(seriesTitle, url, TvType.Movie, episodeList.first().link) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }

        val reversedEpisodes = episodeList.reversed()

        val episodes = reversedEpisodes.map { episode ->
            val epNumStr = extractEpisodeNumber(episode.title)
            val parsedEpNum = parseEpisodeStringToInt(epNumStr)
            
            var cleanTitle = episode.title
            if (cleanTitle.contains(seriesTitle, ignoreCase = true)) {
                cleanTitle = cleanTitle.replace(seriesTitle, "", ignoreCase = true).trim()
                cleanTitle = cleanTitle.removePrefix("-").removePrefix("–").removePrefix(":").trim()
            }
            if (cleanTitle.isBlank()) cleanTitle = episode.title

            newEpisode(cleanTitle) {
                this.name = cleanTitle
                this.data = episode.link
                this.episode = parsedEpNum 
            }
        }

        return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        if (isCategoryPage(url)) {
            return loadSinglePage(doc, url)
        }

        if (url.contains("/douga/")) {
            var seriesUrl = getSeriesUrlFromBreadcrumb(doc)
            if (seriesUrl != null) {
                try {
                    val allEpisodes = loadAllEpisodes(seriesUrl)
                    if (allEpisodes.isNotEmpty()) {
                        val seriesDoc = app.get(seriesUrl, headers = mapOf("User-Agent" to userAgent)).document
                        return buildSeriesResponse(seriesDoc, seriesUrl, allEpisodes)
                    } else {
                        val seriesDoc = app.get(seriesUrl, headers = mapOf("User-Agent" to userAgent)).document
                        val fallbackEpisodes = extractEpisodeInfo(seriesDoc)
                        if (fallbackEpisodes.isNotEmpty()) {
                            return buildSeriesResponse(seriesDoc, seriesUrl, fallbackEpisodes)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val seriesLink = doc.select("a[rel='category']").firstOrNull()
            if (seriesLink != null) {
                val href = seriesLink.attr("href")
                if (href.isNotBlank() && !href.contains("/douga/") && !isCategoryPage(href)) {
                    try {
                        val allEpisodes = loadAllEpisodes(href)
                        if (allEpisodes.isNotEmpty()) {
                            val seriesDoc = app.get(href, headers = mapOf("User-Agent" to userAgent)).document
                            return buildSeriesResponse(seriesDoc, href, allEpisodes)
                        } else {
                            val seriesDoc = app.get(href, headers = mapOf("User-Agent" to userAgent)).document
                            val fallbackEpisodes = extractEpisodeInfo(seriesDoc)
                            if (fallbackEpisodes.isNotEmpty()) {
                                return buildSeriesResponse(seriesDoc, href, fallbackEpisodes)
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            return loadSinglePage(doc, url)
        }

        val episodeList = extractEpisodeInfo(doc)

        if (episodeList.isNotEmpty()) {
            val allEpisodes = loadAllEpisodes(url)
            if (allEpisodes.isNotEmpty()) {
                return buildSeriesResponse(doc, url, allEpisodes)
            } else {
                return buildSeriesResponse(doc, url, episodeList)
            }
        }

        return loadSinglePage(doc, url)
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

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                when {
                    src.contains("muxalor.guru") -> {
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
                    }
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
