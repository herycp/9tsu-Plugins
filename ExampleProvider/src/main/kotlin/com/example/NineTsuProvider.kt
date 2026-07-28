package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

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

    // 3. Fungsi Memuat Link Pemutar (Ditambahkan Suppress agar Deprecation tidak dianggap Error)
    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = data.contains(".m3u8") || data.contains(".m3u")
            )
        )

        return true
    }
}
