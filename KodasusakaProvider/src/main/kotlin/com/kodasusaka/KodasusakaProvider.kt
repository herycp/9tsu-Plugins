package com.kodasusaka

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class KodasusakaProvider : MainAPI() {
    override var mainUrl = "https://kodasusaka.com"
    override var name = "Kodasusaka"
    override var hasMainPage = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    // 5 menu halaman depan menggunakan API v2
    override val mainPage = mainPageOf(
        "/api/v2/movies?sortby=views" to "Top Views",
        "/api/v2/movies?sortby=tmdb" to "Top TMDB",
        "/api/v2/movies?sortby=imdb" to "Top IMDB",
        "/api/v2/movies?sortby=newest" to "Movie Newest",
        "/api/v2/tv-series?sortby=newest" to "TV Series Newest"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Menambahkan parameter page dan limit secara dinamis untuk pagination
        val url = if (request.data.contains("?")) {
            "$mainUrl${request.data}&limit=30&page=$page"
        } else {
            "$mainUrl${request.data}?limit=30&page=$page"
        }
        
        val response = app.get(url).parsedSafe<ApiListResponse>()

        val homeItems = response?.items?.mapNotNull { item ->
            item.toSearchResponse(mainUrl)
        } ?: emptyList()

        // Logika Pagination Otomatis dari API
        val currentPage = response?.page ?: 1
        val totalPages = response?.total_pages ?: 1
        val hasNext = currentPage < totalPages

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = homeItems,
                isHorizontalImages = false
            ),
            hasNext = hasNext // Cloudstream akan me-load page+1 jika ini true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Search bawaan Cloudstream hanya membutuhkan page 1
        val url = "$mainUrl/api/v2/search?q=$query&limit=30&page=1"
        val response = app.get(url).parsedSafe<ApiListResponse>()

        return response?.items?.mapNotNull { item ->
            item.toSearchResponse(mainUrl)
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').split("/").last()
        val apiUrl = "$mainUrl/api/v2/movies/$slug"

        val response = app.get(apiUrl).parsedSafe<ApiResponse>() ?: return null

        val title = response.title ?: return null
        val poster = fixUrlNull(response.poster?.takeIf { it.isNotBlank() } ?: response.thumb)
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
                        newEpisode(ep.sources_url) {
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
            val relPoster = fixUrlNull(rel.poster?.takeIf { it.isNotBlank() } ?: rel.thumb)
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
                    // Inject "/index.json" ke akhir URL agar format M3U8 bisa terbaca oleh player
                    val formattedUrl = if (fileUrl.endsWith("/")) {
                        "${fileUrl}index.json"
                    } else if (!fileUrl.endsWith("/index.json")) {
                        "$fileUrl/index.json"
                    } else {
                        fileUrl
                    }

                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = formattedUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                }
            }
            return true
        }
        return false
    }

    // --- Ekstensi Helper Pemetaan JSON ke SearchResponse ---
    private fun ApiListItem.toSearchResponse(mainUrl: String): SearchResponse? {
        val validTitle = title ?: return null
        val validSlug = slug ?: return null
        
        val url = "$mainUrl/$validSlug" 
        val img = poster?.takeIf { it.isNotBlank() } ?: thumb
        val posterUrl = fixUrlNull(img)
        val isTvSeries = kind.equals("tv-series", ignoreCase = true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(validTitle, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(validTitle, url, TvType.Movie) {
                this.posterUrl = posterUrl
                if (quality?.contains("HD", true) == true || episode_label?.contains("HD", true) == true) {
                    this.quality = SearchQuality.HD
                }
            }
        }
    }

    // --- Data Classes untuk Parsing API Web JSON ---
    data class ApiListResponse(
        @JsonProperty("items") val items: List<ApiListItem>?,
        @JsonProperty("page") val page: Int?,
        @JsonProperty("total_pages") val total_pages: Int?,
        @JsonProperty("total") val total: Int?,
        @JsonProperty("limit") val limit: Int?
    )

    data class ApiListItem(
        @JsonProperty("title") val title: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("thumb") val thumb: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("episode_label") val episode_label: String?
    )

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
