package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor // <-- 1. IMPORT PENTING DI SINI

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.cc"
    override var name = "9tsu"
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var hasMainPage = true

    // Fungsi untuk mengambil detail halaman (Film/TV Show)
    override suspend fun load(url: String): LoadResponse {
        val title = "Sample Title"
        val poster = "https://example.com/poster.jpg"
        
        // Data yang dikirim ke loadLinks (bisa berupa URL video/embed)
        val embedUrl = "https://example.com/embed/123"

        return newMovieLoadResponse(title, url, TvType.Movie, embedUrl) {
            this.posterUrl = poster
        }
    }

    // <-- 2. loadExtractor HANYA BOLEH DIPANGGIL DI DALAM loadLinks()
    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Baris 38: Memanggil loadExtractor di dalam scope yang benar
        loadExtractor(
            url = data,
            referer = referer ?: mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        return true
    }
}
