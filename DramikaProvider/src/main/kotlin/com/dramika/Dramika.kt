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

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"
    
    // Header default untuk menembus proteksi image hotlinking
    private val defaultHeaders = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)

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
        if (title.isNullOrEmpty()) title = selectFirst("h2")?.text()?.trim()
        if (title.isNullOrEmpty()) return null

        val href = fixUrlNull(attr("href")) ?: return null
        val posterUrl = fixUrlNull(img?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
            // Sisipkan posterHeaders agar UI Cloudstream berhasil meload gambar
            this.posterHeaders = defaultHeaders
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

        // Gunakan selector spesifik ke blok navigasi episode untuk menghindari elemen share
        val episodeElements = document.select("nav[aria-label='Episode Navigation'] a")
        val episodes = episodeElements.mapNotNull { el ->
            val epNumText = el.text().trim()
            val epNum = epNumText.toIntOrNull() ?: extractEpisodeNumber(epNumText)
            if (epNum == null) return@mapNotNull null // Abaikan yang tidak valid tapi tolerir ep 0 jika ada

            val epLink = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epTitle = "Episode $epNum"
            Triple(epTitle, epLink, epNum)
        }.sortedBy { it.third } // Urutkan ascending 

        if (episodes.isNotEmpty()) {
            val episodeList = episodes.map { (epTitle, epLink, _) ->
                newEpisode(epTitle) { this.data = epLink }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = posterUrl
                this.posterHeaders = defaultHeaders
                this.plot = description
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.posterHeaders = defaultHeaders
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

        val serverItems = doc.select("#serverList li.server-item.linkserver")
        if (serverItems.isNotEmpty()) {
            var anySuccess = false
            for (item in serverItems) {
                val videoUrl = item.attr("data-video")
                if (videoUrl.isNotBlank()) {
                    if (loadExtractor(fixUrl(videoUrl), subtitleCallback, callback)) anySuccess = true
                }
            }
            if (anySuccess) return true
        }

        val iframeSrc = doc.selectFirst("iframe#embedvideo, iframe")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            var cleanUrl = fixUrl(iframeSrc)
            
            // Penanganan Double Iframe untuk menembus ke kisskh.space
            if (!cleanUrl.contains("kisskh.space", ignoreCase = true)) {
                try {
                    val iframeDoc = app.get(cleanUrl, headers = defaultHeaders).document
                    val realIframeSrc = iframeDoc.selectFirst("iframe")?.attr("src")
                    if (!realIframeSrc.isNullOrBlank()) {
                        cleanUrl = fixUrl(realIframeSrc)
                    }
                } catch (e: Exception) {
                    // Abaikan dan coba URL iframe pertama jika penelusuran middleman gagal
                }
            }
            
            return loadExtractor(cleanUrl, subtitleCallback, callback)
        }

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
            return true
        }
        return false
    }

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
        if (fixed.startsWith("//")) fixed = "https:$fixed"
        return fixed
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }
}
