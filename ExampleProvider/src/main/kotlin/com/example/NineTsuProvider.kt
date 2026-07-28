package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor // 1. Pastikan import ini ada

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.cc"
    override var name = "9tsu"
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var hasMainPage = true

    // Fungsi ini HANYA untuk mengambil detail halaman (TIDAK BOLEH pakai loadExtractor di sini)
    override suspend fun load(url: String): LoadResponse {
        // ... Logika scraping detail film/episode Anda ...
        
        val title = "Judul Film"
        val poster = "https://example.com/poster.jpg"
        val videoEmbedUrl = "https://example.com/embed/123" // URL pemutar video yang akan diproses

        return newMovieLoadResponse(title, url, TvType.Movie, videoEmbedUrl) {
            this.posterUrl = poster
        }
    }

    // BARIS 29 SEBELUMNYA ERROR DI SINI:
    // Pindahkan pemanggilan loadExtractor ke dalam fungsi loadLinks di bawah ini:
    override suspend fun loadLinks(
        data: String, // 'data' berisi videoEmbedUrl yang dikirim dari load()
        isCdn: Boolean,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Memanggil loadExtractor di tempat yang benar
        loadExtractor(
            url = data,
            referer = referer ?: mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        return true
    }
}
