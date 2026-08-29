package com.asiaflix.extractors

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class VideasyExtractor : ExtractorApi() {
    override val name = "Videasy"
    override val mainUrl = "https://player.videasy.net"
    override val requiresReferer = true

    private val videasyServers = listOf(
        Pair("mb-flix", "https://api.videasy.net/mb-flix/sources-with-title"),
        Pair("1movies", "https://api.videasy.net/1movies/sources-with-title"),
        Pair("cdn", "https://api.videasy.net/cdn/sources-with-title"),
        Pair("superflix", "https://api.videasy.net/superflix/sources-with-title"),
        Pair("lamovie", "https://api.videasy.net/lamovie/sources-with-title")
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("VideasyDebug", "Target URL: $url")
        var mediaType = "movie"; var tmdbId = ""; var season = "1"; var episode = "1"
        
        if (url.contains("/tv/")) {
            mediaType = "tv"
            val parts = url.substringAfter("/tv/").substringBefore("?").split("/")
            tmdbId = parts.getOrNull(0) ?: ""; season = parts.getOrNull(1) ?: "1"; episode = parts.getOrNull(2) ?: "1"
        } else if (url.contains("/movie/")) {
            tmdbId = url.substringAfter("/movie/").substringBefore("?").substringBefore("/")
        }
        if (tmdbId.isEmpty()) return

        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Referer" to "$mainUrl/", "Origin" to mainUrl)

        coroutineScope {
            videasyServers.map { (srvName, srvUrl) ->
                async {
                    // Gunakan try-catch di dalam async agar pembatalan satu server tidak mematikan server lain
                    try {
                        val qParams = "title=&mediaType=$mediaType&tmdbId=$tmdbId&imdbId=&episodeId=${if(mediaType=="tv") episode else "1"}&seasonId=${if(mediaType=="tv") season else "1"}&language=english"
                        val targetServer = "$srvUrl?$qParams"
                        
                        val res = app.get(targetServer, headers = headers, timeout = 8)
                        if (res.code == 200) {
                            val blobText = res.text
                            if (blobText.length >= 10) {
                                val decRes = tryParseJson<DecResponse>(app.post("https://enc-dec.app/api/dec-videasy", json = mapOf("text" to blobText, "id" to tmdbId)).text)
                                
                                if (decRes?.status == 200 && decRes.result != null) {
                                    decRes.result.subtitles?.forEach { sub ->
                                        val subUrl = sub.url ?: return@forEach
                                        val isInvalid = subUrl.endsWith(".jpg") || subUrl.endsWith(".png") || subUrl.endsWith(".m3u8") || subUrl.endsWith(".mp4")
                                        if (subUrl.startsWith("http") && !isInvalid) {
                                            subtitleCallback.invoke(SubtitleFile(sub.lang ?: sub.language ?: "Auto", subUrl))
                                        }
                                    }
                                    decRes.result.sources?.forEach { src ->
                                        src.url?.let { streamUrl ->
                                            val isM3u8 = src.type?.lowercase()?.contains("hls") == true || streamUrl.lowercase().contains(".m3u8")
                                            callback.invoke(
                                                newExtractorLink(
                                                    name = "$name - $srvName",
                                                    source = this@VideasyExtractor.name,
                                                    url = streamUrl,
                                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = "$mainUrl/"
                                                    this.headers = headers
                                                    this.quality = Qualities.Unknown.value
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VideasyDebug", "Skipped server $srvName due to error: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }

    data class DecResponse(@JsonProperty("status") val status: Int?, @JsonProperty("result") val result: DecResult?)
    data class DecResult(@JsonProperty("sources") val sources: List<Source>?, @JsonProperty("subtitles") val subtitles: List<Subtitle>?)
    data class Source(@JsonProperty("url") val url: String?, @JsonProperty("type") val type: String?)
    data class Subtitle(@JsonProperty("url") val url: String?, @JsonProperty("language") val language: String?, @JsonProperty("lang") val lang: String?)
}