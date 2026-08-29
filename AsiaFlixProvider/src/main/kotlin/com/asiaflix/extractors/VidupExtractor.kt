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
import com.lagradost.cloudstream3.utils.newSubtitleFile

class VidupExtractor : ExtractorApi() {
    override val name = "Vidup"
    override val mainUrl = "https://vidup.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("VidupDebug", "Target URL: $url")
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        
        val baseHeaders = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Origin" to mainUrl)
        
        val pageRes = app.get(url, headers = baseHeaders).text
        val tokenRegex = """\\"(?:en|token)\\":\\"(.*?)\\"""".toRegex()
        val tokenMatch = tokenRegex.find(pageRes) ?: """["'](?:en|token)["']\s*:\s*["']([^"']+)["']""".toRegex().find(pageRes)
        val tokenText = tokenMatch?.groupValues?.get(1)
        
        if (tokenText == null) {
            Log.e("VidupDebug", "Token 'en' tidak ditemukan di HTML")
            return
        }

        val encRes = app.get("https://enc-dec.app/api/enc-vidup?text=$tokenText").parsedSafe<EncResponse>()
        if (encRes?.status != 200 || encRes.result == null) {
            Log.e("VidupDebug", "API Enc-Dec App Gagal (Enc)")
            return
        }
        
        val headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Origin" to mainUrl, "X-Requested-With" to "XMLHttpRequest", "X-CSRF-Token" to (encRes.result.token ?: ""))
        val serversEncrypted = app.post(encRes.result.servers ?: return, headers = headers).text

        val decApiUrl = "https://enc-dec.app/api/dec-vidup"
        val decServersRes = app.post(decApiUrl, json = mapOf("text" to serversEncrypted)).parsedSafe<DecServersResponse>()
        if (decServersRes?.status != 200 || decServersRes.result == null) {
            Log.e("VidupDebug", "API Enc-Dec App Gagal (Dec Servers)")
            return
        }

        decServersRes.result.forEach { srv ->
            val srvData = srv.data ?: return@forEach
            Log.d("VidupDebug", "Processing Server: ${srv.name}")
            
            val streamEncrypted = app.post("${encRes.result.stream}/$srvData", headers = headers).text
            val decStreamRes = app.post(decApiUrl, json = mapOf("text" to streamEncrypted))

            if (decStreamRes.code == 200) {
                val streamObj = tryParseJson<DecStreamObjectResponse>(decStreamRes.text)
                val streamStr = tryParseJson<DecStreamStringResponse>(decStreamRes.text)

                var m3u8Url: String? = null
                var subtitlesList: List<VidupSubtitle>? = null

                if (streamObj?.status == 200 && streamObj.result != null) {
                    m3u8Url = streamObj.result.url ?: streamObj.result.file ?: streamObj.result.stream
                    subtitlesList = streamObj.result.subtitles ?: streamObj.result.tracks ?: streamObj.result.captions
                } else if (streamStr?.status == 200 && streamStr.result != null) {
                    if (streamStr.result.startsWith("{")) {
                        val parsed = tryParseJson<VidupStreamData>(streamStr.result)
                        m3u8Url = parsed?.url ?: parsed?.file ?: parsed?.stream
                        subtitlesList = parsed?.subtitles ?: parsed?.tracks ?: parsed?.captions
                    } else m3u8Url = streamStr.result
                }

                subtitlesList?.forEach { sub ->
                    val subUrl = sub.file ?: sub.url ?: return@forEach
                    val isInvalid = subUrl.endsWith(".jpg") || subUrl.endsWith(".png") || subUrl.endsWith(".m3u8") || subUrl.endsWith(".mp4")
                    if (subUrl.startsWith("http") && !isInvalid) {
                        // Menggunakan DSL newSubtitleFile terbaru
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                lang = sub.label ?: sub.lang ?: sub.language ?: "Auto",
                                url = subUrl
                            ) {
                                this.headers = baseHeaders
                            }
                        )
                    }
                }

                if (!m3u8Url.isNullOrEmpty()) {
                    Log.d("VidupDebug", "Found Stream: $m3u8Url")
                    callback.invoke(
                        newExtractorLink(
                            name = "$name - ${srv.name ?: name}",
                            source = this@VidupExtractor.name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
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

    data class EncResponse(@JsonProperty("status") val status: Int?, @JsonProperty("result") val result: EncResult?)
    data class EncResult(@JsonProperty("servers") val servers: String?, @JsonProperty("stream") val stream: String?, @JsonProperty("token") val token: String?)
    data class DecServersResponse(@JsonProperty("status") val status: Int?, @JsonProperty("result") val result: List<VidupServerItem>?)
    data class VidupServerItem(@JsonProperty("name") val name: String?, @JsonProperty("data") val data: String?)
    data class DecStreamStringResponse(@JsonProperty("status") val status: Int?, @JsonProperty("result") val result: String?)
    data class DecStreamObjectResponse(@JsonProperty("status") val status: Int?, @JsonProperty("result") val result: VidupStreamData?)
    data class VidupStreamData(@JsonProperty("url") val url: String?, @JsonProperty("file") val file: String?, @JsonProperty("stream") val stream: String?, @JsonProperty("subtitles") val subtitles: List<VidupSubtitle>?, @JsonProperty("tracks") val tracks: List<VidupSubtitle>?, @JsonProperty("captions") val captions: List<VidupSubtitle>?)
    data class VidupSubtitle(@JsonProperty("file") val file: String?, @JsonProperty("url") val url: String?, @JsonProperty("label") val label: String?, @JsonProperty("lang") val lang: String?, @JsonProperty("language") val language: String?)
}
