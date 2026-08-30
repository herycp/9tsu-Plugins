package com.asiaflix.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class VideasyExtractor : ExtractorApi() {
    override val name = "Videasy"
    override val mainUrl = "https://player.videasy.net"
    override val requiresReferer = true

    private val tmdbApiKey = "15d292cd807f8844e60c97ca027b5fe8"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "https://player.videasy.to/",
        "Origin" to "https://player.videasy.to"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val isTv = url.contains("/tv/")
        val tmdbId = Regex("""/(?:tv|movie)/(\d+)""").find(url)?.groupValues?.get(1) ?: return
        val season = Regex("""/tv/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"
        val episode = Regex("""/tv/\d+/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"

        // 1. Ambil Metadata dari TMDB untuk title, year, dan imdb_id
        val tmdbUrl = if (isTv) {
            "https://api.themoviedb.org/3/tv/$tmdbId?api_key=$tmdbApiKey&append_to_response=external_ids"
        } else {
            "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&append_to_response=external_ids"
        }

        val tmdbText = app.get(tmdbUrl).text
        val tmdbJson = tryParseJson<TmdbMediaDetails>(tmdbText) ?: return
        val title = tmdbJson.title ?: tmdbJson.name ?: return
        val year = (tmdbJson.releaseDate ?: tmdbJson.firstAirDate)?.take(4) ?: ""
        val imdbId = tmdbJson.imdbId ?: tmdbJson.externalIds?.imdbId ?: ""

        // Double URL-encoding untuk judul (sesuai spesifikasi Videasy)
        val encTitle = URLEncoder.encode(URLEncoder.encode(title, "UTF-8"), "UTF-8")

        // 2. Ambil Seed Data
        val seedResponse = app.get("https://api.speedracelight.com/seed?mediaId=$tmdbId", headers = baseHeaders).text
        val seed = tryParseJson<SeedResponse>(seedResponse)?.seed ?: return

        // 3. Iterasi Server Utama (cdn, m4uhd, vsrc, hdmovie)
        val servers = listOf("cdn", "m4uhd", "vsrc", "hdmovie")
        val mediaType = if (isTv) "tv" else "movie"

        servers.forEach { server ->
            try {
                val apiUrl = if (isTv) {
                    "https://api.speedracelight.com/$server/sources-with-title?title=$encTitle&mediaType=$mediaType&year=$year&episodeId=$episode&seasonId=$season&tmdbId=$tmdbId&imdbId=$imdbId&enc=2&seed=$seed"
                } else {
                    "https://api.speedracelight.com/$server/sources-with-title?title=$encTitle&mediaType=$mediaType&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=2&seed=$seed"
                }

                val encData = app.get(apiUrl, headers = baseHeaders).text
                if (encData.isBlank() || encData.contains("error", ignoreCase = true)) return@forEach

                // 4. Dekripsi via Service API enc-dec.app
                val decResponseText = app.post(
                    "https://enc-dec.app/api/dec-videasy",
                    json = mapOf("text" to encData, "id" to tmdbId, "seed" to seed)
                ).text

                val decResult = tryParseJson<DecryptResponse>(decResponseText)
                if (decResult?.status == 200 && decResult.result != null) {
                    val resultJsonString = if (decResult.result is String) decResult.result else decResult.result.toJson()
                    val videasyData = tryParseJson<VideasyData>(resultJsonString)

                    // Subtitle Extraction
                    videasyData?.tracks?.forEach { track ->
                        val subUrl = track.file ?: track.url ?: return@forEach
                        val lang = track.label ?: track.lang ?: "Unknown"
                        subtitleCallback(SubtitleFile(lang, subUrl))
                    }

                    // Video Stream Extraction
                    videasyData?.sources?.forEach { source ->
                        val streamUrl = source.file ?: source.url ?: return@forEach
                        val quality = source.quality ?: source.label ?: "Auto"
                        val isM3u8 = source.type?.lowercase() == "hls" || streamUrl.contains(".m3u8")

                        callback(
                            newExtractorLink(
                                name,
                                "$name - ${server.uppercase()} ($quality)",
                                streamUrl,
                                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.headers = baseHeaders
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // Skip server yang gagal/timeout
            }
        }
    }

    private data class SeedResponse(val seed: String? = null)
    private data class DecryptResponse(val status: Int? = null, val result: Any? = null)
    private data class VideasyData(val sources: List<VideasySource>? = null, val tracks: List<VideasyTrack>? = null)
    private data class VideasySource(val file: String? = null, val url: String? = null, val type: String? = null, val quality: String? = null, val label: String? = null)
    private data class VideasyTrack(val file: String? = null, val url: String? = null, val label: String? = null, val kind: String? = null, val lang: String? = null)
    private data class TmdbMediaDetails(
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null
    )
    private data class ExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)
}