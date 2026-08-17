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
import java.net.URLEncoder

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
                '０' -> '0'; '１' -> '1'; '２' -> '2'; '３' -> '3'; '４' -> '4'
                '５' -> '5'; '６' -> '6'; '７' -> '7'; '８' -> '8'; '９' -> '9'
                else -> char
            }
        }.joinToString("")
    }

    private fun extractEpisodeNumber(title: String): String? {
        val normalizedTitle = normalizeJapaneseNumbers(title)
        val regex = Regex("""(?i)第?\s*([\d.,-]+)\s*(?:話|夜|貫|話・夜)|(?:#|EP)\s*([\d.,-]+)|(前編|後編|中編|前篇|後篇)""")
        val match = regex.find(normalizedTitle)
        if (match != null) {
            return match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value
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
        return Regex("""\d+""").find(epStr)?.value?.toIntOrNull()
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
        
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val postData = "action=load_more&page=$ajaxPage&searchPage=true&template=html%2Floop%2Fcontent&vars%5Bs%5D=$encodedQuery"

        return try {
            val response = app.post(
                ajaxUrl,
                requestBody = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"),
                    postData
                ),
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

    private fun isCategoryPage(url: String): Boolean {
        val path = url.replace(mainUrl, "").split("?")[0]
        return categoryPages.any { path == it || path.startsWith("$it/") }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val seriesTitle = doc.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() ?: doc.title()
        return newMovieLoadResponse(seriesTitle, url, TvType.Movie, url) {
            this.plot = doc.selectFirst(".body-content")?.text()?.trim() ?: ""
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return true
    }
}
