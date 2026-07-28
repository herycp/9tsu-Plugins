package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.json.JSONArray

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun getAttrOrNull(element: org.jsoup.nodes.Element?, attr: String): String? {
        val value = element?.attr(attr)?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    private fun unescapeJs(str: String): String {
        return str.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    // 1. Kategori Halaman Utama
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

    // 2. Scraping Beranda
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            val cleanData = request.data.removeSuffix("/")
            "$cleanData/page/$page/"
        } else {
            request.data
        }

        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val homeItems = doc.select("article, .post, .entry, .type-post").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, .entry-title a, a[rel='bookmark']") 
                ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            val imgElement = element.selectFirst("img")
            val posterUrl = getAttrOrNull(imgElement, "data-src") 
                ?: getAttrOrNull(imgElement, "data-lazy-src") 
                ?: getAttrOrNull(imgElement, "src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // 3. Pencarian Berbasis AJAX / REST API (Mengatasi Error 404)
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim()

        // Metode A: WP REST API Search
        try {
            val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=${cleanQuery.replace(" ", "+")}&_embed&per_page=20"
            val apiRes = app.get(apiUrl, headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "application/json"
            ))

            if (apiRes.code == 200 && apiRes.text.startsWith("[")) {
                val jsonArray = JSONArray(apiRes.text)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val titleRaw = item.getJSONObject("title").getString("rendered")
                    val title = titleRaw.replace(Regex("<[^>]*>"), "").replace("&#8211;", "-").trim()
                    val link = item.getString("link")

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
                        results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        } catch (e: Exception) {
            // Lanjut ke metode B jika WP-JSON dinonaktifkan
        }

        // Metode B: Direct Admin-AJAX Call
        if (results.isEmpty()) {
            try {
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val ajaxRes = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "ajax_search",
                        "s" to cleanQuery,
                        "keyword" to cleanQuery
                    ),
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )

                if (ajaxRes.code == 200) {
                    val doc = ajaxRes.document
                    doc.select("a").forEach { a ->
                        val href = a.attr("href")
                        val title = a.text().trim()
                        if (href.contains("9tsu.vip") && title.isNotBlank()) {
                            results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries))
                        }
                    }
                }
            } catch (e: Exception) {
                // Abaikan
            }
        }

        return results.distinctBy { it.url }
    }

    // 4. Memuat Detail Halaman & Penangkapan Embed Video
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val html = response.text
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() 
            ?: doc.title()

        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        val posterUrl = getAttrOrNull(imgElement, "data-src") 
            ?: getAttrOrNull(imgElement, "data-lazy-src") 
            ?: getAttrOrNull(imgElement, "src")

        val embedUrls = mutableListOf<String>()

        // A. Pindai Semua Tag iFrame
        doc.select("iframe").forEach { iframe ->
            getAttrOrNull(iframe, "src")?.let { embedUrls.add(it) }
            getAttrOrNull(iframe, "data-src")?.let { embedUrls.add(it) }
            getAttrOrNull(iframe, "data-lazy-src")?.let { embedUrls.add(it) }
        }

        // B. Pindai Tag Script / Teks HTML Baku (Dremoxa, Demoxa, Vtbe)
        val unescapedHtml = unescapeJs(html)
        val regex = Regex("""https?://[^\s"'<>]+?(?:dremoxa|demoxa|vtbe|playlist|\.m3u8)[^\s"'<>]*""")
        regex.findAll(unescapedHtml).forEach { match ->
            embedUrls.add(match.value)
        }

        // C. Jika Player Dipanggil via AJAX Post ID
        val postId = doc.selectFirst("article[id*='post-']")?.id()?.replace("post-", "")
            ?: doc.selectFirst("input[name='comment_post_ID']")?.attr("value")

        if (!postId.isNullOrBlank()) {
            try {
                val playerAjax = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "get_player",
                        "id" to postId
                    ),
                    headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest")
                )
                val ajaxHtml = unescapeJs(playerAjax.text)
                regex.findAll(ajaxHtml).forEach { match ->
                    embedUrls.add(match.value)
                }
            } catch (e: Exception) {
                // Lanjut jika endpoint ini tidak aktif
            }
        }

        if (embedUrls.isEmpty()) {
            embedUrls.add(url)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, embedUrls.distinct().joinToString(",")) {
            this.posterUrl = posterUrl
        }
    }

    // 5. Unpack Dremoxa/Demoxa & Pengambilan Stream M3U8
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val urls = data.split(",")

        for (rawUrl in urls) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.isBlank()) continue
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) continue

            // 1. Direct Stream M3U8
            if (cleanUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name = "9tsu - Direct Stream",
                        source = this.name,
                        url = cleanUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                continue
            }

            // 2. Dremoxa / Demoxa Embed
            if (cleanUrl.contains("dremoxa") || cleanUrl.contains("demoxa") || cleanUrl.contains("vtbe")) {
                try {
                    val embedRes = app.get(
                        cleanUrl,
                        referer = mainUrl,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Accept" to "*/*"
                        )
                    )
                    val rawHtml = embedRes.text
                    val unescaped = unescapeJs(rawHtml)
                    val unpackedHtml = try { getAndUnpack(rawHtml) } catch (e: Exception) { "" }
                    val combinedText = "$rawHtml\n$unescaped\n$unpackedHtml"

                    val domainMatch = Regex("""https?://[^/]+""").find(cleanUrl)?.value ?: "https://dremoxa.space"

                    // Konversi hasil regex ke List menggunakan .toList()
                    val absM3u8 = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""").findAll(combinedText).map { it.value }.toList()
                    val relM3u8 = Regex("""/playlist/[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""").findAll(combinedText).map { "$domainMatch${it.value}" }.toList()
                    val fileM3u8 = Regex("""(?:file|source|src)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(combinedText).map { match ->
                        val path = match.groupValues[1]
                        if (path.startsWith("http")) path else "$domainMatch$path"
                    }.toList()

                    val foundStreams = (absM3u8 + relM3u8 + fileM3u8).distinct()

                    for (streamUrl in foundStreams) {
                        callback.invoke(
                            newExtractorLink(
                                name = "9tsu - Demoxa Stream",
                                source = this.name,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = cleanUrl
                                this.headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to cleanUrl,
                                    "Origin" to domainMatch
                                )
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }

                    if (foundStreams.isNotEmpty()) continue
                } catch (e: Exception) {
                    // Lanjut ke extractor bawaan jika gagal
                }
            }

            // 3. Fallback Ekstraktor Standar
            val loaded = loadExtractor(cleanUrl, subtitleCallback, callback)
            if (!loaded && cleanUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = cleanUrl
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return true
    }
}
