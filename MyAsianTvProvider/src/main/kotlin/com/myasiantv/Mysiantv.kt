package com.myasiantv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class MyAsianTv : MainAPI() {
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://myasiantv.com.bz"
    override var name = "MyAsianTv"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultHeaders = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)

    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/drama/" to "Drama",
        "/drama/?selCountry=Japanese&btnFilter=Submit" to "Drama Jepang",
        "/drama/?selCountry=Thailand&btnFilter=Submit" to "Drama Thailand",
        "/movies-list/" to "Movies",
        "/movies-list/?selCountry=Japanese&btnFilter=Submit" to "Movie Jepang",
        "/movies-list/?selCountry=Thailand&btnFilter=Submit" to "Movie Thailand",
        "/shows/" to "TV Shows"
    )

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "${mainUrl}${request.data}" else "${mainUrl}${request.data}page/$page/"
        val document = app.get(url, headers = defaultHeaders).document

        val items = when {
            request.data == "/" -> {
                // Homepage: extract episode links and convert to series
                document.select("ul.items li, .episode-new .list li, .latest-episodes li")
                    .mapNotNull { it.toSeriesFromEpisode() }
                    .distinctBy { it.url } // deduplicate series
            }
            else -> {
                // Standard list page (drama, movies, shows with or without filters)
                document.select("ul.items li").mapNotNull { it.toSearchResult() }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // Convert an episode list item to a series SearchResponse
    private fun Element.toSeriesFromEpisode(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val episodeHref = fixUrlNull(link.attr("href")) ?: return null
        // Extract series slug from episode URL: e.g., /love-on-the-menu-2026-ep-1-eng-sub/ -> /series/love-on-the-menu-2026/
        val seriesSlug = episodeHref.replace(Regex("""-ep-\d+-eng-sub/?$"""), "").trimEnd('/')
        val seriesUrl = "$mainUrl/series$seriesSlug" // Ensure correct format

        val img = selectFirst("img")
        var title = img?.attr("alt")?.replace("Poster for ", "")?.trim()
        if (title.isNullOrEmpty()) {
            title = link.text().trim()
        }
        if (title.isNullOrEmpty()) return null

        var posterUrl = img?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = img?.attr("src")
        posterUrl = fixUrlNull(posterUrl)

        return newAnimeSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = defaultHeaders
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val img = selectFirst("img")
        var title = img?.attr("alt")?.replace("Poster for ", "")?.trim()
        if (title.isNullOrEmpty()) {
            title = selectFirst("h2")?.text()?.trim()
        }
        if (title.isNullOrEmpty()) return null

        var posterUrl = img?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = img?.attr("src")
        posterUrl = fixUrlNull(posterUrl)

        // Determine if it's a movie or series based on URL or title
        val isMovie = href.contains("/movies/") || title.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.posterHeaders = defaultHeaders
        }
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = defaultHeaders).document
        return document.select("ul.items li").mapNotNull { it.toSearchResult() }
    }

    // ==================== EXTRACT EPISODE NUMBER ====================
    private fun extractEpisodeNumber(title: String): Int? {
        title.toIntOrNull()?.let { if (it > 0) return it }
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

    // ==================== LOAD DETAIL (SERIES / MOVIE) ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        var rawPoster = document.selectFirst("img.wp-post-image, img.attachment-full")?.attr("data-src")
        if (rawPoster.isNullOrBlank()) rawPoster = document.selectFirst("img.wp-post-image, img.attachment-full")?.attr("src")
        val cleanPosterUrl = fixUrlNull(rawPoster)

        val description = document.select("div.text-secondary.leading-relaxed p")
            .joinToString("\n\n") { it.text().trim() }
            .ifEmpty { document.select(".text-secondary.leading-relaxed").text() }

        // Check for episode list
        val episodeElements = document.select("ul.list-episode li")
        val episodes = episodeElements.mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            val epLink = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val epTitle = link.text().trim()
            val epNum = extractEpisodeNumber(epTitle) ?: return@mapNotNull null
            Triple(epTitle, epLink, epNum)
        }.sortedBy { it.third }

        // Determine if it's a movie or series
        // If there are multiple episodes -> series
        if (episodes.size > 1) {
            val episodeList = episodes.map { (epTitle, epLink, _) ->
                newEpisode(epTitle) { this.data = epLink }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = cleanPosterUrl
                this.posterHeaders = defaultHeaders
                this.plot = description
            }
        }

        // If there is exactly one episode
        if (episodes.size == 1) {
            val (epTitle, epLink, _) = episodes.first()
            // Check if it's a movie: title contains "Movie" or episode title does not contain "Ep" / "Episode"
            val isMovie = title.contains("Movie", ignoreCase = true) ||
                    !epTitle.contains(Regex("""(?i)\b(ep|episode|e)\b""")) // no episode indicator

            if (isMovie) {
                // Movie: return movie response with the episode link as the data
                return newMovieLoadResponse(title, url, TvType.Movie, epLink) {
                    this.posterUrl = cleanPosterUrl
                    this.posterHeaders = defaultHeaders
                    this.plot = description
                }
            } else {
                // Single-episode series
                val episodeList = listOf(newEpisode(epTitle) { this.data = epLink })
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                    this.posterUrl = cleanPosterUrl
                    this.posterHeaders = defaultHeaders
                    this.plot = description
                }
            }
        }

        // No episodes found: maybe it's a movie page with embedded player directly
        // Check if there is an iframe
        val iframe = document.selectFirst("iframe#b, iframe")
        if (iframe != null) {
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank()) {
                return newMovieLoadResponse(title, url, TvType.Movie, src) {
                    this.posterUrl = cleanPosterUrl
                    this.posterHeaders = defaultHeaders
                    this.plot = description
                }
            }
        }

        return null
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        // data could be an episode URL (like /love-on-the-menu-2026-ep-1-eng-sub/) or an iframe URL (kisskh.space)
        // If it's a relative URL, fetch the episode page and get the iframe
        var finalUrl = data
        if (!data.startsWith("http")) {
            val doc = app.get("$mainUrl$data", headers = defaultHeaders).document
            val iframeElement = doc.selectFirst("iframe#b, iframe")
            var iframeSrc = iframeElement?.attr("data-src")
            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = iframeElement?.attr("src")
            }
            if (iframeSrc.isNullOrBlank()) return false
            finalUrl = fixUrl(iframeSrc)
        }

        // Now load the iframe (kisskh.space) to get video sources
        val embedDoc = app.get(finalUrl, headers = defaultHeaders).document
        var anySuccess = false

        // Try to find server items
        val serverItems = embedDoc.select(".server-item, li[data-video]")
        if (serverItems.isNotEmpty()) {
            for (item in serverItems) {
                val videoUrl = item.attr("data-video")
                if (videoUrl.isNotBlank()) {
                    val cleanVideoUrl = fixUrl(videoUrl)
                    val serverName = item.text().trim().ifBlank { "Server" }

                    // Try extractor
                    val extractorFound = loadExtractor(cleanVideoUrl, subtitleCallback, callback)

                    // Manual backup
                    val manualFound = manualExtractor(cleanVideoUrl, serverName, callback)

                    if (extractorFound || manualFound) anySuccess = true
                }
            }
        } else {
            // No server items, try to find a nested iframe
            var deepIframe = embedDoc.selectFirst("iframe")?.attr("data-src")
            if (deepIframe.isNullOrBlank()) deepIframe = embedDoc.selectFirst("iframe")?.attr("src")
            if (!deepIframe.isNullOrBlank()) {
                val cleanDeepIframe = fixUrl(deepIframe)
                val extractorFound = loadExtractor(cleanDeepIframe, subtitleCallback, callback)
                val manualFound = manualExtractor(cleanDeepIframe, "Server", callback)
                if (extractorFound || manualFound) anySuccess = true
            }
        }

        // Fallback: direct video source in <video> tag
        if (!anySuccess) {
            val videoSrc = embedDoc.selectFirst("video source")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                val cleanUrl = fixUrl(videoSrc)
                val isM3u8 = cleanUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        name = if (isM3u8) "MyAsianTv - HLS" else "MyAsianTv - Direct",
                        source = name,
                        url = cleanUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                anySuccess = true
            }
        }

        return anySuccess
    }

    // ==================== MANUAL EXTRACTOR (BACKUP) ====================
    private suspend fun manualExtractor(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val playerHtml = app.get(url, headers = defaultHeaders).text

            val m3u8Regex = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
            val mp4Regex = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")

            val m3u8Match = m3u8Regex.find(playerHtml)
            if (m3u8Match != null) {
                callback(
                    newExtractorLink(
                        name = "$serverName (Manual HLS)",
                        source = name,
                        url = m3u8Match.groupValues[1],
                        type = ExtractorLinkType.M3U8
                    )
                )
                return true
            }

            val mp4Match = mp4Regex.find(playerHtml)
            if (mp4Match != null) {
                callback(
                    newExtractorLink(
                        name = "$serverName (Manual MP4)",
                        source = name,
                        url = mp4Match.groupValues[1],
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    // ==================== HELPER FUNCTIONS ====================
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
