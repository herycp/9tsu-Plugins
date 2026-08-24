package com.dramika

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Dramika : MainAPI() {
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override var mainUrl = "https://dramika.com"
    override var name = "Dramika"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "/dramas/" to "Dramas",
        "/movies/" to "Movies",
        "/tvshows/" to "TV Shows"
    )

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${mainUrl}${request.data}page/$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val items = document.select("article.post, .type-post, .entry, .blog-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("h2 a, h3 a, .entry-title a, a[rel='bookmark']") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        val img = selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        return document.select("article.post, .type-post, .entry, .blog-item").mapNotNull { it.toSearchResult() }
    }

    // ==================== EKSTRAKSI EPISODE ====================
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

    // ==================== LOAD DETAIL ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title = document.selectFirst("h1.entry-title, h1.post-title, .title")?.text()?.trim()
            ?: return null

        val posterUrl = document.selectFirst(".post-thumbnail img, .entry-content img, .featured-image img, .wp-post-image")?.attr("src")
            ?.let { fixUrl(it) }

        val description = document.selectFirst(".entry-content, .post-content, .description, .summary")?.text()?.trim()

        // Cari daftar episode
        val episodeElements = document.select(".episode-list a, .episodes a, .season-list a, .episode-item a")
        val episodes = episodeElements.mapNotNull { el ->
            val epTitle = el.text().trim()
            val epLink = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epNum = extractEpisodeNumber(epTitle) ?: 0
            Triple(epTitle, epLink, epNum)
        }.sortedByDescending { it.third }

        if (episodes.isNotEmpty()) {
            val episodeList = episodes.map { (epTitle, epLink, _) ->
                newEpisode(epTitle) { this.data = epLink }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }

        // Jika tidak ada episode, anggap movie
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val doc = app.get(data, headers = mapOf("User-Agent" to userAgent)).document

        // ===== 1. Cari server dari #serverList =====
        val serverItems = doc.select("#serverList li.server-item.linkserver")
        if (serverItems.isNotEmpty()) {
            var anySuccess = false
            for (item in serverItems) {
                val videoUrl = item.attr("data-video")
                if (videoUrl.isBlank()) continue

                val cleanUrl = fixUrl(videoUrl)
                // Gunakan loadExtractor yang akan memanggil StreamTape, MixDrop, Vidmoly dll
                val result = loadExtractor(cleanUrl, subtitleCallback, callback)
                if (result) anySuccess = true
            }
            if (anySuccess) return true
        }

        // ===== 2. Fallback: cari iframe langsung =====
        val iframeSrc = doc.selectFirst("iframe#embedvideo, iframe.player-iframe")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            val cleanUrl = fixUrl(iframeSrc)
            return loadExtractor(cleanUrl, subtitleCallback, callback)
        }

        // ===== 3. Fallback: cari video source langsung =====
        val videoSrc = doc.selectFirst("video source")?.attr("src")
        if (!videoSrc.isNullOrBlank()) {
            val cleanUrl = fixUrl(videoSrc)
            val isM3u8 = cleanUrl.contains(".m3u8")
            callback(
                newExtractorLink(
                    name = if (isM3u8) "Dramika - HLS" else "Dramika - Direct",
                    source = name,
                    url = cleanUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        return false
    }

    // ==================== FUNGSI BANTU ====================
    private fun fixUrl(url: String): String {
        var fixed = url.trim()
        if (fixed.startsWith("//")) fixed = "https:$fixed"
        return fixed
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }
}