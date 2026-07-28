package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor // Import penting untuk loadExtractor
import org.jsoup.Nodes

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.cc" // Sesuaikan domain domain utama Anda
    override var name = "9tsu"
    override var supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override var hasMainPage = true

    override async fun loadLinks(
        data: String,
        isCdn: Boolean,
        refer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // ... kode mengambil URL pemutar/embed video Anda ...
        val embedUrl = data 

        // Baris 101 yang diperbaiki:
        // Fungsi loadExtractor dipanggil dengan parameter URL, referer, subtitleCallback, dan callback
        loadExtractor(
            url = embedUrl,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        return true
    }
}
