package com.gomio

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class VidomonOkruExtractor : ExtractorApi() {
    override val name: String = "Ok.ru (Vidomon Backup)"
    override val mainUrl: String = "https://vidomon.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://vidomon.com/wp-json/aio-dl/video-data/"

        // Membatasi eksekusi API maksimal 1 menit (60.000 milidetik)
        val response = withTimeoutOrNull(60_000L) {
            app.post(
                apiUrl,
                data = mapOf("url" to url),
                timeout = 60,
                timeoutUnit = TimeUnit.SECONDS
            ).parsedSafe<VidomonResponse>()
        }

        // Jika response timeout/null, eksekusi dihentikan tanpa membekukan aplikasi
        response?.medias?.forEach { media ->
            if (media.videoAvailable == true && !media.url.isNullOrEmpty()) {
                // Pemetaan kualitas video
                val quality = when (media.quality?.lowercase()) {
                    "hd" -> Qualities.P720.value
                    "sd" -> Qualities.P480.value
                    "low" -> Qualities.P360.value
                    "lowest" -> 240
                    "mobile" -> 144
                    else -> Qualities.Unknown.value
                }

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = media.url,
                        referer = mainUrl,
                        quality = quality,
                        isM3u8 = false
                    )
                )
            }
        }
    }

    // Data Class untuk parsing JSON response Vidomon
    data class VidomonResponse(
        @JsonProperty("medias") val medias: List<Media>? = null
    )

    data class Media(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("videoAvailable") val videoAvailable: Boolean? = null
    )
}