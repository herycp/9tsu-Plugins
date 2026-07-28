package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray

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

    // 1. Konfigurasi Halaman Utama berdasarkan kategori asli 9tsu.vip
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/drama-monday1" to "Drama Senin",
        "$mainUrl/drama-tuesday1" to "Drama Selasa",
        "$mainUrl/drama-wednesday1" to "Drama Rabu",
        "$mainUrl/drama-thursday1" to "Drama Kamis",
        "$mainUrl/drama-friday1" to "Drama Jumat",
        "$mainUrl/drama-saturday1" to "Drama Sabtu",
        "$mainUrl/drama-sunday1" to "Drama Minggu",
        "$mainUrl/dramaend" to "Drama Tamat (End)"
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

    // 3. Pencarian Ganda (AJAX Header + WP REST API Fallback)
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val formattedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$formattedQuery"

        // Metode A: Coba Scraping HTML dengan Header AJAX
        try {
            val response = app.get(
                searchUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
            val doc = response.document

            val elements = doc.select("article, .post, .entry, .type-post, .search-result, .ajax-search-item, li.post-item")

            elements.forEach { element ->
                val titleElement = element.selectFirst("h2 a, h3 a, .entry-title a, a[rel='bookmark'], a.title") 
                    ?: element.selectFirst("a") ?: return@forEach
                val title = titleElement.text().trim()
                val href = titleElement.attr("href")

                if (title.isNotBlank() && href.isNotBlank() && href.startsWith("http")) {
                    val imgElement = element.selectFirst("img")
                    val posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")

                    results.add(
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Lanjut ke fallback jika gagal
        }

        // Metode B: Fallback ke WordPress REST API jika scraping HTML tidak menghasilkan apa-apa
        if (results.isEmpty()) {
            try {
                val wpApiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$formattedQuery&_embed"
                val apiResponse = app.get(wpApiUrl)

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

                        if (title.isNotBlank() && link.isNotBlank()) {
                            results.add(
                                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Abaikan jika REST API dikunci
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

        // Ekstrak URL dremoxa/demoxa .m3u8 via Regex
        val m3u8Regex = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
        m3u8Regex.findAll(html).forEach { match ->
            embedUrls.add(match.value)
        }

        // Ekstrak iframe jika ada
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

    // 5. Penanganan Stream dremoxa.space / demoxa.space (.m3u8)
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

            if (formattedUrl.contains("demoxa") || formattedUrl.contains("dremoxa") || formattedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name = "9tsu - Demoxa",
                        source = this.name,
                        url = formattedUrl
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
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
        }

        return true
    }
}
