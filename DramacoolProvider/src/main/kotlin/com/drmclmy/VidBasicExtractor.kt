package com.drmclmy

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Extractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.jsoup.Jsoup
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VidBasicExtractor : Extractor() {
    override val name = "VidBasic"
    override val mainUrl = "https://vidbasic.top"
    override val requiresReferer = true

    // Pola URL: https://vidbasic.top/embed/kev99can2gk
    override fun getExtractorUrl(url: String): String? {
        val regex = Regex("""https?://(vidbasic\.top|vidb\.top)/embed/([0-9a-zA-Z]+)""")
        val match = regex.find(url)
        return match?.value
    }

    override suspend fun getLinks(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // 1. Ambil HTML dari URL embed
            val response = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0"))
            val html = response.text
            val doc = Jsoup.parse(html)

            // 2. Cari data-video dari elemen "Standard Server selected"
            var dataVideo = doc.selectFirst(".Standard Server.selected")?.attr("data-video")
            if (dataVideo.isNullOrEmpty()) {
                // Coba regex fallback
                val regex = Regex("""data-video="([^"]+)">Standard""")
                val match = regex.find(html)
                dataVideo = match?.groupValues?.get(1)
            }

            if (dataVideo.isNullOrEmpty()) {
                return false
            }

            // 3. Perbaiki skema URL
            val fullUrl = if (dataVideo.startsWith("//")) "https:$dataVideo" else dataVideo

            // 4. Ambil parameter sub jika ada
            val subParam = runCatching {
                val parsed = java.net.URL(fullUrl)
                val query = parsed.query ?: ""
                if (query.contains("sub=")) {
                    val subValue = query.substringAfter("sub=").substringBefore("&")
                    "&sub=$subValue"
                } else ""
            }.getOrDefault("")

            val url2 = if (subParam.isNotEmpty()) "$fullUrl$subParam" else fullUrl

            // 5. Fetch URL kedua (dengan referer)
            val response2 = app.get(
                url2,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to url,
                    "Origin" to "https://${java.net.URL(url).host}"
                )
            )
            val html2 = response2.text

            // 6. Cari data-value dari crypto (AES encrypted)
            val cryptoRegex = Regex("""data-name="crypto"\s*data-value="([^"]+)"""")
            val cryptoMatch = cryptoRegex.find(html2)
            val encrypted = cryptoMatch?.groupValues?.get(1)

            if (encrypted.isNullOrEmpty()) {
                return false
            }

            // 7. Decrypt dengan AES-CBC
            val decrypted = decryptVidBasic(encrypted)

            if (decrypted.startsWith("http")) {
                val isM3u8 = decrypted.contains(".m3u8")
                callback(
                    ExtractorLink(
                        source = name,
                        name = if (isM3u8) "$name - HLS" else name,
                        url = decrypted,
                        referer = url2,
                        quality = Qualities.Unknown.value,
                        isM3u8 = isM3u8,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0",
                            "Referer" to url2
                        )
                    )
                )
                return true
            }

            return false

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Decrypt AES-CBC dengan key dan iv dari vidbasic.py
     * Key: "94588293375053432799222445521289"
     * IV:  "5259228356829423"
     */
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
