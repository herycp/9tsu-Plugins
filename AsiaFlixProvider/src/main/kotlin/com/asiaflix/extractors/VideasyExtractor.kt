package com.asiaflix.extractors

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.M3u8Helper

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
        Log.d("VideasyDebug", "1. Extractor MULA terpanggil untuk URL: $url")

        // --- 0. PARSING PARAMETER DARI URL INPUT ---
        // Contoh URL: https://player.videasy.to/tv/253960/1/1?nextEpisode=false
        // atau: https://player.videasy.to/movie/253960
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

        Log.d("VideasyDebug", "2. Data Regex didapat -> TMDB: $tmdbId, isTv: $isTv, S: $season, E: $episode")

        // --- 1. AMBIL METADATA DARI SPEEDRACELIGHT ---
        var apiUrl = if (isTv) {
            "https://db.speedracelight.com/3/tv/$tmdbId?append_to_response=external_ids&language=en"
        } else {
            "https://db.speedracelight.com/3/movie/$tmdbId?append_to_response=external_ids&language=en"
        }

        Log.d("VideasyDebug", "3. Mengambil API: $apiUrl")

        var apiText = try {
            app.get(apiUrl).text
        } catch (e: Exception) {
            Log.e("VideasyDebug", "GAGAL: Jaringan Timeout/Ditolak: ${e.message}")
            return
        }

        var apiJson = tryParseJson<TmdbMediaDetails>(apiText)
        var title = apiJson?.name ?: apiJson?.title

        // Fallback Cross-Category jika kategori pertama gagal/kosong
        if (title.isNullOrBlank()) {
            Log.w("VideasyDebug", "Peringatan: Judul kosong. Mencoba kategori alternatif...")
            apiUrl = if (!isTv) {
                "https://db.speedracelight.com/3/tv/$tmdbId?append_to_response=external_ids&language=en"
            } else {
                "https://db.speedracelight.com/3/movie/$tmdbId?append_to_response=external_ids&language=en"
            }

            apiText = try {
                app.get(apiUrl).text
            } catch (e: Exception) {
                ""
            }
            apiJson = tryParseJson<TmdbMediaDetails>(apiText)
            title = apiJson?.name ?: apiJson?.title
        }

        if (title.isNullOrBlank()) {
            Log.e("VideasyDebug", "GAGAL FATAL: Judul tetap kosong. ID $tmdbId tidak ditemukan di metadata.")
            return
        }

        val year = (apiJson?.firstAirDate ?: apiJson?.releaseDate)?.take(4) ?: ""
        val imdbId = apiJson?.externalIds?.imdbId ?: ""

        Log.d("VideasyDebug", "4. Meta Sukses -> Title: $title, Year: $year, IMDB: $imdbId")

        // --- 2. PROSES EXTRACTION / STREAM SEARCHING ---
        // Masukkan enkripsi / API fetch server Videasy Anda di bawah ini
        try {
            // Contoh implementasi pemanggilan endpoint stream Videasy
            // (Sesuaikan dengan endpoint backend/encrypter stream yang Anda miliki)
            val streamUrl = "https://example-stream-provider.com/hls/$tmdbId.m3u8" // Ganti dengan logika eksekusi Videasy Anda

            if (streamUrl.endsWith(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name = name,
                    streamUrl = streamUrl,
                    referer = mainUrl
                ).forEach(callback)
            } else {
                callback.invoke(
                    ExtractorLink(
                        name = name,
                        source = name,
                        url = streamUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value
                    )
                )
            }

            Log.d("VideasyDebug", "5. Berhasil mengirim ExtractorLink ke Cloudstream Player.")
        } catch (e: Exception) {
            Log.e("VideasyDebug", "GAGAL Ekstraksi Stream Videasy: ${e.message}")
        }
    }
}

// --- DATA CLASSES UNTUK PARSING METADATA SPEEDRACELIGHT ---
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