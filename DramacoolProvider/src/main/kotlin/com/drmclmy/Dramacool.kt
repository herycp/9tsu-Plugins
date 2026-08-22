package com.drmclmy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Dramacool : MainAPI() {
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://dramacool.my"
    override var name = "Dramacool"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "recently-added" to "Recently Added",
        "recently-added-movie" to "Recently Added Movies",
        "most-popular-drama" to "Most Popular",
        "popular-ongoing-series" to "Ongoing Series",
        "popular-completed-series" to "Completed Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}?page=$page"
        val document = app.get(url).document
        val items = document.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3.title")?.text()?.trim() ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val img = selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-original") ?: img?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?type=movies&keyword=${query.replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst(".movie-title")?.text()?.trim()
            ?: return null

        val posterUrl = document.selectFirst("img.poster")?.attr("src")
            ?: document.selectFirst(".film-poster img")?.attr("src")
            ?.let { fixUrl(it) }

        val episodeElements = document.select(
            "div.epdiv a, " +
            "ul.episode-list li a, " +
            ".episodes-list li a, " +
            ".server .episode-item a, " +
            "#episode-list a"
        )
        val episodes = episodeElements.mapNotNull { el ->
            val epName = el.text().trim().ifEmpty { "Episode" }
            val epLink = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            newEpisode(epName) {
                this.data = epLink
            }
        }.reversed()

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Cari iframe dari tombol play
        var iframeUrl = document.selectFirst("#load-iframe")?.attr("onclick")
            ?.substringAfter("playThis(\"")?.substringBefore("\")")
        if (iframeUrl == null) {
            iframeUrl = document.selectFirst("iframe")?.attr("src")
        }
        if (iframeUrl == null) {
            // Cari video source langsung
            val videoSrc = document.selectFirst("video source")?.attr("src")
            if (videoSrc != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(videoSrc),
                        referer = mainUrl,
                        quality = QUALITY_UNKNOWN,
                        isM3u8 = videoSrc.endsWith(".m3u8")
                    )
                )
                return true
            }
            return false
        }

        val iframeFullUrl = fixUrl(iframeUrl)
        val iframe = app.get(iframeFullUrl)
        val iframeDoc = iframe.document

        return loadExtractor(iframeDoc.html(), mainUrl, subtitleCallback, callback)
    }
}
