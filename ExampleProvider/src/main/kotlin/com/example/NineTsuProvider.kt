package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // Helper kustom untuk mengekstrak atribut gambar tanpa memicu error anotasi JSpecify
    private fun getAttrOrNull(element: org.jsoup.nodes.Element?, attr: String): String? {
        val value = element?.attr(attr)?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    // 1. Konfigurasi Halaman Utama (Main Page / Home)
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/category/japan-drama/" to "Drama Jepang",
        "$mainUrl/category/variety-show/" to "Variety Show"
    )

    // 2. Scraping Konten Halaman Utama
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = app.get(url).document

        val homeItems = doc.select("article, .post, .entry").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, .entry-title a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            val imgElement = element.selectFirst("img")
            val posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // 3. Scraping Hasil Pencarian (Search)
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document

        return doc.select("article, .post, .entry").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, .entry-title a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            val imgElement = element.selectFirst("img")
            val posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    // 4. Scraping Detail Halaman Video
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() 
            ?: doc.title()

        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img")
        val posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")

        // Ambil semua URL embed/iframe di halaman video
        val embedUrls = doc.select("iframe[src]").map { it.attr("src") }
            .ifEmpty { listOf(url) }

        return newMovieLoadResponse(title, url, TvType.Movie, embedUrls.joinToString(",")) {
            this.posterUrl = posterUrl
            // Deskripsi disengaja dilewati sesuai permintaan
        }
    }

    // 5. Memuat & Mengurai Link Video ke Player
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val urls = data.split(",")

        for (embedUrl in urls) {
            val cleanUrl = embedUrl.trim()
            if (!cleanUrl.startsWith("http")) continue

            // 1. Coba ekstraksi otomatis dengan CloudStream Extractor bawaan
            val loaded = loadExtractor(cleanUrl, subtitleCallback, callback)

            // 2. Fallback: Kirimkan langsung jika ekstraktor otomatis tidak menangani
            if (!loaded) {
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
