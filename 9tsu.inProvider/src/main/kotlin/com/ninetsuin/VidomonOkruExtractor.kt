package com.ninetsuin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.withTimeoutOrNull

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

        // Timeout coroutine 1 menit (60.000 ms)
        val response = withTimeoutOrNull(60_000L) {
            app.post(
                apiUrl,
                data = mapOf("url" to url),
                timeout = 60L
            ).parsedSafe<VidomonResponse>()
        }

        response?.medias?.forEach { media ->
            if (media.videoAvailable == true && !media.url.isNullOrEmpty()) {
                val quality = when (media.quality?.lowercase()) {
                    "hd" -> Qualities.P720.value
                    "sd" -> Qualities.P480.value
                    "low" -> Qualities.P360.value
                    "lowest" -> 240
                    "mobile" -> 144
                    else -> Qualities.Unknown.value
                }

                // Menggunakan newExtractorLink untuk menggantikan constructor ExtractorLink yang deprecated
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = media.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
            }
        }
    }

    data class VidomonResponse(
        @JsonProperty("medias") val medias: List<Media>? = null
    )

    data class Media(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("videoAvailable") val videoAvailable: Boolean? = null
    )
}
