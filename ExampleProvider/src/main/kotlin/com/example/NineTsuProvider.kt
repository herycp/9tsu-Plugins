package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // 1. Scraping Hasil Pencarian
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document

        // Mengambil elemen-elemen kartu video dari hasil pencarian
        return doc.select("article, .post, .entry").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, .entry-title a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            // Mengambil URL gambar poster (mengecek src atau data-src jika ada lazy loading)
            val imgElement = element.selectFirst("img")
            val posterUrl = imgElement?.attr("data-src")?.ifEmpty { null } 
                ?: imgElement?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    // 2. Scraping Detail Halaman & Embed Video
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Ambil judul halaman
        val title = doc.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() 
            ?: doc.title()

        // Ambil gambar poster utama jika ada
        val posterUrl = doc.selectFirst(".entry-content img, .post-thumbnail img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }

        // Cari URL iframe / pemutar video di dalam halaman (tanpa mengambil deskripsi)
        val embedUrl = doc.selectFirst("iframe[src]")?.attr("src")
            ?: doc.selectFirst("video source[src]")?.attr("src")
            ?: url // Fallback jika link video langsung berupa URL halaman ini

        return newMovieLoadResponse(title, url, TvType.Movie, embedUrl) {
            this.posterUrl = posterUrl
            // Deskripsi disengaja dilewati
        }
    }

    // 3. Memuat Link Video ke Player
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = this.name,
                url = data
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
