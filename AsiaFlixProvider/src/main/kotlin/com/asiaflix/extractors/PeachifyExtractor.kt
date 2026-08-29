package com.asiaflix.extractors

import android.util.Base64
import android.util.Log
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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PeachifyExtractor : ExtractorApi() {
    override val name = "Peachify"
    override val mainUrl = "https://peachify.top"
    override val requiresReferer = true

    private val peachifyServers = listOf(
        "https://uwu.eat-peach.sbs/moviebox",
        "https://usa.eat-peach.sbs/holly",
        "https://usa.eat-peach.sbs/air",
        "https://usa.eat-peach.sbs/multi",
        "https://uwu.eat-peach.sbs/net",
        "https://uwu.eat-peach.sbs/bmb"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("PeachifyDebug", "Target URL: $url")
        val path = url.removePrefix(mainUrl).removePrefix("https://peachify.top").substringBefore("?")
        
        // Memastikan referer dan origin mengarah persis ke mainUrl dari Extractor ini
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Referer" to "$mainUrl/", "Origin" to mainUrl)

        coroutineScope {
            peachifyServers.map { serverUrl ->
                async {
                    try {
                        Log.d("PeachifyDebug", "Querying server: $serverUrl$path")
                        val responseText = app.get("$serverUrl$path", headers = headers, timeout = 10).text
                        var apiRes = tryParseJson<PeachifyApiResponse>(responseText)

                        if (apiRes?.isEncrypted == true && !apiRes.data.isNullOrEmpty()) {
                            val decJson = PeachifyDecryptor.decrypt(apiRes.data)
                            if (decJson != null) apiRes = tryParseJson<PeachifyApiResponse>(decJson)
                        }

                        apiRes?.subtitles?.forEach { sub ->
                            val subUrl = pickString(sub, listOf("url", "file", "src"))
                            val isInvalid = subUrl.endsWith(".jpg") || subUrl.endsWith(".png") || subUrl.endsWith(".m3u8") || subUrl.endsWith(".mp4")
                            if (subUrl.startsWith("http") && !isInvalid) {
                                subtitleCallback.invoke(SubtitleFile(pickString(sub, listOf("label", "name")).ifEmpty { "Auto" }, subUrl))
                            }
                        }

                        apiRes?.sources?.forEach { src ->
                            val streamUrl = pickString(src, listOf("url", "src", "file", "stream"))
                            if (streamUrl.isNotEmpty()) {
                                Log.d("PeachifyDebug", "Found Stream: $streamUrl")
                                val isM3u8 = pickString(src, listOf("type", "format")).lowercase().contains("hls") || streamUrl.lowercase().contains(".m3u8")
                                val dubLabel = normalizeDub(pickString(src, listOf("dub", "audio", "lang", "language", "label")))
                                val serverName = java.net.URI(serverUrl).path.removePrefix("/")
                                
                                callback.invoke(
                                    newExtractorLink(
                                        name = "$name - $serverName ($dubLabel)",
                                        source = this@PeachifyExtractor.name, // Jangan jadi Asiaflix
                                        url = streamUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "$mainUrl/" // Memastikan referer player tepat
                                        this.headers = headers
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) { Log.e("PeachifyDebug", "Error on $serverUrl: ${e.message}") }
                }
            }.awaitAll()
        }
    }

    private fun pickString(map: Map<String, Any?>, keys: List<String>): String = keys.firstNotNullOfOrNull { map[it] as? String }?.trim() ?: ""
    private fun normalizeDub(raw: String): String = if (raw.isBlank()) "Original" else when (raw.trim().lowercase()) { "dubbed" -> "Dub"; "subbed" -> "Sub"; else -> raw.trim() }

    data class PeachifyApiResponse(val isEncrypted: Boolean? = null, val data: String? = null, val sources: List<Map<String, Any?>>? = null, val subtitles: List<Map<String, Any?>>? = null)

    private object PeachifyDecryptor {
        private const val KEY = "YThmMmExYjVlOWM0NzA4MTRmNmIyYzNhNWQ4ZTdmOWMxYTJiM3M0ZDVlM2Y3YThiOGNhZDFlMmQwYTRkNWM1Yg=="
        fun decrypt(payload: String): String? = try {
            val parts = payload.split("."); if (parts.size != 3) null else {
                val iv = Base64.decode(parts[0].replace('-', '+').replace('_', '/').padEnd(parts[0].length + (4 - parts[0].length % 4) % 4, '='), Base64.DEFAULT)
                val ct = Base64.decode(parts[1].replace('-', '+').replace('_', '/').padEnd(parts[1].length + (4 - parts[1].length % 4) % 4, '='), Base64.DEFAULT)
                val at = Base64.decode(parts[2].replace('-', '+').replace('_', '/').padEnd(parts[2].length + (4 - parts[2].length % 4) % 4, '='), Base64.DEFAULT)
                val keyBytes = String(Base64.decode(KEY, Base64.DEFAULT)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val encData = ByteArray(ct.size + at.size).apply { System.arraycopy(ct, 0, this, 0, ct.size); System.arraycopy(at, 0, this, ct.size, at.size) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv)) }
                String(cipher.doFinal(encData), Charsets.UTF_8)
            }
        } catch (_: Exception) { null }
    }
}