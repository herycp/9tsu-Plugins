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
        "/movies?sortby=newest" to "Movies - Newest",
        "/movies?sortby=latest-update" to "Movies - Latest Update",
        "/movies?sortby=mostview" to "Movies - Most View",
        "/tv-series?sortby=newest" to "TV Series - Newest",
        "/tv-series?sortby=latest-update" to "TV Series - Latest Update",
        "/tv-series?sortby=mostview" to "TV Series - Most View"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("?")) {
            "$mainUrl${request.data}&page=$page"
        } else {
            "$mainUrl${request.data}?page=$page"
        }
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

        val title = doc.selectFirst("h1.cls-info-title")?.text()?.trim() ?: return null
        
        // A. Gambar halaman (Backdrop / Background) & Poster
        val poster = fixUrlNull(doc.selectFirst("div.cls-poster-lg img")?.attr("src"))
        val background = fixUrlNull(doc.selectFirst("div.cls-backdrop-gallery a.cls-bd-item")?.attr("href"))

        // C. Deskripsi film
        val description = doc.selectFirst("div.cls-prose")?.text()?.trim()
        
        // E. Kategori / Genre
        val tags = doc.select("div.cls-info-people:contains(Genre) a").map { it.text().trim() }
        
        // D. Nama Aktor
        val actors = doc.select("div.cls-info-people:contains(Cast) a").map { it.text().trim() }
        
        val year = doc.selectFirst("a.cls-badge[href^=/year/]")?.text()?.toIntOrNull()

        // B. Trailer (Link YouTube)
        val trailerUrl = doc.selectFirst("a:contains(Watch the trailer), a.cls-btn[href*='youtube.com']")?.attr("href")

        // F. Link halaman untuk player (di button play)
        val watchBtn = doc.selectFirst("a.cls-btn-watch")?.attr("href")
        val watchUrl = fixUrlNull(watchBtn) ?: url

        // H. Cek apakah ini TV Series & tarik tombol episode-nya
        val episodeElements = doc.select("a[href*=/episode/], div.episodes-list a")
        val isTvSeries = episodeElements.isNotEmpty() || doc.select(".cls-badges .cls-badge").text().contains("EP", ignoreCase = true)

        return if (isTvSeries) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = ep.attr("href")
                val epName = ep.text().trim()
                val epNum = epName.filter { it.isDigit() }.toIntOrNull()

                newEpisode(fixUrl(epHref)) {
                    this.name = epName
                    this.episode = epNum
                }
            }.ifEmpty {
                listOf(
                    newEpisode(watchUrl) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.tags = tags
                this.year = year
                this.trailerUrl = trailerUrl
                this.actors = actors.map { ActorData(Actor(it)) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.tags = tags
                this.year = year
                this.trailerUrl = trailerUrl
                this.actors = actors.map { ActorData(Actor(it)) }
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
