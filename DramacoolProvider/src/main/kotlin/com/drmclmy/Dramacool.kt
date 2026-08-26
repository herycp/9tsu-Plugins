package com.drmclmy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import android.util.Log

class Dramacool : MainAPI() {
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://dramacool.my"
    override var name = "Dramacool"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "recently-added" to "Recently Added",
        "recently-added-movie" to "Recently Added Movies",
        "most-popular-drama" to "Most Popular",
        "popular-ongoing-series" to "Ongoing Series"
    )

    private fun fixUrlScheme(url: String): String {
        var fixed = url.trim()
        if (fixed.startsWith("//")) {
            fixed = "https:$fixed"
        }
        return fixed
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}?page=$page"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val items = document.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun convertToSeriesLink(episodeLink: String): String {
        val slug = episodeLink
            .substringAfterLast("/")
            .replace(Regex("-episode-\\d+\\.html$"), "")
        return "$mainUrl/drama-detail/$slug"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3.title")?.text()?.trim() ?: return null
        val episodeLink = fixUrlNull(attr("href")) ?: return null
        val seriesLink = convertToSeriesLink(episodeLink)
        val img = selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-original") ?: img?.attr("src"))
        return newAnimeSearchResponse(title, seriesLink, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim().replace(" ", "+")
        if (cleanQuery.isBlank()) return newSearchResponseList(emptyList(), false)

        val url = if (page <= 1) {
            "$mainUrl/search?type=movies&keyword=$cleanQuery"
        } else {
            "$mainUrl/search?type=movies&keyword=$cleanQuery&page=$page"
        }
        
        return try {
            val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
            val results = document.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
            val hasNext = results.isNotEmpty()
            
            newSearchResponseList(results, hasNext)
        } catch (e: Exception) {
            newSearchResponseList(emptyList(), false)
        }
    }

    private fun unescapeJs(str: String): String {
        return str.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun decodeBase64IfPossible(str: String): String {
        return try {
            val decoded = String(Base64.getDecoder().decode(str))
            if (decoded.isNotBlank()) decoded else str
        } catch (e: Exception) { str }
    }

    private fun extractVideoUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/playlist\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/manifest\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/master\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/index\.m3u8[^\s"'<>]*"""),
            Regex("""https?://[^\s"'<>]+/stream\.m3u8[^\s"'<>]*""")
        )
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val url = match.value
                if (url.isNotBlank()) urls.add(unescapeJs(url))
            }
        }
        return urls.distinct()
    }

    private fun decodeBase64Lenient(input: String): ByteArray {
        var base64 = input.trim().replace(Regex("\\s+"), "")
        while (base64.length % 4 != 0) {
            base64 += "="
        }
        return Base64.getMimeDecoder().decode(base64)
    }

    private fun decryptVidBasic(encrypted: String): String {
        val keyBytes = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
        val ivBytes = "5259228356829423".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val cleanEncrypted = encrypted.trim().replace(Regex("\\s+"), "")

        val decoded = try {
            Base64.getMimeDecoder().decode(cleanEncrypted)
        } catch (e: IllegalArgumentException) {
            decodeBase64Lenient(cleanEncrypted)
        }

        val decrypted = cipher.doFinal(decoded)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun decryptVidBasicSubtitle(vttContent: String): String {
        val patterns = listOf(
            Regex("""^WEBVTT"""),
            Regex("""^\d+$"""),
            Regex("""^\d{2}:\d{2}:\d{2}""")
        )
        return vttContent.lines().mapIndexed { _, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || patterns.any { it.containsMatchIn(trimmed) }) {
                line
            } else {
                try {
                    val decrypted = decryptVidBasic(trimmed)
                    decrypted.replace(Regex("""[\u0000-\u0008\u000B-\u001F\uFEFF]"""), "").trim()
                } catch (e: Exception) {
                    line
                }
            }
        }.joinToString("\n")
    }

    // Mekanisme Testing Transparan: Menguji provider satu per satu dengan Logcat eksplisit tanpa failover diam-diam
    private suspend fun uploadSubtitleExplicitTest(finalVtt: String): String {
        val reqBody = finalVtt.toRequestBody("text/vtt".toMediaTypeOrNull())

        // 1. Test Provider: 0x0.st
        try {
            val multiBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "sub.vtt", reqBody).build()
            val res = app.post("https://0x0.st", requestBody = multiBody, timeout = 5).text.trim()
            Log.d("DramacoolSubTest", "0x0.st Response: $res")
            if (res.startsWith("http")) return res
        } catch (e: Exception) {
            Log.e("DramacoolSubTest", "0x0.st Error: ${e.message}")
        }

        // 2. Test Provider: Litterbox (Catbox Temp)
        try {
            val multiBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("reqtype", "fileupload")
                .addFormDataPart("time", "1h")
                .addFormDataPart("fileToUpload", "sub.vtt", reqBody).build()
            val res = app.post("https://litterbox.catbox.moe/api.php", requestBody = multiBody, timeout = 5).text.trim()
            Log.d("DramacoolSubTest", "Litterbox Response: $res")
            if (res.startsWith("http")) return res
        } catch (e: Exception) {
            Log.e("DramacoolSubTest", "Litterbox Error: ${e.message}")
        }

        // 3. Test Provider: Pixeldrain
        try {
            val multiBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "sub.vtt", reqBody).build()
            val res = app.post("https://pixeldrain.com/api/file/", requestBody = multiBody, timeout = 5).text
            val id = JSONObject(res).optString("id")
            val directUrl = "https://pixeldrain.com/api/file/$id"
            Log.d("DramacoolSubTest", "Pixeldrain Response: $directUrl")
            if (id.isNotBlank()) return directUrl
        } catch (e: Exception) {
            Log.e("DramacoolSubTest", "Pixeldrain Error: ${e.message}")
        }

        // 4. Test Provider: Tmpfiles (Dengan mekanisme konversi HTML page ke Raw Link via Jsoup)
        try {
            val multiBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "sub.vtt", reqBody).build()
            val res = app.post("https://tmpfiles.org/api/v1/upload", requestBody = multiBody, timeout = 5).text
            val pageUrl = JSONObject(res).getJSONObject("data").optString("url")
            Log.d("DramacoolSubTest", "Tmpfiles Page URL: $pageUrl")
            
            if (pageUrl.isNotBlank()) {
                // Konversi halaman HTML download ke direct raw link tanpa JS menggunakan Jsoup parsing
                val doc = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent)).document
                val rawLink = doc.select("a#download, a[href*='/dl/']").attr("href").ifBlank {
                    pageUrl.replace("tmpfiles.org/", "tmpfiles.org/dl/")
                }
                Log.d("DramacoolSubTest", "Tmpfiles Converted Raw Link: $rawLink")
                if (rawLink.isNotBlank()) return rawLink
            }
        } catch (e: Exception) {
            Log.e("DramacoolSubTest", "Tmpfiles Error: ${e.message}")
        }

        // 5. Test Provider: Transfer.sh
        try {
            val res = app.put("https://transfer.sh/sub.vtt", requestBody = reqBody, timeout = 5).text.trim()
            Log.d("DramacoolSubTest", "Transfer.sh Response: $res")
            if (res.startsWith("http")) return res
        } catch (e: Exception) {
            Log.e("DramacoolSubTest", "Transfer.sh Error: ${e.message}")
        }

        return ""
    }

    private suspend fun processVidBasic(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anySuccess = false

        try {
            val host = java.net.URL(embedUrl).host
            val headersMap = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://$host/",
                "Origin" to "https://$host"
            )

            val response = app.get(embedUrl, headers = headersMap)
            val html = response.text

            val dataVideoRegex = Regex("""data-video="([^"]+)">Standard""")
            var dataVideo = dataVideoRegex.find(html)?.groupValues?.get(1)
            
            if (dataVideo.isNullOrEmpty()) {
                val doc = org.jsoup.Jsoup.parse(html)
                dataVideo = doc.selectFirst("li[data-video]:contains(Standard)")?.attr("data-video")
                    ?: doc.selectFirst("[data-video]")?.attr("data-video")
            }

            if (!dataVideo.isNullOrEmpty()) {
                val fullUrl = when {
                    dataVideo.startsWith("http") -> dataVideo
                    dataVideo.startsWith("//") -> "https:$dataVideo"
                    else -> "https://$host$dataVideo"
                }

                val html2 = app.get(fullUrl, headers = headersMap).text

                val subParam = Regex("""[\?&]sub=([^&"'>]+)""").let {
                    it.find(fullUrl)?.groupValues?.get(1) ?: it.find(embedUrl)?.groupValues?.get(1)
                }

                if (!subParam.isNullOrEmpty()) {
                    try {
                        val decodedSubParam = URLDecoder.decode(subParam, "UTF-8")
                        val decryptedSubUrl = decryptVidBasic(decodedSubParam)
                        
                        if (decryptedSubUrl.startsWith("http")) {
                            val encryptedVtt = app.get(decryptedSubUrl, headers = headersMap).text
                            val decryptedVtt = decryptVidBasicSubtitle(encryptedVtt)

                            var cleanVtt = decryptedVtt.replace("\r\n", "\n").replace("\r", "\n").trim()
                            if (cleanVtt.startsWith("WEBVTT")) {
                                cleanVtt = cleanVtt.substring(6).trim()
                            }
                            val finalVtt = "WEBVTT\n\n$cleanVtt"

                            if (finalVtt.isNotBlank()) {
                                val uploadedUrl = uploadSubtitleExplicitTest(finalVtt)
                                
                                if (uploadedUrl.isNotBlank()) {
                                    // Validasi akhir memastikan konten merespons dengan format WEBVTT
                                    try {
                                        val testCheck = app.get(uploadedUrl, timeout = 3).text
                                        if (testCheck.contains("WEBVTT")) {
                                            Log.i("DramacoolSubTest", "Subtitle Verified Successfully as WEBVTT!")
                                            subtitleCallback.invoke(
                                                SubtitleFile("English (VidBasic)", uploadedUrl)
                                            )
                                        } else {
                                            Log.w("DramacoolSubTest", "Warning: Uploaded URL did not return WEBVTT content format.")
                                        }
                                    } catch (ex: Exception) {
                                        // Tetap daftarkan jika validasi jaringan ketat terhalang VPN
                                        subtitleCallback.invoke(
                                            SubtitleFile("English (VidBasic)", uploadedUrl)
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val cryptoRegex = Regex("""data-name="crypto"\s*data-value="([^"]+)"""")
                val encrypted = cryptoRegex.find(html2)?.groupValues?.get(1)

                if (!encrypted.isNullOrEmpty()) {
                    try {
                        val decrypted = decryptVidBasic(encrypted)

                        if (decrypted.startsWith("http")) {
                            val isM3u8 = decrypted.contains(".m3u8")
                            callback(
                                newExtractorLink(
                                    name = if (isM3u8) "VidBasic - HLS" else "VidBasic - Direct",
                                    source = name,
                                    url = decrypted,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = fullUrl
                                    this.quality = 0
                                    this.headers = headersMap
                                }
                            )
                            anySuccess = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val apiUrl = if (embedUrl.contains("?")) "$embedUrl&json=" else "$embedUrl?json="
            val response = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent))
            val jsonText = response.text
            
            val json = JSONObject(jsonText)
            val keys = json.keys().asSequence().toList()
            
            for (key in keys) {
                val value = json.optString(key, null)
                if (!value.isNullOrEmpty() && (value.startsWith("http") || value.startsWith("//"))) {
                    val fixedLink = fixUrlScheme(value)
                    val result = loadExtractor(fixedLink, subtitleCallback, callback)
                    if (result) {
                        anySuccess = true
                    }
                }
            }
        } catch (e: Exception) {}

        return anySuccess
    }

    private data class EpisodeExtras(val description: String?, val posterUrl: String?, val airDate: String?, val rating: String?)

    private suspend fun fetchEpisodeExtras(dramaTitle: String, episodeNum: Int): EpisodeExtras {
        return try {
            val formattedTitle = URLEncoder.encode(dramaTitle, "UTF-8")
            val apiUrl = "https://my-drama-list-api-ten.vercel.app/api/id/$formattedTitle/episodes/$episodeNum"
            
            val response = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent), timeout = 4)
            if (response.code == 200) {
                val json = JSONObject(response.text)
                
                val rawRating = json.opt("rating")?.toString()?.trim() ?: ""
                val cleanRating = if (rawRating != "null" && rawRating.isNotBlank()) rawRating else ""

                EpisodeExtras(
                    description = json.optString("description", "").takeIf { it.isNotBlank() && it != "null" },
                    posterUrl = json.optString("image", "").takeIf { it.isNotBlank() && it != "null" },
                    airDate = json.optString("air_date", "").takeIf { it.isNotBlank() && it != "null" },
                    rating = cleanRating
                )
            } else {
                EpisodeExtras(null, null, null, null)
            }
        } catch (e: Exception) {
            EpisodeExtras(null, null, null, null)
        }
    }

    private suspend fun fetchDramaCast(dramaTitle: String): List<ActorData> {
        val actors = mutableListOf<ActorData>()
        try {
            val formattedTitle = URLEncoder.encode(dramaTitle, "UTF-8")
            val apiUrl = "https://my-drama-list-api-ten.vercel.app/api/id/$formattedTitle/cast"
            
            val response = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent), timeout = 3)
            if (response.code == 200) {
                val json = JSONObject(response.text)
                val castObj = json.optJSONObject("cast")
                if (castObj != null) {
                    val mainRoleArr = castObj.optJSONArray("Main Role")
                    val supportRoleArr = castObj.optJSONArray("Support Role")
                    
                    fun processArray(arr: JSONArray?) {
                        if (arr == null) return
                        for (i in 0 until arr.length()) {
                            if (actors.size >= 10) break
                            when (val item = arr.get(i)) {
                                is JSONObject -> {
                                    val name = item.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                                    val character = item.optString("character", "").ifBlank { item.optString("role", "") }.takeIf { it.isNotBlank() }
                                    val image = item.optString("image", "").takeIf { it.isNotBlank() }
                                    actors.add(ActorData(Actor(name, image), roleString = character))
                                }
                                is String -> {
                                    if (item.trim().startsWith("{")) {
                                        try {
                                            val obj = JSONObject(item)
                                            val name = obj.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                                            val character = obj.optString("character", "").ifBlank { obj.optString("role", "") }.takeIf { it.isNotBlank() }
                                            val image = obj.optString("image", "").takeIf { it.isNotBlank() }
                                            actors.add(ActorData(Actor(name, image), roleString = character))
                                        } catch (e: Exception) {
                                            actors.add(ActorData(Actor(item.trim())))
                                        }
                                    } else if (item.isNotBlank()) {
                                        actors.add(ActorData(Actor(item.trim())))
                                    }
                                }
                            }
                        }
                    }

                    processArray(mainRoleArr)
                    processArray(supportRoleArr)
                }
            }
        } catch (e: Exception) {}
        return actors.take(10)
    }

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title = document.selectFirst(".details .info h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return@coroutineScope null

        val posterUrl = document.selectFirst(".details .img img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }

        val description = document.select(".details .info p").mapNotNull { p ->
            if (p.select("span").isEmpty() && p.text().length > 50) {
                p.text().trim()
            } else null
        }.joinToString("\n\n").ifEmpty {
            document.select(".details .info").first()?.text()?.substringAfter("Description:")?.trim()
        }

        val actorsDeferred = async { fetchDramaCast(title) }

        val episodeItems = document.select("ul.list-episode-item-2.all-episode li a")
        val episodeRegex = Regex("""(?i)(?:Episode|EP|E)\s*(\d+(?:\.\d+)?)""")

        val episodesDeferred = episodeItems.map { el ->
            async {
                val titleText = el.selectFirst("h3.title")?.text()?.trim() ?: return@async null
                val link = fixUrlNull(el.attr("href")) ?: return@async null
                
                val epMatch = episodeRegex.find(titleText)
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val extras = fetchEpisodeExtras(title, epNum)

                val descBuilder = StringBuilder()
                val metaData = mutableListOf<String>()
                
                if (!extras.airDate.isNullOrBlank()) {
                    metaData.add("📅 ${extras.airDate}")
                }
                
                if (!extras.rating.isNullOrBlank()) {
                    metaData.add("⭐ ${extras.rating}")
                }

                if (metaData.isNotEmpty()) {
                    descBuilder.append(metaData.joinToString(" • "))
                    descBuilder.append(" ｜ ") 
                }

                if (!extras.description.isNullOrBlank()) {
                    descBuilder.append(extras.description)
                } else {
                    descBuilder.append("Nantikan kisah seru dan menegangkan di episode ini!")
                }

                newEpisode(titleText) {
                    this.data = link
                    this.episode = epNum
                    this.posterUrl = extras.posterUrl
                    this.description = descBuilder.toString()
                }
            }
        }

        val recommendationsDeferred = async {
            val recommendations = mutableListOf<SearchResponse>()
            val tags = document.select("div.tags a").mapNotNull { it.attr("href") }
            val maxRecommendations = 15
            
            for (tagUrl in tags) {
                if (recommendations.size >= maxRecommendations) break
                
                val cleanTag = tagUrl.substringAfterLast("/tags/").substringAfterLast("/")
                val tagLower = cleanTag.lowercase()
                
                if (tagLower.isNotBlank() && tagLower != "drama" && tagLower != "kdrama") {
                    try {
                        val tagPageUrl = "$mainUrl/tags/$cleanTag?page=1"
                        val tagDoc = app.get(tagPageUrl, headers = mapOf("User-Agent" to userAgent), timeout = 3).document
                        val items = tagDoc.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
                        
                        for (item in items) {
                            if (item.url != url && recommendations.none { it.url == item.url }) {
                                recommendations.add(item)
                                if (recommendations.size >= maxRecommendations) break
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            recommendations.distinctBy { it.url }
        }

        val actorsList = actorsDeferred.await()
        val episodes = episodesDeferred.awaitAll().filterNotNull().sortedByDescending { it.episode ?: 0 }
        val finalRecommendations = recommendationsDeferred.await()

        if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.actors = actorsList
                this.recommendations = finalRecommendations
            }
        } else if (episodes.size == 1) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = posterUrl
                this.plot = description
                this.actors = actorsList
                this.recommendations = finalRecommendations
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.actors = actorsList
                this.recommendations = finalRecommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val docRes = app.get(data, headers = mapOf("User-Agent" to userAgent))
        val html = docRes.text
        val doc = docRes.document

        val allUrls = mutableSetOf<String>()

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(fixUrlScheme(src))
        }

        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(fixUrlScheme(src))
        }

        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(fixUrlScheme(video))
        }

        doc.select("script").forEach { script ->
            var scriptData = script.data()
            try {
                if (scriptData.contains("eval(") || scriptData.contains("pako") || scriptData.contains("atob")) {
                    val unpacked = getAndUnpack(scriptData)
                    if (unpacked.isNotBlank()) scriptData = unpacked
                }
            } catch (e: Exception) {}
            
            val decoded = decodeBase64IfPossible(scriptData)
            if (decoded != scriptData) scriptData = decoded
            
            extractVideoUrls(scriptData).forEach { url -> allUrls.add(fixUrlScheme(url)) }
            
            val jsonPattern = Regex("""(\{.*?(?:file|src|video|url)\s*:\s*"[^"]+".*?\})""")
            jsonPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (!file.isNullOrBlank()) allUrls.add(fixUrlScheme(file))
                } catch (e: Exception) {}
            }
        }

        extractVideoUrls(html).forEach { url -> allUrls.add(fixUrlScheme(url)) }

        var linkFound = false

        for (rawUrl in allUrls) {
            val cleanUrl = fixUrlScheme(rawUrl)
            if (!cleanUrl.startsWith("http")) continue

            if (cleanUrl.contains("vidbasic.top") || cleanUrl.contains("vidb.top")) {
                val result = processVidBasic(cleanUrl, subtitleCallback, callback)
                if (result) linkFound = true
                continue
            }

            if (loadExtractor(cleanUrl, subtitleCallback, callback)) {
                linkFound = true
                continue
            }

            if (cleanUrl.contains(".m3u8") || cleanUrl.endsWith(".mp4")) {
                val isM3 = cleanUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        name = if (isM3) "Dramacool - HLS" else "Dramacool - MP4",
                        source = this.name,
                        url = cleanUrl,
                        type = if (isM3) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                linkFound = true
            }
        }

        return linkFound
    }
}
