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

class CinezoExtractor : ExtractorApi() {
    override val name = "Cinezo"
    override val mainUrl = "https://player.cinezo.live"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("CinezoDebug", "Target Input URL: $url")
        var mediaType = "movie"
        var tmdbId = ""
        var season = "1"
        var episode = "1"

        // Evaluasi 1: Regex & Parsing yang lebih aman
        if (url.contains("id=")) {
            tmdbId = url.substringAfter("id=").substringBefore("&")
            if (url.contains("/tv") || url.contains("season=")) mediaType = "tv"
            if (url.contains("season=")) season = url.substringAfter("season=").substringBefore("&")
            if (url.contains("episode=")) episode = url.substringAfter("episode=").substringBefore("&")
        } else if (url.contains("/tv/")) {
            mediaType = "tv"
            val parts = url.substringAfter("/tv/").substringBefore("?").split("/")
            tmdbId = parts.getOrNull(0) ?: ""
            season = parts.getOrNull(1) ?: "1"
            episode = parts.getOrNull(2) ?: "1"
        } else if (url.contains("/movie/")) {
            tmdbId = url.substringAfter("/movie/").substringBefore("?").substringBefore("/")
        } else {
            tmdbId = url.trim()
        }

        if (tmdbId.isEmpty()) return

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val baseHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        val apiUrl = if (mediaType == "tv") {
            "https://proxy1.flikhub.net/tv?id=$tmdbId&season=$season&episode=$episode"
        } else {
            "https://proxy1.flikhub.net/movie?id=$tmdbId"
        }

        val responseText = try {
            // Evaluasi 2: Timeout disesuaikan karena API merespons dengan pola Stream (SSE)
            app.get(apiUrl, headers = baseHeaders, timeout = 15).text
        } catch (e: Exception) {
            Log.e("CinezoDebug", "Error fetching API: ${e.message}")
            return
        }

        val lines = responseText.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            
            val jsonStr = trimmed.removePrefix("data:").trim()
            if (jsonStr.isEmpty()) continue

            // Evaluasi 3: Filter Subtitle yang sangat selektif
            if (jsonStr.contains("\"type\":\"meta\"")) {
                val metaData = tryParseJson<CinezoMetaEvent>(jsonStr)
                for (sub in metaData?.subtitles ?: emptyList()) {
                    val subUrl = sub.file ?: continue
                    // Memastikan hanya VTT/SRT HTTP yang lolos (mengabaikan base64 "Vylian")
                    if (subUrl.startsWith("http") && (subUrl.contains(".vtt") || subUrl.contains(".srt"))) {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                lang = sub.label ?: "Auto",
                                url = subUrl
                            )
                        )
                    }
                }
            }

            // Evaluasi 4: Deteksi Tipe Stream Hybrid (JSON Parameter + URL String)
            if (jsonStr.contains("\"type\":\"source\"")) {
                val sourceData = tryParseJson<CinezoSourceEvent>(jsonStr)
                val srcObj = sourceData?.source ?: continue
                val streamUrl = srcObj.url ?: continue

                if (streamUrl.startsWith("http")) {
                    val rawType = srcObj.type?.lowercase() ?: ""
                    val lowerUrl = streamUrl.lowercase()

                    val isDash = rawType == "dash" || lowerUrl.contains(".mpd") || lowerUrl.contains("manifest.mpd")
                    val isM3u8 = rawType == "hls" || rawType == "m3u8" || lowerUrl.contains(".m3u8") || lowerUrl.contains("master.m3u8")

                    val linkType = when {
                        isDash -> ExtractorLinkType.DASH
                        isM3u8 -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }

                    val serverLabel = srcObj.label ?: srcObj.source ?: "Server"

                    callback.invoke(
                        newExtractorLink(
                            name = "$name - $serverLabel",
                            source = this@CinezoExtractor.name,
                            url = streamUrl,
                            type = linkType
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = baseHeaders
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
    }

    data class CinezoMetaEvent(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("subtitles") val subtitles: List<CinezoSubtitle>? = null
    )

    data class CinezoSubtitle(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class CinezoSourceEvent(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("source") val source: CinezoSourceItem? = null
    )

    data class CinezoSourceItem(
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}