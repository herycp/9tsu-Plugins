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
        val url = if (page == 1) {
            "${mainUrl}${request.data}"
        } else {
            "${mainUrl}${request.data}page/$page/"
        }
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val items = document.select("a.group").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val img = selectFirst("img")
        val title = img?.attr("alt")?.replace("Poster for ", "")?.trim()
            ?: selectFirst("h2")?.text()?.trim()
            ?: return null

        val href = fixUrlNull(attr("href")) ?: return null
        val posterUrl = fixUrlNull(img?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        return document.select("a.group").mapNotNull { it.toSearchResult() }
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

        // Judul: h1.text-3xl atau h1.text-4xl
        val title = document.selectFirst("h1.text-3xl, h1.text-4xl")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        // Poster: img.wp-post-image atau img.attachment-full
        val posterUrl = document.selectFirst("img.wp-post-image, img.attachment-full")?.attr("src")
            ?.let { fixUrl(it) }

        // Deskripsi: ambil semua paragraf di div.my-4 atau div.leading-relaxed
        val description = document.select("div.my-4 p, div.leading-relaxed p").joinToString("\n\n") { it.text().trim() }
            .ifEmpty { document.select(".text-secondary.leading-relaxed").text() }

        // Daftar episode: dari div.flex.flex-wrap.gap-2 a
        val episodeElements = document.select("div.flex.flex-wrap.gap-2 a")
        val episodes = episodeElements.mapNotNull { el ->
            val epNumText = el.text().trim()
            val epNum = epNumText.toIntOrNull() ?: extractEpisodeNumber(epNumText)
            if (epNum == null || epNum <= 0) return@mapNotNull null
            val epLink = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epTitle = "Episode $epNum"
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

        // Cari server list: #serverList li.server-item.linkserver
        val serverItems = doc.select("#serverList li.server-item.linkserver")
        if (serverItems.isNotEmpty()) {
            var anySuccess = false
            for (item in serverItems) {
                val videoUrl = item.attr("data-video")
                if (videoUrl.isBlank()) continue

                val cleanUrl = fixUrl(videoUrl)
                val result = loadExtractor(cleanUrl, subtitleCallback, callback)
                if (result) anySuccess = true
            }
            if (anySuccess) return true
        }

        // Fallback: iframe langsung
        val iframeSrc = doc.selectFirst("iframe#embedvideo, iframe")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            val cleanUrl = fixUrl(iframeSrc)
            return loadExtractor(cleanUrl, subtitleCallback, callback)
        }

        // Fallback: video source
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