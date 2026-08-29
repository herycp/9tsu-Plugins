package com.asiaflix.extractors

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class PeachifyExtractor : ExtractorApi() {
    override val name = "Peachify"
    override val mainUrl = "https://peachify.top"
    override val requiresReferer = true

    private val peachifyServers = listOf(
        Pair("Air", "https://none.eat-peach.sbs/air"),
        Pair("Multi", "https://none.eat-peach.sbs/multi"),
        Pair("MovieBox", "https://none.eat-peach.sbs/moviebox"),
        Pair("Holly", "https://none.eat-peach.sbs/holly"),
        Pair("Net", "https://none.eat-peach.sbs/net"),
        Pair("Bmb", "https://none.eat-peach.sbs/bmb")
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("PeachifyDebug", "Target Input URL: $url")
        var mediaType = "movie"; var tmdbId = ""; var season = "1"; var episode = "1"

        if (url.contains("/tv/")) {
            mediaType = "tv"
            val parts = url.substringAfter("/tv/").substringBefore("?").split("/")
            tmdbId = parts.getOrNull(0) ?: ""; season = parts.getOrNull(1) ?: "1"; episode = parts.getOrNull(2) ?: "1"
        } else if (url.contains("/movie/")) {
            tmdbId = url.substringAfter("/movie/").substringBefore("?").substringBefore("/")
        }

        if (tmdbId.isEmpty()) return

        val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        val requestHeaders = mapOf(
            "User-Agent" to defaultUserAgent,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        val endpointPath = if (mediaType == "tv") "tv/$tmdbId/$season/$episode" else "movie/$tmdbId"

        coroutineScope {
            peachifyServers.map { (srvLabel, srvBaseUrl) ->
                async {
                    try {
                        val targetApiUrl = "$srvBaseUrl/$endpointPath"
                        Log.d("PeachifyDebug", "Fetching from API: $targetApiUrl")

                        val responseText = app.get(targetApiUrl, headers = requestHeaders, timeout = 10).text
                        val apiRes = tryParseJson<PeachifyApiResponse>(responseText)

                        // Parse Subtitles
                        for (sub in apiRes?.subtitles ?: emptyList()) {
                            val subUrl = sub.url ?: sub.file ?: sub.src ?: continue
                            val isInvalid = subUrl.endsWith(".jpg") || subUrl.endsWith(".png") || subUrl.endsWith(".m3u8") || subUrl.endsWith(".mp4")
                            if (subUrl.startsWith("http") && !isInvalid) {
                                subtitleCallback.invoke(
                                    newSubtitleFile(
                                        lang = sub.label ?: sub.name ?: sub.lang ?: "Auto",
                                        url = subUrl
                                    ) {
                                        this.headers = requestHeaders
                                    }
                                )
                            }
                        }

                        // Parse Sources dengan Header Dinamis dari JSON API
                        apiRes?.sources?.forEach { src ->
                            val streamUrl = src.url ?: src.src ?: src.file ?: return@forEach
                            if (streamUrl.startsWith("http")) {
                                val isM3u8 = src.type?.lowercase()?.contains("hls") == true || streamUrl.lowercase().contains(".m3u8")
                                val dubLabel = if (src.dub.isNullOrBlank()) "Original" else src.dub

                                // Ekstraksi header dinamis dari JSON (seperti origin & referer ke nextgencloudfabric.com)
                                val playHeaders = mutableMapOf("User-Agent" to defaultUserAgent)
                                src.headers?.forEach { (key, value) ->
                                    playHeaders[key] = value
                                }

                                val streamReferer = src.headers?.get("referer") 
                                    ?: src.headers?.get("Referer") 
                                    ?: "$mainUrl/"

                                Log.d("PeachifyDebug", "Found Stream ($srvLabel): $streamUrl | Referer: $streamReferer")

                                callback.invoke(
                                    newExtractorLink(
                                        name = "$name - $srvLabel ($dubLabel)",
                                        source = this@PeachifyExtractor.name,
                                        url = streamUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = streamReferer
                                        this.headers = playHeaders
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PeachifyDebug", "Error fetching $srvLabel: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }

    data class PeachifyApiResponse(
        @JsonProperty("providerName") val providerName: String? = null,
        @JsonProperty("sources") val sources: List<PeachifySource>? = null,
        @JsonProperty("subtitles") val subtitles: List<PeachifySubtitle>? = null
    )

    data class PeachifySource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("dub") val dub: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null
    )

    data class PeachifySubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )
}
