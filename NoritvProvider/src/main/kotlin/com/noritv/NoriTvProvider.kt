package com.noritv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder

class NoriTvProvider : MainAPI() {
    override var mainUrl = "https://noritv.com"
    override var name = "NoriTV"
    override val hasMainPage = true
    override var lang = "ja"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val restApiUrl = "https://api.noritv.com/rest/v1"
    private val streamDomain = "https://s1.noritv.com"

    private val defaultHeaders = mapOf(
        "referer" to mainUrl,
        "Origin" to mainUrl
    )

    private val restHeaders = defaultHeaders + mapOf(
        "apikey" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiYXVkIjoiYXV0aGVudGljYXRlZCJ9.IZicfGNGV2-jcsik_rRWGIAMDkc7CVQFbUpU5nrlCgw",
        "authorization" to "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiYXVkIjoiYXV0aGVudGljYXRlZCJ9.IZicfGNGV2-jcsik_rRWGIAMDkc7CVQFbUpU5nrlCgw"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?type=movie&sort=recommended&pageSize=24&page=" to "Movie (Recommended)",
        "$mainUrl/api/movies?type=series&sort=recommended&pageSize=24&page=" to "Series (Recommended)",
        "$mainUrl/api/movies?type=movie&sort=year_desc&pageSize=24&page=" to "Movie (Newest)",
        "$mainUrl/api/movies?type=series&sort=year_desc&pageSize=24&page=" to "Series (Newest)"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val response = app.get(url, headers = defaultHeaders).text
        val parsed = parseJson<ApiResponse>(response)

        val items = parsed.items.mapNotNull { item ->
            item.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/movies?q=$encodedQuery&page=1&pageSize=24"
        val response = app.get(url, headers = defaultHeaders).text
        val parsed = parseJson<ApiResponse>(response)

        return parsed.items.mapNotNull { item ->
            item.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size < 2) return null
        
        val type = parts[0]
        val slug = URLEncoder.encode(parts[1], "UTF-8")

        return if (type == "series") {
            val seriesUrl = "$restApiUrl/series?select=id%2Cslug%2Ctitle%2Coriginal_title%2Ctitle_kana%2Cyear%2Corigin_country%2Cdescription%2Cposter_url%2Cbanner_url%2Cseries_genres(genre%3Agenres(slug%2Cname%2Cname_ja))%2Cepisodes(id%2Cseries_id%2Ccollection_id%2Cseason_number%2Cepisode_number%2Ctitle%2Cvideo_path%2Cthumbnail_url%2Cduration_minutes)&slug=eq.$slug"
            val response = app.get(seriesUrl, headers = restHeaders).text
            val parsed = parseJson<List<SeriesDetail>>(response).firstOrNull() ?: return null

            val episodesList = parsed.episodes?.mapNotNull { ep ->
                val videoPath = ep.video_path ?: return@mapNotNull null
                newEpisode(videoPath) {
                    this.name = ep.title ?: "Episode ${ep.episode_number ?: 1}"
                    this.episode = ep.episode_number
                    this.season = ep.season_number
                }
            } ?: emptyList()

            newTvSeriesLoadResponse(
                parsed.title ?: "Unknown Series",
                url,
                TvType.TvSeries,
                episodesList
            ) {
                this.posterUrl = fixUrl(parsed.poster_url ?: parsed.banner_url ?: "")
                this.plot = parsed.description
                this.year = parsed.year
            }
        } else {
            val movieUrl = "$restApiUrl/movies?select=id%2Cslug%2Ctitle%2Coriginal_title%2Ctitle_kana%2Cyear%2Corigin_country%2Cdescription%2Cposter_url%2Cbanner_url%2Cmovie_genres(genre%3Agenres(slug%2Cname%2Cname_ja))%2Cmovie_parts(id%2Cmovie_id%2Ccollection_id%2Cpart_number%2Ctitle%2Cvideo_path%2Cthumbnail_url%2Cduration_minutes%2Csubtitles)&slug=eq.$slug"
            val response = app.get(movieUrl, headers = restHeaders).text
            val parsed = parseJson<List<MovieDetail>>(response).firstOrNull() ?: return null

            val videoPath = parsed.movie_parts?.firstOrNull()?.video_path ?: ""

            newMovieLoadResponse(
                parsed.title ?: "Unknown Movie",
                url,
                TvType.Movie,
                videoPath
            ) {
                this.posterUrl = fixUrl(parsed.poster_url ?: parsed.banner_url ?: "")
                this.plot = parsed.description
                this.year = parsed.year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val streamUrl = "$streamDomain$data"

        callback.invoke(
            ExtractorLink(
                source = name,
                name = "S1 NoriTV",
                url = streamUrl,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = defaultHeaders
            )
        )
        return true
    }

    private fun ApiItem.toSearchResponse(): SearchResponse? {
        val itemTitle = this.title ?: return null
        val itemSlug = this.slug ?: return null
        val itemType = this.type ?: "movie"
        
        val mappedType = if (itemType == "series") TvType.TvSeries else TvType.Movie
        val horizontalPoster = fixUrl(this.bannerUrl ?: this.thumbnail ?: "")

        return newTvSeriesSearchResponse(
            itemTitle,
            "$itemType|$itemSlug",
            mappedType,
            false
        ) {
            this.posterUrl = horizontalPoster
            this.posterHeaders = defaultHeaders
        }
    }

    // --- Data Classes Penyesuaian JSON API --- //

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiResponse(
        @JsonProperty("items") val items: List<ApiItem> = emptyList(),
        @JsonProperty("total") val total: Int? = 0,
        @JsonProperty("page") val page: Int? = 1,
        @JsonProperty("pageSize") val pageSize: Int? = 24
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("bannerUrl") val bannerUrl: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeriesDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("banner_url") val banner_url: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeDetail>? = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season_number") val season_number: Int? = null,
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("video_path") val video_path: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MovieDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("banner_url") val banner_url: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("movie_parts") val movie_parts: List<MoviePartDetail>? = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MoviePartDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("video_path") val video_path: String? = null
    )
}