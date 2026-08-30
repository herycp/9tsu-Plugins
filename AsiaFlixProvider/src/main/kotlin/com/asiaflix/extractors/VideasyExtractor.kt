package com.asiaflix.extractors

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder

class VideasyExtractor : ExtractorApi() {
    override val name = "Videasy"
    override val mainUrl = "https://player.videasy.to"
    override val requiresReferer = true

    // --- DATA CLASSES ---
    data class TmdbMediaDetails(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null
    )

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    data class SeedResponse(@JsonProperty("seed") val seed: String?)

    data class DecryptedResult(
        @JsonProperty("status") val status: Int?,
        @JsonProperty("error") val error: String?,
        @JsonProperty("result") val result: DecryptedSources?
    )

    data class DecryptedSources(
        @JsonProperty("sources") val sources: List<SourceItem>?,
        @JsonProperty("tracks") val tracks: List<TrackItem>?
    )

    data class SourceItem(
        @JsonProperty("file") val file: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("quality") val quality: String?
    )

    data class TrackItem(
        @JsonProperty("file") val file: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    // Helper untuk Double URL-Encoding spasi ke %20
    private fun encodeUri(str: String): String {
        return URLEncoder.encode(str, "UTF-8").replace("+", "%20")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("VideasyDebug: 1. Extractor MULAI terpanggil untuk URL: $url")
        Log.d("VideasyDebug", "1. Extractor MULAI terpanggil untuk URL: $url")

        // --- 0. PARSING URL PARAMETERS ---
        val regex = Regex(".*/(tv|movie)/(\\d+)(?:/(\\d+)/(\\d+))?")
        val match = regex.find(url) ?: return

        val type = match.groupValues[1]
        val isTv = type == "tv"
        val tmdbId = match.groupValues[2]
        val season = if (isTv) match.groupValues[3] else "1"
        val episode = if (isTv) match.groupValues[4] else "1"

        Log.d("VideasyDebug", "2. Data Regex -> TMDB: $tmdbId, isTv: $isTv, S: $season, E: $episode")

        // --- 1. AMBIL METADATA (TMDB / SPEEDRACELIGHT DB) ---
        var tmdbApiUrl = if (isTv) {
            "https://db.speedracelight.com/3/tv/$tmdbId?append_to_response=external_ids&language=en"
        } else {
            "https://db.speedracelight.com/3/movie/$tmdbId?append_to_response=external_ids&language=en"
        }

        var apiText = try { app.get(tmdbApiUrl).text } catch (e: Exception) { "" }
        var apiJson = tryParseJson<TmdbMediaDetails>(apiText)
        var title = apiJson?.name ?: apiJson?.title

        // Fallback kategori silang jika kosong
        if (title.isNullOrBlank()) {
            tmdbApiUrl = if (!isTv) {
                "https://db.speedracelight.com/3/tv/$tmdbId?append_to_response=external_ids&language=en"
            } else {
                "https://db.speedracelight.com/3/movie/$tmdbId?append_to_response=external_ids&language=en"
            }
            apiText = try { app.get(tmdbApiUrl).text } catch (e: Exception) { "" }
            apiJson = tryParseJson<TmdbMediaDetails>(apiText)
            title = apiJson?.name ?: apiJson?.title
        }

        if (title.isNullOrBlank()) {
            Log.e("VideasyDebug", "GAGAL FATAL: Metadata judul tidak ditemukan.")
            return
        }

        val year = (apiJson?.firstAirDate ?: apiJson?.releaseDate)?.take(4) ?: ""
        val imdbId = apiJson?.externalIds?.imdbId ?: ""

        Log.d("VideasyDebug", "4. Meta Sukses -> Title: $title, Year: $year, IMDB: $imdbId")

        // --- 2. SETUP HEADERS & API ENDPOINTS ---
        val reqHeaders = mapOf(
            "Accept" to "*/*",
            "Origin" to "https://player.videasy.to",
            "Referer" to "https://player.videasy.to/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        )

        val speedraceApiUrl = "https://api.speedracelight.com"
        val decApi = "https://enc-dec.app/api/dec-videasy"

        // Double encode title
        val encTitle = encodeUri(encodeUri(title))

        // --- 3. AMBIL SEED ---
        val seedUrl = "$speedraceApiUrl/seed?mediaId=$tmdbId"
        val seedResponse = try {
            app.get(seedUrl, headers = reqHeaders).parsedSafe<SeedResponse>()
        } catch (e: Exception) {
            Log.e("VideasyDebug", "GAGAL Get Seed: ${e.message}")
            null
        }

        val seed = seedResponse?.seed
        if (seed == null) {
            Log.e("VideasyDebug", "GAGAL: Seed kosong atau tidak ditemukan")
            return
        }

        // --- 4. LIST SEMUA SERVER VIDEASY ---
        val servers = listOf(
            "cdn" to "Yoru",
            "m4uhd" to "Breach",
            "vsrc" to "Neon",
            "hdmovie" to "Vyse",
            "meine" to "Killjoy",
            "lamovie" to "Omen",
            "superflix" to "Raze"
        )

        val enc = "2"

        // --- 5. ITERASI SEMUA SERVER & KUMPULKAN URL ---
        for ((serverEndpoint, serverName) in servers) {
            try {
                var sourceUrl = "$speedraceApiUrl/$serverEndpoint/sources-with-title?title=$encTitle&mediaType=$type&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=$enc&seed=$seed"
                if (isTv) {
                    sourceUrl += "&seasonId=$season&episodeId=$episode"
                }

                Log.d("VideasyDebug", "Memproses server $serverName ($serverEndpoint)...")
                val encData = app.get(sourceUrl, headers = reqHeaders).text
                if (encData.isBlank()) continue

                val decPayload = mapOf(
                    "text" to encData,
                    "id" to tmdbId,
                    "seed" to seed
                )

                val decryptedResponse = app.post(
                    decApi,
                    headers = mapOf("Content-Type" to "application/json"),
                    json = decPayload
                ).parsedSafe<DecryptedResult>()

                if (decryptedResponse?.status == 200 && decryptedResponse.result != null) {
                    val resultData = decryptedResponse.result

                    // Ekstrak Video dari Server Ini
                    resultData.sources?.forEach { source ->
                        val videoUrl = source.url ?: source.file
                        if (!videoUrl.isNullOrEmpty()) {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "$name - $serverName",
                                    url = videoUrl,
                                    referer = "https://player.videasy.to/",
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = videoUrl.contains(".m3u8")
                                )
                            )
                        }
                    }

                    // Ekstrak Subtitle
                    resultData.tracks?.forEach { track ->
                        val trackUrl = track.url ?: track.file
                        if (!trackUrl.isNullOrEmpty() && (track.kind == "captions" || track.kind == "subtitles")) {
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    lang = track.label ?: "Unknown",
                                    url = trackUrl
                                )
                            )
                        }
                    }
                    Log.d("VideasyDebug", "BERHASIL: Menambahkan link dari server $serverName")
                }
            } catch (e: Exception) {
                Log.w("VideasyDebug", "Satu server gagal ($serverName), melanjut ke server berikutnya: ${e.message}")
            }
        }
    }
}