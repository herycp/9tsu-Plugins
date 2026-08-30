package com.asiaflix.extractors

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class VideasyExtractor : ExtractorApi() {
    override val name = "Videasy"
    override val mainUrl = "https://player.videasy.to"
    override val requiresReferer = true

    private val tmdbApiKey = "15d292cd807f8844e60c97ca027b5fe8"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "https://player.videasy.to/",
        "Origin" to "https://player.videasy.to",
        "Accept" to "*/*"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // [LOG 1] Memastikan Extractor benar-benar terpanggil
        Log.d("VideasyDebug", "1. Extractor MULA terpanggil untuk URL: $url")

        val isTv = url.contains("/tv/")
        val tmdbIdMatch = Regex("""/(?:tv|movie)/(\d+)""").find(url)
        
        if (tmdbIdMatch == null) {
            Log.e("VideasyDebug", "GAGAL: Tidak dapat menemukan TMDB ID dari URL.")
            return
        }
        val tmdbId = tmdbIdMatch.groupValues[1]
        val season = Regex("""/tv/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"
        val episode = Regex("""/tv/\d+/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"

        Log.d("VideasyDebug", "2. Data Regex didapat -> TMDB: $tmdbId, isTv: $isTv, S: $season, E: $episode")

        // --- 1. AMBIL METADATA TMDB ---
        val tmdbUrl = if (isTv) {
            "https://api.themoviedb.org/3/tv/$tmdbId?api_key=$tmdbApiKey&append_to_response=external_ids"
        } else {
            "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&append_to_response=external_ids"
        }

        Log.d("VideasyDebug", "3. Mengambil TMDB API: $tmdbUrl")
        val tmdbText = try {
            app.get(tmdbUrl).text
        } catch (e: Exception) {
            Log.e("VideasyDebug", "GAGAL: Jaringan TMDB Timeout/Ditolak: ${e.message}")
            return
        }

        val tmdbJson = tryParseJson<TmdbMediaDetails>(tmdbText)
        if (tmdbJson == null) {
            Log.e("VideasyDebug", "GAGAL: Format JSON TMDB tidak dikenali. Raw: $tmdbText")
            return
        }

        val title = tmdbJson.title ?: tmdbJson.name
        if (title.isNullOrBlank()) {
            Log.e("VideasyDebug", "GAGAL: Judul TMDB kosong/null.")
            return
        }

        val year = (tmdbJson.releaseDate ?: tmdbJson.firstAirDate)?.take(4) ?: ""
        val imdbId = tmdbJson.imdbId ?: tmdbJson.externalIds?.imdbId ?: ""
        Log.d("VideasyDebug", "4. Meta TMDB Sukses -> Title: $title, Year: $year, IMDB: $imdbId")

        val encTitle = URLEncoder.encode(URLEncoder.encode(title, "UTF-8"), "UTF-8")

        // --- 2. AMBIL SEED DATA ---
        val seedUrl = "https://api.speedracelight.com/seed?mediaId=$tmdbId"
        Log.d("VideasyDebug", "5. Mengambil Seed URL: $seedUrl")
        
        val seedResponseText = try {
            app.get(seedUrl, headers = baseHeaders).text
        } catch (e: Exception) {
            Log.e("VideasyDebug", "GAGAL: Jaringan Seed API Timeout/Error: ${e.message}")
            return
        }

        val seedJson = tryParseJson<SeedResponse>(seedResponseText)
        val seed = seedJson?.seed
        if (seed.isNullOrBlank()) {
            Log.e("VideasyDebug", "GAGAL: Token Seed tidak ditemukan. Raw Response: $seedResponseText")
            return
        }
        Log.d("VideasyDebug", "6. Token Seed Sukses: $seed")

        // --- 3. ITERASI SERVER ---
        val servers = listOf("cdn", "m4uhd", "vsrc", "hdmovie")
        val mediaType = if (isTv) "tv" else "movie"

        servers.forEach { server ->
            try {
                val apiUrl = if (isTv) {
                    "https://api.speedracelight.com/$server/sources-with-title?title=$encTitle&mediaType=$mediaType&year=$year&episodeId=$episode&seasonId=$season&tmdbId=$tmdbId&imdbId=$imdbId&enc=2&seed=$seed"
                } else {
                    "https://api.speedracelight.com/$server/sources-with-title?title=$encTitle&mediaType=$mediaType&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=2&seed=$seed"
                }

                Log.d("VideasyDebug", "7. [$server] Request Enkripsi: $apiUrl")
                val encData = app.get(apiUrl, headers = baseHeaders).text
                
                if (encData.isBlank() || encData.contains("error", ignoreCase = true)) {
                    Log.w("VideasyDebug", "SKIP [$server]: Data Enkripsi kosong atau Error.")
                    return@forEach // Lanjut ke server berikutnya
                }
                Log.d("VideasyDebug", "8. [$server] Data Enkripsi Berhasil Diambil (Panjang: ${encData.length})")

                // --- 4. DEKRIPSI ---
                val decApiUrl = "https://enc-dec.app/api/dec-videasy"
                Log.d("VideasyDebug", "9. [$server] Proses Dekripsi ke: $decApiUrl")
                
                val decResponseText = app.post(
                    decApiUrl,
                    json = mapOf("text" to encData, "id" to tmdbId, "seed" to seed)
                ).text

                val decResult = tryParseJson<DecryptResponse>(decResponseText)
                if (decResult?.status == 200 && decResult.result != null) {
                    val resultJsonString = if (decResult.result is String) decResult.result else decResult.result.toJson()
                    Log.d("VideasyDebug", "10. [$server] Dekripsi Sukses: $resultJsonString")
                    
                    val videasyData = tryParseJson<VideasyData>(resultJsonString)

                    videasyData?.tracks?.forEach { track ->
                        val subUrl = track.file ?: track.url ?: return@forEach
                        val lang = track.label ?: track.lang ?: "Unknown"
                        Log.d("VideasyDebug", "=> Subtitle Ditemukan: $lang ($subUrl)")
                        subtitleCallback(SubtitleFile(lang, subUrl))
                    }

                    videasyData?.sources?.forEach { source ->
                        val streamUrl = source.file ?: source.url ?: return@forEach
                        val quality = source.quality ?: source.label ?: "Auto"
                        val isM3u8 = source.type?.lowercase() == "hls" || streamUrl.contains(".m3u8")
                        
                        Log.d("VideasyDebug", "=> Stream Ditemukan: $quality ($streamUrl)")
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
                } else {
                    Log.e("VideasyDebug", "GAGAL [$server]: Status Dekripsi bukan 200. Raw: $decResponseText")
                }
            } catch (e: Exception) {
                Log.e("VideasyDebug", "GAGAL [$server]: Terjadi Exception - ${e.message}", e)
            }
        }
    }

    // Wajib menambahkan @JsonIgnoreProperties agar Parser tidak crash jika server API menambahkan parameter baru diam-diam
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SeedResponse(val seed: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DecryptResponse(val status: Int? = null, val result: Any? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class VideasyData(val sources: List<VideasySource>? = null, val tracks: List<VideasyTrack>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class VideasySource(val file: String? = null, val url: String? = null, val type: String? = null, val quality: String? = null, val label: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class VideasyTrack(val file: String? = null, val url: String? = null, val label: String? = null, val kind: String? = null, val lang: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TmdbMediaDetails(
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)
}