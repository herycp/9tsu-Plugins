package com.example

// Wildcard imports untuk memastikan semua komponen dan utilitas terdeteksi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.cc"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // 1. Fungsi untuk mencari konten (Wajib ada agar tidak error struktur)
    override suspend fun search(query: String): List<SearchResponse> {
        // Logika pencarian diletakkan di sini nantinya
        return listOf()
    }

    // 2. Fungsi untuk memuat detail halaman video
    override suspend fun load(url: String): LoadResponse {
        val title = "Judul Video"
        val poster = "https://example.com/poster.jpg"
        
        // URL embed atau server pihak ketiga yang mengandung video
        val embedUrl = "https://example.com/embed/123"

        return newMovieLoadResponse(title, url, TvType.Movie, embedUrl) {
            this.posterUrl = poster
        }
    }

    // 3. Fungsi untuk mengekstrak dan memutar video
    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Pastikan URL valid sebelum mencoba mengekstrak
        if (data.startsWith("http")) {
            loadExtractor(
                data,
                referer ?: mainUrl,
                subtitleCallback,
                callback
            )
        }
        
        return true
    }
}
