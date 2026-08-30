package com.asiaflix.extractors

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink 

class VideasyExtractor : ExtractorApi() {
    override val name = "Videasy"
    override val mainUrl = "https://player.videasy.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("VideasyDebug: 1. Extractor MULAI terpanggil untuk URL: $url")
        Log.d("VideasyDebug", "1. Extractor MULAI terpanggil untuk URL: $url")

        // --- 0. PARSING PARAMETER DARI URL INPUT ---
        val isTv = url.contains("/tv/")
        val regex = Regex("""/(tv|movie)/(\d+)(?:/(\d+)/(\d+))?""")
        val matchResult = regex.find(url)

        if (matchResult == null) {
            Log.e("VideasyDebug", "GAGAL: Gagal melakukan parse Regex pada URL input.")
            return
        }

        val tmdbId = matchResult.groupValues[2]
        val season = matchResult.groupValues.getOrNull(3)?.toIntOrNull() ?: 1
        val episode = matchResult.groupValues.getOrNull(4)?.toIntOrNull() ?: 1

        println("VideasyDebug: 2. Data Regex -> TMDB: $tmdbId, isTv: $isTv")

        // --- 1. AMBIL METADATA API SPEEDRACELIGHT ---
        var apiUrl = if (isTv) {
            "https://db.speedracelight.com/3/tv/$tmdbId?append_to_response=external_ids&language=en"
        } else {
            "https://db.speedracelight.com/3/movie/$tmdbId?append_to_response=external_ids&language=en"
        }

        val apiText = try {
            app.get(apiUrl).text
        } catch (e: Exception) {
            Log.e("VideasyDebug", "GAGAL API Tahap 1: ${e.message}")
            ""
        }

        var apiJson = tryParseJson<TmdbMediaDetails>(apiText)
        var title = apiJson?.name ?: apiJson?.title

        // Fallback jika API mengembalikan data kosong (salah kategori)
        if (title.isNullOrBlank()) {
            println("VideasyDebug: Peringatan: Judul kosong. Mencoba kategori alternatif...")
            apiUrl = if (!isTv) {
                "https://db.speedracelight.com/3/tv/$tmdbId?append_to_response=external_ids&language=en"
            } else {
                "https://db.speedracelight.com/3/movie/$tmdbId?append_to_response=external_ids&language=en"
            }
            
            val apiTextAlt = try { app.get(apiUrl).text } catch (e: Exception) { "" }
            apiJson = tryParseJson<TmdbMediaDetails>(apiTextAlt)
            title = apiJson?.name ?: apiJson?.title
        }

        if (title.isNullOrBlank()) {
            Log.e("VideasyDebug", "GAGAL FATAL: Judul tetap kosong. Script berhenti.")
            return
        }

        val year = (apiJson?.firstAirDate ?: apiJson?.releaseDate)?.take(4) ?: ""
        val imdbId = apiJson?.externalIds?.imdbId ?: ""

        println("VideasyDebug: 4. Meta Sukses -> Title: $title, Year: $year, IMDB: $imdbId")

        // --- 2. PROSES EXTRACTION / M3U8 GENERATION ---
        try {
            // [GANTI URL INI DENGAN LOGIKA ENKRIPSI/API SERVER ANDA]
            val streamUrl = "https://example-stream-provider.com/hls/$tmdbId.m3u8"

            if (streamUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = streamUrl,
                    referer = mainUrl
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            println("VideasyDebug: 5. Berhasil mengirim ExtractorLink ke Cloudstream Player.")
        } catch (e: Exception) {
            Log.e("VideasyDebug", "GAGAL Generate Stream: ${e.message}")
        }
    }
}

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