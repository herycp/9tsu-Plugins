package com.drmclmy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.json.JSONObject

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

    // Konversi link episode ke link series (drama-detail)
    private fun convertToSeriesLink(episodeLink: String): String {
        val slug = episodeLink
            .substringAfterLast("/")
            .replace(Regex("-episode-\\d+\\.html$"), "")
        return "$mainUrl/drama-detail/$slug"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3.title")?.text()?.trim() ?: return null
        val episodeLink = fixUrlNull(attr("href")) ?: return null
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

        // --- Judul ---
        val title = document.selectFirst(".details .info h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        // --- Poster ---
        val posterUrl = document.selectFirst(".details .img img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }

        // --- Deskripsi ---
        val description = document.select(".details .info p").mapNotNull { p ->
            if (p.select("span").isEmpty() && p.text().length > 50) {
                p.text().trim()
            } else null
        }.joinToString("\n\n").ifEmpty {
            document.select(".details .info").first()?.text()?.substringAfter("Description:")?.trim()
        }

        // --- Daftar Episode (diurutkan descending berdasarkan nomor episode) ---
        val episodeItems = document.select("ul.list-episode-item-2.all-episode li a")
        val episodes = episodeItems.mapNotNull { el ->
            val titleText = el.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
            val link = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            // Ekstrak nomor episode dari teks "Episode 10"
            val episodeNum = Regex("Episode (\\d+)").find(titleText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Triple(titleText, link, episodeNum)
        }.sortedByDescending { it.third } // urutan terbaru di atas
        .map { (titleText, link, _) ->
            newEpisode(titleText) { this.data = link }
        }

        // Jika tidak ada episode, anggap sebagai movie
        if (episodes.isEmpty()) {
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

        // === Metode 1: Ambil dari elemen .Standard Server.selected (data-video) ===
        val serverElement = document.selectFirst(".Standard Server.selected")
        var videoUrl = serverElement?.attr("data-video")
        if (videoUrl != null) {
            // Tambahkan ?json= untuk mendapatkan JSON dengan link server
            val jsonUrl = if (videoUrl.contains("?")) "$videoUrl&json=" else "$videoUrl?json="
            try {
                val jsonResponse = app.get(jsonUrl).text
                // Parse JSON menggunakan JSONObject dari org.json
                val jsonObject = JSONObject(jsonResponse)
                val streamtape = jsonObject.optString("streamtape")
                val mixdrop = jsonObject.optString("mixdrop")
                val vidhide = jsonObject.optString("vidhide")
                val streamwish = jsonObject.optString("streamwish")

                val linkToTry = streamtape.takeIf { it.isNotEmpty() }
                    ?: mixdrop.takeIf { it.isNotEmpty() }
                    ?: vidhide.takeIf { it.isNotEmpty() }
                    ?: streamwish.takeIf { it.isNotEmpty() }

                if (linkToTry != null) {
                    // Fetch HTML dari link tersebut dan ekstrak menggunakan extractor
                    val doc = app.get(linkToTry).document
                    return loadExtractor(doc.html(), mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Gagal, lanjut ke metode lain
            }
        }

        // === Metode 2: Cari iframe dari tombol play (onclick) atau elemen iframe ===
        var iframeUrl = document.selectFirst("#load-iframe")?.attr("onclick")
            ?.substringAfter("playThis(\"")?.substringBefore("\")")
        if (iframeUrl == null) {
            iframeUrl = document.selectFirst("iframe")?.attr("src")
        }
        if (iframeUrl == null) {
            iframeUrl = document.selectFirst(".player a, .watch a, #player a")?.attr("href")
        }

        if (iframeUrl != null) {
            val iframeFullUrl = fixUrl(iframeUrl)
            try {
                val iframe = app.get(iframeFullUrl)
                return loadExtractor(iframe.document.html(), mainUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                // Gagal
            }
        }

        // === Metode 3: Cari video source langsung ===
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

        return false
    }
}
