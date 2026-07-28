package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor // 1. Pastikan import ini ada

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.cc"
    override var name = "9tsu"
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var hasMainPage = true

    // ... (fungsi search / getMainPage / load detail film di sini) ...

    // 2. loadExtractor HANYA boleh dipanggil di dalam fungsi loadLinks ini
    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // 'data' di sini berisi URL embed/stream yang dikirim dari fungsi load()
        val videoUrl = data

        // Pemanggilan loadExtractor yang benar:
        loadExtractor(
            url = videoUrl,
            referer = referer ?: mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        return true
    }
}
