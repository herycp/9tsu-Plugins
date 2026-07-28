package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // Helper kustom untuk mengekstrak atribut gambar tanpa memicu error JSpecify
    private fun getAttrOrNull(element: org.jsoup.nodes.Element?, attr: String): String? {
        val value = element?.attr(attr)?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    // 1. Konfigurasi Halaman Utama (Main Page) Sesuai URL Terbaru
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

        val doc = app.get(url).document

        val homeItems = doc.select("article, .post, .entry, .type-post").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, .entry-title a, a[rel='bookmark']") 
                ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            val imgElement = element.selectFirst("img")
            val posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // 3. Pencarian Diakali Menggunakan DuckDuckGo Site Search (Mencegah Redirect ke Homepage)
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim().replace(" ", "+")

        // Metode Utama: Trik DuckDuckGo HTML Search
        try {
            val ddgUrl = "https://html.duckduckgo.com/html/?q=site:9tsu.vip+$cleanQuery"
            val response = app.get(
                ddgUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            )
            val doc = response.document

            doc.select(".result").forEach { element ->
                val titleElem = element.selectFirst("a.result__a") ?: return@forEach
                val rawTitle = titleElem.text()
                val title = rawTitle.replace(Regex("""\s*[-|]\s*9tsu.*""", RegexOption.IGNORE_CASE), "").trim()
                val rawHref = titleElem.attr("href")

                // Unpack URL redirect milik DuckDuckGo (uddg=...)
                val realUrl = if (rawHref.contains("uddg=")) {
                    try {
                        URLDecoder.decode(rawHref.substringAfter("uddg=").substringBefore("&"), "UTF-8")
                    } catch (e: Exception) {
                        rawHref
                    }
                } else {
                    rawHref
                }

                // Filter agar link hasil cari benar-benar berupa postingan video (bukan homepage/kategori)
                if (realUrl.contains("9tsu.vip") && 
                    realUrl != mainUrl && 
                    realUrl != "$mainUrl/" && 
                    !realUrl.contains("/category/") && 
                    !realUrl.contains("/tag/")) {

                    results.add(
                        newTvSeriesSearchResponse(title, realUrl, TvType.TvSeries)
                    )
                }
            }
        } catch (e: Exception) {
            // Lanjut ke fallback jika DuckDuckGo gagal
        }

        // Fallback: WordPress REST API
        if (results.isEmpty()) {
            try {
                val wpApiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$cleanQuery&_embed"
                val apiResponse = app.get(wpApiUrl)

                if (apiResponse.code == 200) {
                    val jsonArray = org.json.JSONArray(apiResponse.text)
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

    // 4. Scraping Detail Halaman Video
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val html = response.text
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() 
            ?: doc.title()

        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img")
        val posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")

        val embedUrls = mutableListOf<String>()

        // A. Ekstrak langsung URL .m3u8 jika ada di HTML
        val m3u8Regex = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
        m3u8Regex.findAll(html).forEach { match ->
            embedUrls.add(match.value)
        }

        // B. Ekstrak iframe player
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) embedUrls.add(src)
        }

        if (embedUrls.isEmpty()) {
            embedUrls.add(url)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, embedUrls.distinct().joinToString(",")) {
            this.posterUrl = posterUrl
        }
    }

    // 5. Ekstraksi Dremoxa/Demoxa iFrame & Penyelesaian Error 3003
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val urls = data.split(",")
        val m3u8Regex = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")

        for (rawUrl in urls) {
            val cleanUrl = rawUrl.trim()
            if (!cleanUrl.startsWith("http")) continue

            val formattedUrl = if (cleanUrl.startsWith("//")) "https:$cleanUrl" else cleanUrl

            // Kasus A: Jika link sudah langsung berupa file .m3u8
            if (formattedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name = "9tsu - Demoxa",
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

            // Kasus B: Jika link berupa iframe dremoxa.space / demoxa.space
            if (formattedUrl.contains("demoxa") || formattedUrl.contains("dremoxa")) {
                try {
                    // Buka halaman iframe pemutar di latar belakang
                    val embedResponse = app.get(
                        formattedUrl,
                        referer = mainUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    )
                    val embedHtml = embedResponse.text

                    // Cari URL playlist.m3u8 tersembunyi di dalam iframe dremoxa
                    val m3u8Match = m3u8Regex.find(embedHtml)?.value
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
                } catch (e: Exception) {
                    // Lanjut ke fallback jika iframe gagal dibuka
                }
            }

            // Kasus C: Fallback extractor bawaan CloudStream
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
