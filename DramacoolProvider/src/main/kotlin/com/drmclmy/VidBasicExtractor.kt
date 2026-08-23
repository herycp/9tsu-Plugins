package com.drmclmy

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VidBasicExtractor : ExtractorApi() {
    override val name = "VidBasic"
    override val mainUrl = "https://vidbasic.top"
    override val requiresReferer = true

    override fun getExtractorUrl(id: String): String {
        val regex = Regex("""https?://(vidbasic\.top|vidb\.top)/embed/([0-9a-zA-Z]+)""")
        val match = regex.find(id)
        return match?.value ?: ""
    }

    override suspend fun getLinks(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anySuccess = false
        val embedUrl = url

        // ===== 1. METODE UTAMA: AES DECRYPT (Link dari server VidBasic sendiri) =====
        try {
            val response = app.get(embedUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
            val html = response.text

            val dataVideoRegex = Regex("""data-video="([^"]+)">Standard""")
            var dataVideo = dataVideoRegex.find(html)?.groupValues?.get(1)
            if (dataVideo.isNullOrEmpty()) {
                val doc = org.jsoup.Jsoup.parse(html)
                dataVideo = doc.selectFirst(".Standard Server.selected")?.attr("data-video")
            }

            if (!dataVideo.isNullOrEmpty()) {
                val fullUrl = if (dataVideo.startsWith("//")) "https:$dataVideo" else dataVideo

                val response2 = app.get(
                    fullUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Referer" to embedUrl,
                        "Origin" to "https://${java.net.URL(embedUrl).host}"
                    )
                )
                val html2 = response2.text

                val cryptoRegex = Regex("""data-name="crypto"\s*data-value="([^"]+)"""")
                val encrypted = cryptoRegex.find(html2)?.groupValues?.get(1)

                if (!encrypted.isNullOrEmpty()) {
                    val decrypted = decryptVidBasic(encrypted)

                    if (decrypted.startsWith("http")) {
                        val isM3u8 = decrypted.contains(".m3u8")
                        callback(
                            newExtractorLink(
                                name = if (isM3u8) "$name - HLS (VidBasic)" else "$name - Direct (VidBasic)",
                                source = name,
                                url = decrypted,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = fullUrl
                                this.quality = 0
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0",
                                    "Referer" to fullUrl
                                )
                            }
                        )
                        anySuccess = true
                    }
                }
            }
        } catch (e: Exception) {
            // AES gagal, lanjut ke metode lain
        }

        // ===== 2. METODE TAMBAHAN: API JSON (Link dari provider lain) =====
        try {
            val apiUrl = if (embedUrl.contains("?")) "$embedUrl&json=" else "$embedUrl?json="
            val response = app.get(apiUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
            val jsonText = response.text
            val json = JSONObject(jsonText)

            val allKeys = json.keys().asSequence().toList()
            for (key in allKeys) {
                val value = json.optString(key, null)
                if (!value.isNullOrEmpty() && (value.startsWith("http") || value.startsWith("//"))) {
                    val fixedLink = if (value.startsWith("//")) "https:$value" else value
                    val result = loadExtractor(fixedLink, subtitleCallback, callback)
                    if (result) anySuccess = true
                }
            }
        } catch (e: Exception) {
            // API gagal
        }

        // ===== 3. FALLBACK: Cari link langsung di HTML =====
        if (!anySuccess) {
            try {
                val response = app.get(embedUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
                val html = response.text
                val doc = org.jsoup.Jsoup.parse(html)

                val videoSrc = doc.selectFirst("video source")?.attr("src")
                if (!videoSrc.isNullOrEmpty()) {
                    val fixed = if (videoSrc.startsWith("//")) "https:$videoSrc" else videoSrc
                    callback(
                        newExtractorLink(
                            name = "$name - Direct",
                            source = name,
                            url = fixed,
                            type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedUrl
                            this.quality = 0
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0",
                                "Referer" to embedUrl
                            )
                        }
                    )
                    anySuccess = true
                }
            } catch (e: Exception) {}
        }

        return anySuccess
    }

    private fun decryptVidBasic(encrypted: String): String {
        val keyBytes = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
        val ivBytes = "5259228356829423".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val decoded = Base64.getDecoder().decode(encrypted)
        val decrypted = cipher.doFinal(decoded)
        return String(decrypted, Charsets.UTF_8)
    }
}
