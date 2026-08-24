package com.dramika

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Dramika : MainAPI() {
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override var mainUrl = "https://dramika.com"
    override var name = "Dramika"
    override val hasMainPage = true

    // Ubah ke UA Desktop yang lebih aman dari blokir Cloudflare
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultHeaders = mapOf("User-Agent" to userAgent)

    override val mainPage = mainPageOf(
        "/dramas/" to "Dramas",
        "/movies/" to "Movies",
        "/tvshows/" to "TV Shows"
    )

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "${mainUrl}${request.data}" else "${mainUrl}${request.data}page/$page/"
        val document = app.get(url, headers = defaultHeaders).document
        val items = document.select("a.group").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val img = selectFirst("img")
        var title = img?.attr("alt")?.replace("Poster for ", "")?.trim()
        if (title.isNullOrEmpty()) {
            title = selectFirst("h2")?.text()?.trim()
        }
        if (title.isNullOrEmpty()) return null

        val href = fixUrlNull(attr("href")) ?: return null
        val posterUrl = fixUrlNull(img?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
            // posterHeaders TIDAK DISERTAKAN agar Glide (Image Loader Cloudstream) memuat secara natural
        }
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = defaultHeaders).document
        return document.select("a.group").mapNotNull { it.toSearchResult() }
    }

    // ==================== EKSTRAKSI EPISODE ====================
    private fun extractEpisodeNumber(title: String): Int? {
        title.toIntOrNull()?.let { if (it > 0) return it }
        val patterns = listOf(
            Regex("""(?i)Episode\s*(\d+)"""),
            Regex("""(?i)EP\s*(\d+)"""),
            Regex("""(?i)E(\d+)"""),
            Regex("""#(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null && num > 0) return num
            }
        }
        return null
    }

    // ==================== LOAD DETAIL ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.text-3xl, h1.text-4xl, h1")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst("img.wp-post-image, img.attachment-full")?.attr("src")?.let { fixUrl(it) }
        val description = document.select("div.text-secondary.leading-relaxed p")
            .joinToString("\n\n") { it.text().trim() }
            .ifEmpty { document.select(".text-secondary.leading-relaxed").text() }

        val episodeElements = document.select("nav[aria-label='Episode Navigation'] a")
        val episodes = episodeElements.mapNotNull { el ->
            val epNumText = el.text().trim()
            val epNum = epNumText.toIntOrNull() ?: extractEpisodeNumber(epNumText)
            if (epNum == null) return@mapNotNull null
            
            val epLink = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epTitle = "Episode $epNum"
            Triple(epTitle, epLink, epNum)
        }.sortedBy { it.third }

        val hasVideo = document.selectFirst("iframe, video") != null

        // LOGIKA COMING SOON: Jika tidak ada daftar episode DAN tidak ada video/iframe
        if (episodes.isEmpty() && !hasVideo) {
            val comingSoonEp = newEpisode(url) {
                this.name = "Coming Soon"
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(comingSoonEp)) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }

        // LOGIKA SERIES BIASA
        if (episodes.isNotEmpty()) {
            val episodeList = episodes.map { (epTitle, epLink, _) ->
                newEpisode(epTitle) { this.data = epLink }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }

        // LOGIKA MOVIE
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val doc = app.get(data, headers = defaultHeaders).document

        var anySuccess = false

        // 1. Ambil URL Iframe dari halaman Dramika (yang mengarah ke KissKH)
        val iframeSrc = doc.selectFirst("iframe")?.attr("src")
        
        if (!iframeSrc.isNullOrBlank()) {
            val embedUrl = fixUrl(iframeSrc)
            
            try {
                // 2. Minta Cloudstream membuka halaman embed KissKH di balik layar
                val embedDoc = app.get(embedUrl, headers = defaultHeaders).document
                
                // 3. Sekarang kita cari daftar server (Vidmoly, Streamtape, dll) di dalam KissKH
                val serverItems = embedDoc.select("#serverList li.server-item.linkserver")
                
                if (serverItems.isNotEmpty()) {
                    for (item in serverItems) {
                        val videoUrl = item.attr("data-video")
                        if (videoUrl.isNotBlank()) {
                            // 4. Lempar URL server tersebut ke Extractor bawaan Cloudstream
                            if (loadExtractor(fixUrl(videoUrl), subtitleCallback, callback)) {
                                anySuccess = true
                            }
                        }
                    }
                } else {
                    // Fallback jika #serverList gagal dimuat, cari iframe terdalam
                    val deepIframe = embedDoc.selectFirst("iframe#embedvideo, iframe")?.attr("src")
                    if (!deepIframe.isNullOrBlank()) {
                        if (loadExtractor(fixUrl(deepIframe), subtitleCallback, callback)) {
                            anySuccess = true
                        }
                    }
                }
            } catch (e: Exception) {
                // Abaikan exception agar tidak memutus pencarian link berikutnya
            }
        }

        // Fallback jika video disematkan langsung di halaman Dramika menggunakan <video> (meski jarang)
        if (!anySuccess) {
            val videoSrc = doc.selectFirst("video source")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                val cleanUrl = fixUrl(videoSrc)
                val isM3u8 = cleanUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        name = if (isM3u8) "Dramika - HLS" else "Dramika - Direct",
                        source = name,
                        url = cleanUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                anySuccess = true
            }
        }

        return anySuccess
    }

    // ==================== FUNGSI BANTU ====================
    private fun fixUrl(url: String): String {
        var fixed = url.trim()
        if (fixed.startsWith("//")) fixed = "https:$fixed"
        return fixed
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }
}
