package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import java.net.URLDecoder

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // Helper kustom untuk mengekstrak atribut gambar/iframe tanpa memicu error JSpecify
    private fun getAttrOrNull(element: org.jsoup.nodes.Element?, attr: String): String? {
        val value = element?.attr(attr)?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    // Unescape string JavaScript (menghilangkan \/ dan \")
    private fun unescapeJs(str: String): String {
        return str.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    // 1. Konfigurasi Halaman Utama
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

    // 2. Scraping Konten Halaman Utama
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

    // 3. Pencarian Stabil Menggunakan Bing Site Search & WP API Fallback
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim().replace(" ", "+")

        // Metode 1: Bing HTML Search (Tahan anti-bot)
        try {
            val bingUrl = "https://www.bing.com/search?q=site%3A9tsu.vip+$cleanQuery"
            val response = app.get(bingUrl, headers = mapOf("User-Agent" to userAgent))
            val doc = response.document

            doc.select("li.b_algo").forEach { element ->
                val titleElem = element.selectFirst("h2 a") ?: return@forEach
                val rawTitle = titleElem.text()
                val title = rawTitle.replace(Regex("""\s*[-|–]\s*9tsu.*""", RegexOption.IGNORE_CASE), "").trim()
                val href = titleElem.attr("href")

                if (href.contains("9tsu.vip") && 
                    href != mainUrl && 
                    href != "$mainUrl/" && 
                    !href.contains("/category/") && 
                    !href.contains("/tag/")) {

                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries))
                }
            }
        } catch (e: Exception) {
            // Lanjut ke fallback jika Bing gagal
        }

        // Metode 2: Fallback WordPress REST API
        if (results.isEmpty()) {
            try {
                val wpApiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$cleanQuery&_embed"
                val apiResponse = app.get(wpApiUrl, headers = mapOf("User-Agent" to userAgent))

                if (apiResponse.code == 200) {
                    val jsonArray = JSONArray(apiResponse.text)
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val titleRaw = item.getJSONObject("title").getString("rendered")
                        val title = titleRaw.replace(Regex("<[^>]*>"), "").trim()
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

                        if (title.isNotBlank() && link.isNotBlank() && link != mainUrl && link != "$mainUrl/") {
                            results.add(
                                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Abaikan
            }
        }

        return results.distinctBy { it.url }
    }

    // 4. Scraping Detail Halaman Video (Menangkap Semua Sumber Player)
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val html = response.text
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() 
            ?: doc.title()

        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img")
        val posterUrl = getAttrOrNull(imgElement, "data-src") 
            ?: getAttrOrNull(imgElement, "data-lazy-src") 
            ?: getAttrOrNull(imgElement, "src")

        val embedUrls = mutableListOf<String>()

        // Scan atribut iframe (src, data-src, data-lazy-src)
        doc.select("iframe").forEach { iframe ->
            val src = getAttrOrNull(iframe, "src") 
                ?: getAttrOrNull(iframe, "data-src") 
                ?: getAttrOrNull(iframe, "data-lazy-src")
            if (!src.isNullOrBlank()) embedUrls.add(src)
        }

        // Scan script tag untuk URL dremoxa/demoxa/m3u8
        val unescapedHtml = unescapeJs(html)
        val urlRegex = Regex("""https?://[^\s"'<>]+?(?:dremoxa|demoxa|playlist|\.m3u8)[^\s"'<>]*""")
        urlRegex.findAll(unescapedHtml).forEach { match ->
            embedUrls.add(match.value)
        }

        if (embedUrls.isEmpty()) {
            embedUrls.add(url)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, embedUrls.distinct().joinToString(",")) {
            this.posterUrl = posterUrl
        }
    }

    // 5. Pembongkaran Dremoxa iFrame & Pencarian Stream M3U8
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val urls = data.split(",")

        for (rawUrl in urls) {
            val cleanUrl = rawUrl.trim()
            if (!cleanUrl.startsWith("http")) continue

            val formattedUrl = if (cleanUrl.startsWith("//")) "https:$cleanUrl" else cleanUrl

            // Kasus 1: Link sudah berupa file .m3u8 langsung
            if (formattedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name = "9tsu - Direct Stream",
                        source = this.name,
                        url = formattedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://9tsu.vip/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                continue
            }

            // Kasus 2: Link berupa embed/iframe Dremoxa atau Demoxa
            if (formattedUrl.contains("demoxa") || formattedUrl.contains("dremoxa")) {
                try {
                    val embedResponse = app.get(
                        formattedUrl,
                        referer = mainUrl,
                        headers = mapOf("User-Agent" to userAgent)
                    )
                    val embedHtml = unescapeJs(embedResponse.text)

                    // A. Cari URL .m3u8 absolut
                    val absM3u8Regex = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""")
                    val m3u8Match = absM3u8Regex.find(embedHtml)?.value

                    if (!m3u8Match.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                name = "9tsu - Demoxa Stream",
                                source = this.name,
                                url = m3u8Match,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = formattedUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        continue
                    }

                    // B. Cari URL .m3u8 relatif (misal: /playlist/xyz/playlist.m3u8)
                    val relM3u8Regex = Regex("""/playlist/[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""")
                    val relMatch = relM3u8Regex.find(embedHtml)?.value

                    if (!relMatch.isNullOrBlank()) {
                        val fullM3u8 = "https://dremoxa.space$relMatch"
                        callback.invoke(
                            newExtractorLink(
                                name = "9tsu - Demoxa Stream",
                                source = this.name,
                                url = fullM3u8,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = formattedUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        continue
                    }
                } catch (e: Exception) {
                    // Lanjut ke fallback
                }
            }

            // Kasus 3: Fallback ke ekstraktor standar CloudStream
            val loaded = loadExtractor(formattedUrl, subtitleCallback, callback)
            if (!loaded && formattedUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = formattedUrl
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
