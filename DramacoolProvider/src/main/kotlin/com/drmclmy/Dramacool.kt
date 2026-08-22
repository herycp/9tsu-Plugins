package com.drmclmy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Dramacool : MainAPI() {
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://dramacool.my"
    override var name = "Dramacool"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "recently-added" to "Recently Added",
        "recently-added-movie" to "Recently Added Movies",
        "most-popular-drama" to "Most Popular",
        "popular-ongoing-series" to "Ongoing Series",
        "popular-completed-series" to "Completed Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}?page=$page"
        val document = app.get(url).document
        val items = document.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    /**
     * Mengubah link episode menjadi link series (drama-detail)
     * Contoh: /cang-feng-2025-episode-10.html -> /drama-detail/cang-feng-2025
     */
    private fun convertToSeriesLink(episodeLink: String): String {
        // Hapus "-episode-{number}.html" dari akhir
        val slug = episodeLink
            .substringAfterLast("/")
            .replace(Regex("-episode-\\d+\\.html$"), "")
        return "$mainUrl/drama-detail/$slug"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3.title")?.text()?.trim() ?: return null
        val episodeLink = fixUrlNull(attr("href")) ?: return null
        // Konversi ke link series
        val seriesLink = convertToSeriesLink(episodeLink)
        val img = selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-original") ?: img?.attr("src"))
        
        return newAnimeSearchResponse(title, seriesLink, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?type=movies&keyword=${query.replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("ul.list-episode-item li a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Ambil judul
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst(".movie-title")?.text()?.trim()
            ?: return null

        // Ambil poster
        val posterUrl = document.selectFirst("img.poster")?.attr("src")
            ?: document.selectFirst(".film-poster img")?.attr("src")
            ?.let { fixUrl(it) }

        // Ambil sinopsis
        val description = document.selectFirst(".description, .synopsis, .plot")?.text()?.trim()

        // Ambil daftar episode dari halaman series
        val episodeElements = document.select(
            "div.epdiv a, " +
            "ul.episode-list li a, " +
            ".episodes-list li a, " +
            ".server .episode-item a, " +
            "#episode-list a, " +
            ".episode-item a"
        )

        val episodes = episodeElements.mapNotNull { el ->
            val epName = el.text().trim().ifEmpty { "Episode" }
            val epLink = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            newEpisode(epName) {
                this.data = epLink
            }
        }.reversed() // Episode terbaru di atas

        if (episodes.isEmpty()) {
            // Jika tidak ada episode, anggap sebagai movie
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Method 1: Cari dari tombol play (onclick)
        var iframeUrl = document.selectFirst("#load-iframe")?.attr("onclick")
            ?.substringAfter("playThis(\"")?.substringBefore("\")")
        
        // Method 2: Cari iframe langsung
        if (iframeUrl == null) {
            iframeUrl = document.selectFirst("iframe")?.attr("src")
        }
        
        // Method 3: Cari dari link download atau player
        if (iframeUrl == null) {
            val playerLink = document.selectFirst(".player a, .watch a, #player a")?.attr("href")
            iframeUrl = playerLink
        }

        // Method 4: Cari video source langsung (untuk beberapa kasus)
        if (iframeUrl == null) {
            val videoSrc = document.selectFirst("video source")?.attr("src")
            if (videoSrc != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(videoSrc)
                    )
                )
                return true
            }
        }

        if (iframeUrl == null) {
            return false
        }

        val iframeFullUrl = fixUrl(iframeUrl)
        
        // Coba load iframe dan ekstrak link videonya
        try {
            val iframe = app.get(iframeFullUrl)
            val iframeDoc = iframe.document
            
            // Coba ekstrak dari iframe
            val extracted = loadExtractor(iframeDoc.html(), mainUrl, subtitleCallback, callback)
            if (extracted) {
                return true
            }
            
            // Jika gagal, coba cari langsung di dalam iframe
            val videoLink = iframeDoc.selectFirst("video source")?.attr("src")
                ?: iframeDoc.selectFirst("a[href*=.m3u8]")?.attr("href")
                ?: iframeDoc.selectFirst("a[href*=.mp4]")?.attr("href")
            
            if (videoLink != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(videoLink)
                    )
                )
                return true
            }
            
            return false
        } catch (e: Exception) {
            return false
        }
    }
}
