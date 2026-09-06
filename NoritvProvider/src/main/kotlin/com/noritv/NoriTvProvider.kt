package com.noritv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class NoriTvProvider : MainAPI() {
    override var mainUrl = "https://noritv.com"
    override var name = "NoriTV"
    override val hasMainPage = true
    override var lang = "ja"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val restApiUrl = "https://api.noritv.com/rest/v1"
    private val streamDomain = "https://s1.noritv.com"

    // Header wajib untuk semua URL
    private val defaultHeaders = mapOf(
        "referer" to mainUrl,
        "Origin" to mainUrl
    )

    // Header khusus untuk API REST
    private val restHeaders = defaultHeaders + mapOf(
        "apikey" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiYXVkIjoiYXV0aGVudGljYXRlZCJ9.IZicfGNGV2-jcsik_rRWGIAMDkc7CVQFbUpU5nrlCgw",
        "authorization" to "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiYXVkIjoiYXV0aGVudGljYXRlZCJ9.IZicfGNGV2-jcsik_rRWGIAMDkc7CVQFbUpU5nrlCgw"
    )

    // Daftar Halaman Utama
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
        // Terapkan mekanisme pagination dengan mengganti nilai page
        val url = request.data + page
        val response = app.get(url, headers = defaultHeaders).text
        val parsed = parseJson<ApiResponse>(response)

        val items = parsed.items.mapNotNull { item ->
            item.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // URL pencarian dengan pagination diset ke page 1 secara default
        val url = "$mainUrl/api/movies?q=${java.net.URLEncoder.encode(query, "utf-8")}&page=1&pageSize=24"
        val response = app.get(url, headers = defaultHeaders).text
        val parsed = parseJson<ApiResponse>(response)

        return parsed.items.mapNotNull { item ->
            item.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (type, slug) = url.split("|")

        return if (type == "series") {
            // Hit api untuk membuka detail series
            val seriesUrl = "$restApiUrl/series?select=id%2Cslug%2Ctitle%2Coriginal_title%2Ctitle_kana%2Cyear%2Corigin_country%2Cdescription%2Cposter_url%2Cbanner_url%2Cseries_genres(genre%3Agenres(slug%2Cname%2Cname_ja))%2Cepisodes(id%2Cseries_id%2Ccollection_id%2Cseason_number%2Cepisode_number%2Ctitle%2Cvideo_path%2Cthumbnail_url%2Cduration_minutes)&slug=eq.$slug"
            val response = app.get(seriesUrl, headers = restHeaders).text
            val parsed = parseJson<List<SeriesDetail>>(response).firstOrNull() ?: return null

            newTvSeriesLoadResponse(
                parsed.title,
                url,
                TvType.TvSeries,
                parsed.episodes?.map { ep ->
                    newEpisode(ep.video_path ?: "") {
                        this.name = ep.title
                        this.episode = ep.episode_number
                    }
                } ?: emptyList()
            ) {
                this.posterUrl = fixUrl(parsed.poster_url ?: "")
                this.plot = parsed.description
                this.year = parsed.year
            }
        } else {
            // Hit api untuk membuka detail movie
            val movieUrl = "$restApiUrl/movies?select=id%2Cslug%2Ctitle%2Coriginal_title%2Ctitle_kana%2Cyear%2Corigin_country%2Cdescription%2Cposter_url%2Cbanner_url%2Cmovie_genres(genre%3Agenres(slug%2Cname%2Cname_ja))%2Cmovie_parts(id%2Cmovie_id%2Ccollection_id%2Cpart_number%2Ctitle%2Cvideo_path%2Cthumbnail_url%2Cduration_minutes%2Csubtitles)&slug=eq.$slug"
            val response = app.get(movieUrl, headers = restHeaders).text
            val parsed = parseJson<List<MovieDetail>>(response).firstOrNull() ?: return null

            // Mengambil part video (biasanya 1 part untuk movie)
            val videoPath = parsed.movie_parts?.firstOrNull()?.video_path ?: ""

            newMovieLoadResponse(
                parsed.title,
                url,
                TvType.Movie,
                videoPath
            ) {
                this.posterUrl = fixUrl(parsed.poster_url ?: "")
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
        // Gabungkan domain s1 dengan path video
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

    private fun ApiItem.toSearchResponse(): SearchResponse {
        val mappedType = if (this.type == "series") TvType.TvSeries else TvType.Movie
        // Menggunakan bannerUrl agar poster berbentuk horizontal (Landscape)
        val horizontalPoster = fixUrl(this.bannerUrl ?: this.thumbnail ?: "")

        return newTvSeriesSearchResponse(
            this.title,
            "${this.type}|${this.slug}", // Menyimpan tipe dan slug untuk proses load halaman detail
            mappedType,
            false
        ) {
            this.posterUrl = horizontalPoster
            this.posterHeaders = defaultHeaders
        }
    }

    // --- Data Classes Penyesuaian JSON API --- //

    data class ApiResponse(
        @JsonProperty("items") val items: List<ApiItem> = emptyList(),
        @JsonProperty("total") val total: Int = 0,
        @JsonProperty("page") val page: Int = 1,
        @JsonProperty("pageSize") val pageSize: Int = 24
    )

    data class ApiItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("type") val type: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("bannerUrl") val bannerUrl: String?
    )

    data class SeriesDetail(
        @JsonProperty("title") val title: String,
        @JsonProperty("description") val description: String?,
        @JsonProperty("poster_url") val poster_url: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("episodes") val episodes: List<EpisodeDetail>?
    )

    data class EpisodeDetail(
        @JsonProperty("title") val title: String,
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("video_path") val video_path: String?
    )

    data class MovieDetail(
        @JsonProperty("title") val title: String,
        @JsonProperty("description") val description: String?,
        @JsonProperty("poster_url") val poster_url: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("movie_parts") val movie_parts: List<MoviePartDetail>?
    )

    data class MoviePartDetail(
        @JsonProperty("video_path") val video_path: String?
    )
}