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

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
        if (title.isNullOrEmpty()) {
            title = selectFirst("h2")?.text()?.trim()
        }
        if (title.isNullOrEmpty()) return null

        val href = fixUrlNull(attr("href")) ?: return null
        
        var posterUrl = img?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = img?.attr("src")
        posterUrl = fixUrlNull(posterUrl)

        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
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
        
        var rawPoster = document.selectFirst("img.wp-post-image, img.attachment-full")?.attr("data-src")
        if (rawPoster.isNullOrBlank()) rawPoster = document.selectFirst("img.wp-post-image, img.attachment-full")?.attr("src")
        val cleanPosterUrl = fixUrlNull(rawPoster)

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

        // MENGGUNAKAN emptyList() AGAR UI MEMUNCULKAN "Episode Tidak Ditemukan" & "Segera hadir..."
        if (episodes.isEmpty() && !hasVideo) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = cleanPosterUrl
                this.posterHeaders = defaultHeaders
                this.plot = description
            }
        }

        if (episodes.isNotEmpty()) {
            val episodeList = episodes.map { (epTitle, epLink, _) ->
                newEpisode(epTitle) { this.data = epLink }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = cleanPosterUrl
                this.posterHeaders = defaultHeaders
                this.plot = description
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = cleanPosterUrl
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

        var anySuccess = false
        
        val iframeElement = doc.selectFirst("iframe#b, iframe")
        var iframeSrc = iframeElement?.attr("data-src")
        if (iframeSrc.isNullOrBlank()) {
            iframeSrc = iframeElement?.attr("src")
        }
        
        if (!iframeSrc.isNullOrBlank()) {
            val embedUrl = fixUrl(iframeSrc)
            try {
                val embedDoc = app.get(embedUrl, headers = defaultHeaders).document
                val serverItems = embedDoc.select(".server-item, li[data-video]")
                
                if (serverItems.isNotEmpty()) {
                    for (item in serverItems) {
                        val videoUrl = item.attr("data-video")
                        if (videoUrl.isNotBlank()) {
                            val cleanVideoUrl = fixUrl(videoUrl)
                            val serverName = item.text().trim().ifBlank { "Server" }
                            
                            // 1. Coba extractor bawaan
                            val extractorFound = loadExtractor(cleanVideoUrl, subtitleCallback, callback)
                            
                            // 2. BACKUP SUPER AMAN: Paksa cari link .m3u8/.mp4 mandiri meskipun bawaan sukses (untuk jaga-jaga/redundansi)
                            val manualFound = manualExtractor(cleanVideoUrl, serverName, callback)
                            
                            if (extractorFound || manualFound) anySuccess = true
                        }
                    }
                } else {
                    var deepIframe = embedDoc.selectFirst("iframe")?.attr("data-src")
                    if (deepIframe.isNullOrBlank()) deepIframe = embedDoc.selectFirst("iframe")?.attr("src")
                    
                    if (!deepIframe.isNullOrBlank()) {
                        val cleanDeepIframe = fixUrl(deepIframe)
                        val extractorFound = loadExtractor(cleanDeepIframe, subtitleCallback, callback)
                        val manualFound = manualExtractor(cleanDeepIframe, "Server", callback)
                        if (extractorFound || manualFound) anySuccess = true
                    }
                }
            } catch (e: Exception) {
                // Abaikan jika error jaringan
            }
        }

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

    // ==================== MANUAL EXTRACTOR (BACKUP) ====================
    // Menyedot file video langsung dari kode HTML jika API Cloudstream gagal
    private suspend fun manualExtractor(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val playerHtml = app.get(url, headers = defaultHeaders).text
            
            // Regex mencari pola m3u8 atau mp4
            val m3u8Regex = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
            val mp4Regex = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
            
            val m3u8Match = m3u8Regex.find(playerHtml)
            if (m3u8Match != null) {
                callback(
                    newExtractorLink(
                        name = "$serverName (Manual HLS)",
                        source = name,
                        url = m3u8Match.groupValues[1],
                        type = ExtractorLinkType.M3U8
                    )
                )
                return true
            }
            
            val mp4Match = mp4Regex.find(playerHtml)
            if (mp4Match != null) {
                callback(
                    newExtractorLink(
                        name = "$serverName (Manual MP4)",
                        source = name,
                        url = mp4Match.groupValues[1],
                        type = ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
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
