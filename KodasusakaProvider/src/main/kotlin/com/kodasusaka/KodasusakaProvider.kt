package com.kodasusaka

import com.fasterxml.jackson.annotation.JsonProperty
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
        "/tv-series?sortby=newest" to "TV Series - Newest"
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
        val slug = url.trimEnd('/').split("/").last()
        val apiUrl = "$mainUrl/api/v2/movies/$slug"

        val response = app.get(apiUrl).parsedSafe<ApiResponse>() ?: return null

        val title = response.title ?: return null
        val poster = fixUrlNull(response.poster ?: response.thumb)
        val backdrops = response.backdrops ?: emptyList()
        val background = fixUrlNull(backdrops.firstOrNull())
        val description = response.overview
        val tags = response.genres?.mapNotNull { it.name } ?: emptyList()
        val actors = response.actors?.mapNotNull { it.name } ?: emptyList()
        val year = response.year
        val isTvSeries = response.kind.equals("tv-series", ignoreCase = true) || (response.seasons ?: 1) > 1

        val episodes = mutableListOf<Episode>()
        response.servers?.forEach { server ->
            server.episodes?.forEach { ep ->
                if (!ep.sources_url.isNullOrEmpty()) {
                    episodes.add(
                        newEpisode(ep.sources_url!!) {
                            this.name = ep.name ?: "Episode ${ep.number}"
                            this.episode = ep.number
                            this.season = ep.season
                        }
                    )
                }
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Full Movie"
                    this.episode = 1
                }
            )
        }

        val recommendations = response.related?.mapNotNull { rel ->
            val relSlug = rel.slug ?: return@mapNotNull null
            val relTitle = rel.title ?: "Unknown"
            val relPoster = fixUrlNull(rel.poster ?: rel.thumb)
            val relType = if (rel.kind.equals("tv-series", ignoreCase = true)) TvType.TvSeries else TvType.Movie
            
            if (relType == TvType.TvSeries) {
                newTvSeriesSearchResponse(relTitle, "$mainUrl/$relSlug", relType) {
                    this.posterUrl = relPoster
                }
            } else {
                newMovieSearchResponse(relTitle, "$mainUrl/$relSlug", relType) {
                    this.posterUrl = relPoster
                }
            }
        } ?: emptyList()

        return if (isTvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.tags = tags
                this.year = year
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.tags = tags
                this.year = year
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("/api/v1/episodes/")) {
            val sourceResponse = app.get(data).parsedSafe<SourceApiResponse>()
            sourceResponse?.sources?.forEach { src ->
                val fileUrl = src.file
                if (!fileUrl.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = fileUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value
                        ) {
                            this.isM3u8 = fileUrl.contains(".m3u8", ignoreCase = true)
                        }
                    )
                }
            }
            return true
        }

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

    data class ApiResponse(
        @JsonProperty("title") val title: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("thumb") val thumb: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("trailer_url") val trailer_url: String?,
        @JsonProperty("seasons") val seasons: Int?,
        @JsonProperty("backdrops") val backdrops: List<String>?,
        @JsonProperty("genres") val genres: List<GenreModel>?,
        @JsonProperty("actors") val actors: List<ActorModel>?,
        @JsonProperty("servers") val servers: List<ServerModel>?,
        @JsonProperty("related") val related: List<RelatedModel>?
    )

    data class GenreModel(@JsonProperty("name") val name: String?)
    data class ActorModel(@JsonProperty("name") val name: String?)
    data class ServerModel(
        @JsonProperty("episodes") val episodes: List<EpisodeModel>?
    )
    data class EpisodeModel(
        @JsonProperty("name") val name: String?,
        @JsonProperty("number") val number: Int?,
        @JsonProperty("season") val season: Int?,
        @JsonProperty("sources_url") val sources_url: String?
    )
    data class RelatedModel(
        @JsonProperty("title") val title: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("thumb") val thumb: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class SourceApiResponse(
        @JsonProperty("sources") val sources: List<SourceItem>?,
        @JsonProperty("success") val success: Boolean?
    )
    data class SourceItem(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?
    )
}
