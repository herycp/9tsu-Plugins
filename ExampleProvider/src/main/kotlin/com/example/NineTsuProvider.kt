package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink // Import fungsi pembantu baru

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // 1. Fungsi Pencarian (Search)
    override suspend fun search(query: String): List<SearchResponse> {
        return listOf()
    }

    // 2. Fungsi Memuat Halaman Detail
    override suspend fun load(url: String): LoadResponse {
        val title = "Judul Video"
        val poster = "https://example.com/poster.jpg"
        val videoUrl = "https://example.com/video.m3u8"

        return newMovieLoadResponse(title, url, TvType.Movie, videoUrl) {
            this.posterUrl = poster
        }
    }

    // 3. Fungsi Memuat Link Pemutar (Diperbarui untuk API CloudStream Terbaru)
    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Menggunakan newExtractorLink(...) sesuai standar API terbaru
        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = this.name,
                url = data,
                type = if (data.contains(".m3u8") || data.contains(".m3u")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer ?: mainUrl
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
