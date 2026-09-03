package com.kodasusaka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KodasusakaProvider : MainAPI() {
    override var mainUrl = "https://kodasusaka.com"
    override var name = "Kodasusaka"
    override var hasMainPage = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "/top/tmdb" to "Top TMDB",
        "/top/imdb" to "Top IMDB",
        "/top/views" to "Top Views",
        "/type/series" to "Series Terbaru",
        "/type/movie" to "Film Terbaru"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val doc = app.get(url).document

        val homeItems = doc.select("article.group").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = homeItems,
                isHorizontalImages = false
            ),
            hasNext = homeItems.isNotEmpty() && doc.selectFirst("nav[aria-label=Pagination] a:contains(Next), nav[aria-label=Pagination] a:contains(Selanjutnya), a[rel=next]") != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query&page=1"
        val doc = app.get(url).document

        return doc.select("article.group").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            doc.selectFirst("img[src*=/uploads/]")?.attr("src")
                ?: doc.selectFirst("article img")?.attr("src")
        )

        val description = doc.selectFirst("p.text-gray-300, p.text-mist-400, div.synopsis")?.text()?.trim()
        val tags = doc.select("a[href*=/genre/]").map { it.text().trim() }
        val year = doc.selectFirst("span:contains(20)")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val episodeElements = doc.select("a[href*=/episode/], div.episodes-list a")
        val isTvSeries = episodeElements.isNotEmpty()

        return if (isTvSeries) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = ep.attr("href")
                val epName = ep.text().trim()
                val epNum = epName.filter { it.isDigit() }.toIntOrNull()

                newEpisode(fixUrl(epHref)) {
                    this.name = epName
                    this.episode = epNum
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val iframeUrl = doc.selectFirst("iframe")?.attr("src")
            ?: doc.selectFirst("div[data-embed]")?.attr("data-embed")

        if (!iframeUrl.isNullOrEmpty()) {
            val fixedIframe = fixUrl(iframeUrl)
            loadExtractor(fixedIframe, data, subtitleCallback, callback)
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")

        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val badgeText = this.selectFirst("span.backdrop-blur-sm, span.absolute")?.text()?.trim()

        val isTvSeries = badgeText?.contains("EP", ignoreCase = true) == true ||
                href.contains("/series/", ignoreCase = true) ||
                href.contains("/tv-series", ignoreCase = true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = posterUrl
                if (badgeText?.contains("FULLHD", ignoreCase = true) == true ||
                    badgeText?.contains("HD", ignoreCase = true) == true) {
                    this.quality = SearchQuality.HD
                }
            }
        }
    }
}
