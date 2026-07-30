package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun getAttrOrNull(element: Element?, attr: String): String? {
        val value = element?.attr(attr)?.trim()
        return if (value.isNullOrEmpty()) null else value
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

    // ==================== TEA DECRYPTION ====================
    private val TEA_KEY = Base64.getDecoder().decode("NDh2aU1Cb0NHRG5hcDFRZQ==")

    private fun teaDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val k = IntArray(4)
        for (i in 0..3) {
            k[i] = ((key[i * 4].toInt() and 0xFF) shl 24) or
                    ((key[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((key[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (key[i * 4 + 3].toInt() and 0xFF)
        }

        val delta = 0x9E3779B9.toInt()
        var sum = delta shl 5
        val rounds = 32
        val bytes = data.copyOf()
        val result = ByteArray(bytes.size)

        for (i in bytes.indices step 8) {
            if (i + 7 >= bytes.size) break
            var v0 = (((bytes[i].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i + 3].toInt() and 0xFF))
            var v1 = (((bytes[i + 4].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 8) or
                    (bytes[i + 7].toInt() and 0xFF))

            for (j in 0 until rounds) {
                v1 -= ((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3])
                v0 -= ((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1])
                sum -= delta
            }

            result[i] = ((v0 ushr 24) and 0xFF).toByte()
            result[i + 1] = ((v0 ushr 16) and 0xFF).toByte()
            result[i + 2] = ((v0 ushr 8) and 0xFF).toByte()
            result[i + 3] = (v0 and 0xFF).toByte()
            result[i + 4] = ((v1 ushr 24) and 0xFF).toByte()
            result[i + 5] = ((v1 ushr 16) and 0xFF).toByte()
            result[i + 6] = ((v1 ushr 8) and 0xFF).toByte()
            result[i + 7] = (v1 and 0xFF).toByte()
        }

        return result
    }

    private fun teaDecryptString(encryptedBase64: String, key: ByteArray): String? {
        return try {
            val encryptedBytes = Base64.getDecoder().decode(encryptedBase64)
            val decryptedBytes = teaDecrypt(encryptedBytes, key)
            val padding = decryptedBytes.lastOrNull()?.toInt() ?: 0
            val unpadded = if (padding in 1..16) {
                decryptedBytes.copyOf(decryptedBytes.size - padding)
            } else {
                decryptedBytes
            }
            String(unpadded, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
    // ========================================================

    // ==================== DREMOXA HELPER ====================
    private fun getBaseUrl(embedUrl: String): String? {
        return try {
            val uri = java.net.URI(embedUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            val match = Regex("""(https?://[^/]+)""").find(embedUrl)
            match?.groupValues?.get(1)
        }
    }

    private suspend fun extractLongIdFromEmbed(embedUrl: String): String? {
        return try {
            val headers = mapOf(
                "Referer" to embedUrl,
                "User-Agent" to userAgent
            )
            val html = app.get(embedUrl, headers = headers).text
            val pattern = Regex("""[a-f0-9]{32}""")
            val match = pattern.find(html)
            match?.value
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractXHashFromEmbed(embedUrl: String): String? {
        return try {
            val headers = mapOf(
                "Referer" to embedUrl,
                "User-Agent" to userAgent
            )
            val html = app.get(embedUrl, headers = headers).text
            // Cari pola x-hash atau nilai hash di script
            // Format: MTc4NTM4NTUzOTgyMjo5ZWYyNjdmYTEzNmI1MjE2MWQyZGJhNzc3NDA5Mzc3MWFhMDQwMDAxNTNmMzljZTI0ZTdmNjk0NzAyY2M3OTlkOm41MWxxbTR0
            val patterns = listOf(
                Regex("""x-hash["']?\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""hash["']?\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""token["']?\s*[:=]\s*["']([^"']+)["']""")
            )
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    // ========================================================

    // ==================== DEBUG DREMOXA ====================
    private suspend fun debugDremoxa(embedUrl: String): String {
        val debug = StringBuilder()
        debug.append("=== DREMOXA DEBUG ===\n")
        debug.append("Embed URL: $embedUrl\n")

        try {
            val shortIdPattern = Regex("""/(?:embed/|e/|v/)([^/?]+)""")
            val shortIdMatch = shortIdPattern.find(embedUrl)
            val shortId = shortIdMatch?.groupValues?.get(1)
            debug.append("Short ID (from URL): $shortId\n")

            val longId = extractLongIdFromEmbed(embedUrl)
            if (longId != null) {
                debug.append("Long ID (extracted): $longId\n")
            } else {
                debug.append("Long ID: NOT FOUND\n")
                return debug.toString()
            }

            val baseUrl = getBaseUrl(embedUrl)
            if (baseUrl == null) {
                debug.append("Base URL: ERROR\n")
                return debug.toString()
            }
            debug.append("Base URL: $baseUrl\n")

            val tokenMatch = Regex("""token=([^&]+)""").find(embedUrl)
            val token = tokenMatch?.groupValues?.get(1)
            debug.append("Token: $token\n")

            // Ekstrak x-hash dari halaman embed
            val xHash = extractXHashFromEmbed(embedUrl)
            if (xHash != null) {
                debug.append("x-hash (extracted): $xHash\n")
            } else {
                debug.append("x-hash: NOT FOUND\n")
                return debug.toString()
            }

            val apiUrl = "$baseUrl/ajax/getSources"
            debug.append("API URL: $apiUrl\n")

            val body = mapOf("id" to longId)

            val headers = mapOf(
                "Referer" to embedUrl,  // Referer adalah URL embed lengkap dengan token
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to userAgent,
                "Origin" to baseUrl,
                "Accept" to "*/*",
                "x-hash" to xHash
            )

            val response = app.post(apiUrl, headers = headers, data = body)
            debug.append("Response Code: ${response.code}\n")

            if (response.code == 200) {
                val rawText = response.text
                debug.append("Raw Response (first 500 chars): ${rawText.take(500)}${if (rawText.length > 500) "..." else ""}\n")

                try {
                    val json = JSONObject(rawText)
                    val encryptedPlaylist = json.optString("playlist", null)
                    val tracks = json.optJSONArray("tracks")

                    debug.append("Encrypted Playlist: ${encryptedPlaylist ?: "null"}\n")
                    debug.append("Tracks count: ${tracks?.length() ?: 0}\n")

                    if (!encryptedPlaylist.isNullOrBlank()) {
                        val parts = encryptedPlaylist.split(":")
                        debug.append("Parts count: ${parts.size}\n")
                        if (parts.size >= 2) {
                            debug.append("Part1 (truncated): ${parts[1].take(100)}${if (parts[1].length > 100) "..." else ""}\n")
                            val decrypted = teaDecryptString(parts[1], TEA_KEY)
                            if (decrypted != null) {
                                debug.append("TEA Decrypted: $decrypted\n")
                                val extracted = extractVideoUrls(decrypted)
                                if (extracted.isNotEmpty()) {
                                    debug.append("Extracted URLs from decrypted: ${extracted.joinToString(", ")}\n")
                                } else {
                                    debug.append("No URLs found in decrypted text\n")
                                }
                            } else {
                                debug.append("TEA decryption FAILED\n")
                            }

                            if (decrypted == null || extractVideoUrls(decrypted).isEmpty()) {
                                try {
                                    val base64Decoded = String(Base64.getDecoder().decode(parts[1]))
                                    debug.append("Base64 Decoded (truncated): ${base64Decoded.take(200)}${if (base64Decoded.length > 200) "..." else ""}\n")
                                    val extractedBase64 = extractVideoUrls(base64Decoded)
                                    if (extractedBase64.isNotEmpty()) {
                                        debug.append("Extracted from base64: ${extractedBase64.joinToString(", ")}\n")
                                    }
                                } catch (e: Exception) {
                                    debug.append("Base64 decode failed: ${e.message}\n")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    debug.append("JSON Parse Error: ${e.message}\n")
                    val extracted = extractVideoUrls(rawText)
                    if (extracted.isNotEmpty()) {
                        debug.append("Extracted from raw text: ${extracted.joinToString(", ")}\n")
                    }
                }
            } else {
                debug.append("Response not 200, skipping extraction\n")
            }
        } catch (e: Exception) {
            debug.append("Exception: ${e.message}\n")
            e.printStackTrace()
        }
        return debug.toString()
    }
    // ========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/daily" to "Harian (Daily)",
        "$mainUrl/drama-monday1" to "Drama Senin",
        "$mainUrl/drama-tuesday1" to "Drama Selasa",
        "$mainUrl/drama-wednesdaydouga" to "Drama Rabu",
        "$mainUrl/drama-thursdaydouga" to "Drama Kamis",
        "$mainUrl/drama-fridaydouga" to "Drama Jumat",
        "$mainUrl/drama-saturdaydouga" to "Drama Sabtu",
        "$mainUrl/drama-sundaydouga" to "Drama Minggu",
        "$mainUrl/dramaend" to "Drama Tamat (End)",
        "$mainUrl/premium" to "Kategori Premium"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            val cleanData = request.data.removeSuffix("/")
            "$cleanData/page/$page/"
        } else {
            request.data
        }

        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val homeItems = doc.select("article, .post, .entry, .type-post, .item, .video-item, .blog-item").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            if (title.isBlank() || href.isBlank() || !href.startsWith("http")) return@mapNotNull null

            val imgElement = element.selectFirst("img")
            var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
            if (posterUrl?.startsWith("data:image") == true) posterUrl = null

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, homeItems)
    }

    // SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim()

        val invalidTitles = listOf("back to homepage", "home", "beranda", "menu", "skip to content", "not found", "404")

        // 1. DuckDuckGo
        try {
            val ddgUrl = "https://html.duckduckgo.com/html/?q=site:9tsu.vip+${cleanQuery.replace(" ", "+")}"
            val ddgRes = app.get(ddgUrl, headers = mapOf("User-Agent" to userAgent))
            val doc = ddgRes.document
            doc.select(".result").forEach { result ->
                val titleElement = result.selectFirst(".result__a")
                val title = titleElement?.text()?.trim() ?: return@forEach
                val href = titleElement?.attr("href") ?: return@forEach
                val realUrl = URLDecoder.decode(href.replace("/l/?uddg=", ""), "UTF-8")
                if (realUrl.contains(mainUrl) && !invalidTitles.any { title.contains(it, ignoreCase = true) }) {
                    results.add(newTvSeriesSearchResponse(title, realUrl, TvType.TvSeries) {
                        this.posterUrl = null
                    })
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 2. REST API
        if (results.isEmpty()) {
            try {
                val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=${cleanQuery.replace(" ", "+")}&_embed&per_page=50"
                val apiRes = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest"))
                if (apiRes.code == 200 && apiRes.text.trim().startsWith("[")) {
                    val jsonArray = JSONArray(apiRes.text)
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val titleRaw = item.getJSONObject("title").optString("rendered", "")
                        val title = titleRaw.replace(Regex("<[^>]*>"), "").replace("&#8211;", "-").trim()
                        val link = item.optString("link", "")
                        if (invalidTitles.any { title.equals(it, ignoreCase = true) }) continue
                        var posterUrl: String? = null
                        if (item.has("_embedded")) {
                            val embedded = item.getJSONObject("_embedded")
                            if (embedded.has("wp:featuredmedia")) {
                                val mediaArray = embedded.getJSONArray("wp:featuredmedia")
                                if (mediaArray.length() > 0) {
                                    posterUrl = mediaArray.getJSONObject(0).optString("source_url", null)
                                }
                            }
                        }
                        if (title.isNotBlank() && link.isNotBlank()) {
                            results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = posterUrl })
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 3. Admin AJAX
        if (results.isEmpty()) {
            try {
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val actions = listOf("search", "search_posts", "loadmore", "search_results", "load_posts")
                for (action in actions) {
                    try {
                        val params = mapOf("action" to action, "s" to cleanQuery, "keyword" to cleanQuery, "search" to cleanQuery)
                        val ajaxRes = app.post(
                            ajaxUrl,
                            data = params,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded"
                            )
                        )
                        if (ajaxRes.code == 200) {
                            val text = ajaxRes.text.trim()
                            if (text.startsWith("{")) {
                                val json = JSONObject(text)
                                val html = json.optString("html", null) ?: json.optString("data", null) ?: json.optString("content", null)
                                if (html != null) {
                                    val fragment = org.jsoup.Jsoup.parse(html)
                                    extractItemsFromDocument(fragment, results, invalidTitles)
                                } else {
                                    val posts = json.optJSONArray("posts") ?: json.optJSONArray("results") ?: json.optJSONArray("items")
                                    if (posts != null) {
                                        for (j in 0 until posts.length()) {
                                            val post = posts.getJSONObject(j)
                                            val title = post.optString("title", "").trim()
                                            val link = post.optString("link", "").trim()
                                            val poster = post.optString("image", null) ?: post.optString("thumbnail", null)
                                            if (title.isNotBlank() && link.isNotBlank() && !invalidTitles.any { title.equals(it, ignoreCase = true) }) {
                                                results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = poster })
                                            }
                                        }
                                    }
                                }
                            } else if (text.startsWith("[")) {
                                val jsonArray = JSONArray(text)
                                for (j in 0 until jsonArray.length()) {
                                    val item = jsonArray.getJSONObject(j)
                                    val title = item.optString("title", "").trim()
                                    val link = item.optString("link", "").trim()
                                    val poster = item.optString("image", null) ?: item.optString("thumbnail", null)
                                    if (title.isNotBlank() && link.isNotBlank() && !invalidTitles.any { title.equals(it, ignoreCase = true) }) {
                                        results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = poster })
                                    }
                                }
                            } else {
                                val doc = org.jsoup.Jsoup.parse(text)
                                extractItemsFromDocument(doc, results, invalidTitles)
                            }
                            if (results.isNotEmpty()) break
                        }
                    } catch (e: Exception) { /* ignore */ }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 4. HTML fallback
        if (results.isEmpty()) {
            try {
                val searchUrls = listOf(
                    "$mainUrl/?s=${cleanQuery.replace(" ", "+")}",
                    "$mainUrl/search/${cleanQuery.replace(" ", "+")}/",
                    "$mainUrl/search/${cleanQuery.replace(" ", "+")}"
                )
                for (url in searchUrls) {
                    val res = app.get(url, headers = mapOf(
                        "User-Agent" to userAgent,
                        "X-Requested-With" to "XMLHttpRequest"
                    ))
                    val doc = res.document
                    extractItemsFromDocument(doc, results, invalidTitles)
                    if (results.isNotEmpty()) break
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        return results.distinctBy { it.url }
    }

    private fun extractItemsFromDocument(doc: Document, results: MutableList<SearchResponse>, invalidTitles: List<String>) {
        doc.select("article, .post, .entry, .type-post, .item, .result-item, .video-block, .search-item, .blog-item, .hentry, .list-item").forEach { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
                ?: element.select("a").firstOrNull { it.text().trim().isNotBlank() }
                ?: return@forEach

            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            if (title.isBlank() || href.isBlank() || !href.contains(mainUrl)) return@forEach
            if (invalidTitles.any { title.contains(it, ignoreCase = true) }) return@forEach

            val imgElement = element.selectFirst("img")
            var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
            if (posterUrl?.startsWith("data:image") == true) posterUrl = null

            results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl })
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, .video-title")?.text()?.trim() ?: doc.title()
        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")
        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

        val description = doc.selectFirst(".entry-content, .post-content")?.text()?.trim()

        var debugInfo = ""
        val iframe = doc.selectFirst("iframe[src*='dremoxa'], iframe[src*='demoxa'], iframe[src*='vtbe']")
        if (iframe != null) {
            val embedUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (embedUrl.isNotBlank()) {
                debugInfo = debugDremoxa(embedUrl)
            }
        }

        val finalPlot = if (debugInfo.isNotEmpty()) {
            "$description\n\n$debugInfo"
        } else {
            description
        }

        return newMovieLoadResponse(title, url, TvType.TvSeries, url) {
            this.posterUrl = posterUrl
            this.plot = finalPlot
        }
    }

    // ==================== EKSTRAKSI DREMOXA UNTUK LOADLINKS ====================
    private suspend fun extractDremoxaLinks(embedUrl: String, parentUrl: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val longId = extractLongIdFromEmbed(embedUrl)
            if (longId == null) {
                return result
            }

            val baseUrl = getBaseUrl(embedUrl)
            if (baseUrl == null) {
                return result
            }

            val xHash = extractXHashFromEmbed(embedUrl)
            if (xHash == null) {
                return result
            }

            val apiUrl = "$baseUrl/ajax/getSources"
            val body = mapOf("id" to longId)

            val headers = mapOf(
                "Referer" to embedUrl,  // URL embed lengkap dengan token
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to userAgent,
                "Origin" to baseUrl,
                "Accept" to "*/*",
                "x-hash" to xHash
            )

            val response = app.post(apiUrl, headers = headers, data = body)
            if (response.code == 200) {
                val text = response.text
                try {
                    val json = JSONObject(text)
                    val encryptedPlaylist = json.optString("playlist", null)
                    val tracks = json.optJSONArray("tracks")

                    if (!encryptedPlaylist.isNullOrBlank()) {
                        val parts = encryptedPlaylist.split(":")
                        if (parts.size >= 2) {
                            val decrypted = teaDecryptString(parts[1], TEA_KEY)
                            if (decrypted != null) {
                                result.addAll(extractVideoUrls(decrypted))
                                if (decrypted.contains(".m3u8")) {
                                    result.add(decrypted)
                                }
                            }

                            if (result.isEmpty()) {
                                val fullDecrypted = teaDecryptString(encryptedPlaylist, TEA_KEY)
                                if (fullDecrypted != null) {
                                    result.addAll(extractVideoUrls(fullDecrypted))
                                }
                            }

                            if (result.isEmpty()) {
                                try {
                                    val decoded = String(Base64.getDecoder().decode(parts[1]))
                                    result.addAll(extractVideoUrls(decoded))
                                } catch (e: Exception) {}
                            }
                        }
                    }

                    if (tracks != null) {
                        for (i in 0 until tracks.length()) {
                            val track = tracks.getJSONObject(i)
                            val file = track.optString("file", null)
                            if (file != null && file.isNotBlank()) result.add(file)
                        }
                    }

                } catch (e: Exception) {
                    result.addAll(extractVideoUrls(text))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.distinct()
    }
    // ========================================================

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
        val embedUrls = mutableSetOf<String>()

        // 1. iframe
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                if (src.contains("dremoxa") || src.contains("demoxa") || src.contains("vtbe") ||
                    src.contains("dood") || src.contains("streamtape") || src.contains("mixdrop")) {
                    embedUrls.add(src)
                } else {
                    allUrls.add(src)
                }
            }
        }

        // 2. video/source
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) allUrls.add(src)
        }

        // 3. script
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

            extractVideoUrls(scriptData).forEach { url -> allUrls.add(url) }

            val jsonPattern = Regex("""(\{.*?(?:file|src|video|url)\s*:\s*"[^"]+".*?\})""")
            jsonPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (file != null && file.isNotBlank()) allUrls.add(file)
                    val sources = json.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val srcObj = sources.getJSONObject(i)
                            val src = srcObj.optString("file", null) ?: srcObj.optString("src", null) ?: srcObj.optString("url", null)
                            if (src != null) allUrls.add(src)
                        }
                    }
                } catch (e: Exception) {}
            }

            val varPattern = Regex("""var\s+player\s*=\s*(\{.*?\})""")
            varPattern.findAll(scriptData).forEach { match ->
                try {
                    val jsonStr = match.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val file = json.optString("file", null) ?: json.optString("src", null) ?: json.optString("video", null) ?: json.optString("url", null)
                    if (file != null) allUrls.add(file)
                } catch (e: Exception) {}
            }
        }

        // 4. data-* attributes
        doc.select("[data-video], [data-src], [data-url], [data-file], [data-link]").forEach { el ->
            val video = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-link") }
            if (video.isNotBlank()) allUrls.add(video)
        }

        // 5. general regex on full html
        extractVideoUrls(html).forEach { url -> allUrls.add(url) }

        // 6. Proses embed URLs
        for (embedUrl in embedUrls) {
            val isDremoxa = embedUrl.contains("dremoxa") || embedUrl.contains("demoxa") || embedUrl.contains("vtbe")
            if (isDremoxa) {
                val dremoxaUrls = extractDremoxaLinks(embedUrl, data)
                dremoxaUrls.forEach { url -> allUrls.add(url) }
            } else {
                try {
                    val embedRes = app.get(embedUrl, referer = data, headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to data,
                        "Origin" to embedUrl.substringBefore("/", "").replace("https://", "").replace("http://", "")
                    ))
                    val embedHtml = embedRes.text
                    extractVideoUrls(embedHtml).forEach { url -> allUrls.add(url) }
                    try {
                        val unpacked = getAndUnpack(embedHtml)
                        if (unpacked.isNotBlank()) {
                            extractVideoUrls(unpacked).forEach { url -> allUrls.add(url) }
                        }
                    } catch (e: Exception) {}
                    val decodedEmbed = decodeBase64IfPossible(embedHtml)
                    if (decodedEmbed != embedHtml) {
                        extractVideoUrls(decodedEmbed).forEach { url -> allUrls.add(url) }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        // 7. API endpoint jika ada ID di URL
        val postId = doc.selectFirst("article")?.attr("id")?.replace("post-", "") ?: doc.selectFirst("[data-post-id]")?.attr("data-post-id")
        if (postId != null) {
            val apiEndpoints = listOf(
                "$mainUrl/wp-json/wp/v2/posts/$postId?_embed",
                "$mainUrl/wp-json/oembed/1.0/embed?url=$data"
            )
            for (endpoint in apiEndpoints) {
                try {
                    val apiRes = app.get(endpoint, headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest"))
                    if (apiRes.code == 200) {
                        extractVideoUrls(apiRes.text).forEach { url -> allUrls.add(url) }
                    }
                } catch (e: Exception) {}
            }
        }

        // 8. Cari link di elemen dengan class 'player' atau 'video-container'
        doc.select(".player, .video-container, .embed-container").forEach { container ->
            container.select("a[href], source, iframe").forEach { el ->
                val link = el.attr("href").ifBlank { el.attr("src") }.ifBlank { el.attr("data-src") }
                if (link.isNotBlank()) allUrls.add(link)
            }
        }

        // 9. Coba endpoint /get_player atau /api/source
        val playerScript = doc.select("script").find { it.data().contains("get_player") || it.data().contains("api/source") }
        if (playerScript != null) {
            val match = Regex("""get_player\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(playerScript.data())
            val id = match?.groupValues?.get(1)
            if (id != null) {
                try {
                    val apiUrl = "$mainUrl/wp-admin/admin-ajax.php?action=get_player&id=$id"
                    val apiRes = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest"))
                    if (apiRes.code == 200) {
                        extractVideoUrls(apiRes.text).forEach { url -> allUrls.add(url) }
                    }
                } catch (e: Exception) {}
            }
        }

        // Proses semua URL yang ditemukan
        var linkFound = false
        for (rawUrl in allUrls) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) continue

            if (loadExtractor(cleanUrl, subtitleCallback, callback)) {
                linkFound = true
                continue
            }

            if (cleanUrl.contains(".m3u8") || cleanUrl.endsWith(".mp4")) {
                val isM3 = cleanUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        name = if (isM3) "9tsu - HLS" else "9tsu - MP4",
                        source = this.name,
                        url = cleanUrl,
                        type = if (isM3) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                linkFound = true
            }
        }

        return linkFound
    }
}
