package com.myasiantv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import org.jsoup.nodes.Element

class MyAsianTv : MainAPI() {
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://myasiantv.com.bz"
    override var name = "MyAsianTv"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultHeaders = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)

    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/drama/" to "Drama",
        "/drama/?selCountry=Japanese&btnFilter=Submit" to "Drama Jepang",
        "/drama/?selCountry=Thailand&btnFilter=Submit" to "Drama Thailand",
        "/movies-list/" to "Movies",
        "/movies-list/?selCountry=Japanese&btnFilter=Submit" to "Movie Jepang",
        "/movies-list/?selCountry=Thailand&btnFilter=Submit" to "Movie Thailand",
        "/shows/" to "TV Shows"
    )

    // ==================== HELPER EXTENSIONS ====================
    private fun String.cleanTitle(): String {
        return this.replace(Regex("""(?i)\s*-\s*Myasiantv\s*$"""), "").trim()
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

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val basePath = request.data
        val url = if (page == 1) {
            "${mainUrl}${basePath}"
        } else {
            if (basePath.contains("?")) {
                val parts = basePath.split("?", limit = 2)
                val path = parts[0]
                val query = if (parts.size > 1) parts[1] else ""
                "${mainUrl}${path}page/$page/?$query"
            } else {
                "${mainUrl}${basePath}page/$page/"
            }
        }

        val document = app.get(url, headers = defaultHeaders).document

        val items = when {
            request.data == "/" -> {
                document.select("ul.items li")
                    .mapNotNull { it.toSeriesFromEpisode() }
                    .distinctBy { it.url }
            }
            else -> {
                document.select("ul.items li").mapNotNull { it.toSearchResult() }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSeriesFromEpisode(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val href = link.attr("href")
        val episodeHref = fixUrlNull(href) ?: return null

        val path = if (episodeHref.startsWith(mainUrl)) {
            episodeHref.replace(mainUrl, "").trimStart('/')
        } else {
            episodeHref.trimStart('/')
        }

        val seriesPath = path.replace(Regex("""-ep-\d+-eng-sub/?$"""), "").trimEnd('/')
        val seriesUrl = "$mainUrl/series/$seriesPath"

        val img = selectFirst("img")
        var title = img?.attr("alt")?.replace("Poster for ", "")?.cleanTitle()
        if (title.isNullOrEmpty()) {
            title = link.text().cleanTitle()
        }
        if (title.isNullOrEmpty()) return null

        var posterUrl = img?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = img?.attr("src")
        posterUrl = fixUrlNull(posterUrl)

        return newAnimeSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = defaultHeaders
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val img = selectFirst("img")
        var title = img?.attr("alt")?.replace("Poster for ", "")?.cleanTitle()
        if (title.isNullOrEmpty()) {
            title = selectFirst("h2")?.text()?.cleanTitle()
        }
        if (title.isNullOrEmpty()) return null

        var posterUrl = img?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = img?.attr("src")
        posterUrl = fixUrlNull(posterUrl)

        val isMovie = href.contains("/movies/") || title.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.posterHeaders = defaultHeaders
        }
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = defaultHeaders).document
        return document.select("ul.items li").mapNotNull { it.toSearchResult() }
    }

    // ==================== EXTRACT EPISODE NUMBER ====================
    private fun extractEpisodeNumberFromLink(link: String): Int? {
        val pattern = Regex("""-ep-(\d+)-eng-sub""")
        val match = pattern.find(link)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumberFromTitle(title: String): Int? {
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

    // ==================== LOAD DETAIL (SERIES / MOVIE) ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("div.movie h1")?.text()?.cleanTitle() ?: return null

        var posterUrl = document.selectFirst("img.poster, img.wp-post-image")?.attr("src")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("img.poster, img.wp-post-image")?.attr("data-src")
        }
        val cleanPosterUrl = fixUrlNull(posterUrl)

        val description = document.select("div.info").text().trim()
            .ifEmpty { document.select("div.text-secondary.leading-relaxed").text() }

        val episodeElements = document.select("ul.list-episode li")
        val episodes = episodeElements.mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            val epLink = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val epTitle = link.text().trim()
            val epNum = extractEpisodeNumberFromLink(epLink) ?: extractEpisodeNumberFromTitle(epTitle)
            if (epNum == null) return@mapNotNull null
            Triple(epTitle, epLink, epNum)
        }.sortedBy { it.third }

        if (episodes.size > 1) {
            val episodeList = episodes.map { (epTitle, epLink, _) ->
                newEpisode(epTitle) { this.data = epLink }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = cleanPosterUrl
                this.posterHeaders = defaultHeaders
                this.plot = description
            }
        }

        if (episodes.size == 1) {
            val (epTitle, epLink, _) = episodes.first()
            val isMovie = title.contains("Movie", ignoreCase = true) ||
                    !epTitle.contains(Regex("""(?i)\b(ep|episode|e)\b"""))

            if (isMovie) {
                return newMovieLoadResponse(title, url, TvType.Movie, epLink) {
                    this.posterUrl = cleanPosterUrl
                    this.posterHeaders = defaultHeaders
                    this.plot = description
                }
            } else {
                val episodeList = listOf(newEpisode(epTitle) { this.data = epLink })
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                    this.posterUrl = cleanPosterUrl
                    this.posterHeaders = defaultHeaders
                    this.plot = description
                }
            }
        }

        val iframe = document.selectFirst("iframe#b, iframe")
        if (iframe != null) {
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank()) {
                return newMovieLoadResponse(title, url, TvType.Movie, src) {
                    this.posterUrl = cleanPosterUrl
                    this.posterHeaders = defaultHeaders
                    this.plot = description
                }
            }
        }

        return null
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        var finalUrl = data
        if (!data.startsWith("http")) {
            val doc = app.get("$mainUrl$data", headers = defaultHeaders).document
            val iframeElement = doc.selectFirst("iframe#b, iframe")
            var iframeSrc = iframeElement?.attr("data-src")
            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = iframeElement?.attr("src")
            }
            if (iframeSrc.isNullOrBlank()) return false
            finalUrl = fixUrl(iframeSrc)
        }

        if (finalUrl.contains("vidmoly", ignoreCase = true)) {
            return extractVidMoly(finalUrl, subtitleCallback, callback)
        }

        val embedDoc = app.get(finalUrl, headers = defaultHeaders).document
        var anySuccess = false

        val serverItems = embedDoc.select(".server-item, li[data-video]")
        if (serverItems.isNotEmpty()) {
            for (item in serverItems) {
                val videoUrl = item.attr("data-video")
                if (videoUrl.isNotBlank()) {
                    val cleanVideoUrl = fixUrl(videoUrl)
                    val serverName = item.text().trim().ifBlank { "Server" }

                    // PERBAIKAN: Cek apakah link server ini milik Vidmoly
                    if (cleanVideoUrl.contains("vidmoly", ignoreCase = true)) {
                        val vidmolyFound = extractVidMoly(cleanVideoUrl, subtitleCallback, callback)
                        if (vidmolyFound) anySuccess = true
                    } else {
                        val extractorFound = loadExtractor(cleanVideoUrl, subtitleCallback, callback)
                        val manualFound = manualExtractor(cleanVideoUrl, serverName, callback)
                        if (extractorFound || manualFound) anySuccess = true
                    }
                }
            }
        } else {
            var deepIframe = embedDoc.selectFirst("iframe")?.attr("data-src")
            if (deepIframe.isNullOrBlank()) deepIframe = embedDoc.selectFirst("iframe")?.attr("src")
            if (!deepIframe.isNullOrBlank()) {
                val cleanDeepIframe = fixUrl(deepIframe)
                
                // PERBAIKAN: Sama untuk deepIframe, pastikan lari ke extractVidMoly
                if (cleanDeepIframe.contains("vidmoly", ignoreCase = true)) {
                    val vidmolyFound = extractVidMoly(cleanDeepIframe, subtitleCallback, callback)
                    if (vidmolyFound) anySuccess = true
                } else {
                    val extractorFound = loadExtractor(cleanDeepIframe, subtitleCallback, callback)
                    val manualFound = manualExtractor(cleanDeepIframe, "Server", callback)
                    if (extractorFound || manualFound) anySuccess = true
                }
            }
        }

        if (!anySuccess) {
            val videoSrc = embedDoc.selectFirst("video source")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                val cleanUrl = fixUrl(videoSrc)
                val isM3u8 = cleanUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        name = if (isM3u8) "MyAsianTv - HLS" else "MyAsianTv - Direct",
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

    // ==================== VIDMOLY EXTRACTOR ====================
    private suspend fun extractVidMoly(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Log Debug Awal
        subtitleCallback.invoke(SubtitleFile("DBG: 1. Fungsi extractVidMoly dipanggil!", "https://localhost/d.vtt"))

        return try {
            val headers = mapOf(
                "user-agent" to userAgent,
                "Sec-Fetch-Dest" to "iframe"
            )

            val newUrl = if (url.contains("/w/")) 
                url.replaceFirst("/w/", "/embed-") + ".html" 
            else url

            val doc = app.get(newUrl, headers = headers, referer = mainUrl).document

            val scriptData = doc.select("script")
                .firstOrNull { it.data().contains("sources:") }
                ?.data()

            if (!scriptData.isNullOrBlank()) {
                // Ekstraksi Manual sebagai cadangan
                val tracksMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(scriptData)
                if (tracksMatch != null) {
                    val tracksHtml = tracksMatch.groupValues[1]
                    val individualTracks = Regex("""\{(.*?)\}""").findAll(tracksHtml)
                    
                    for (track in individualTracks) {
                        val trackData = track.groupValues[1]
                        val fileMatch = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""").find(trackData)
                        val labelMatch = Regex("""["']?label["']?\s*:\s*["']([^"']+)["']""").find(trackData)
                        
                        val file = fileMatch?.groupValues?.get(1)
                        val label = labelMatch?.groupValues?.get(1) ?: "Sub"
                        
                        if (file != null && !file.contains(".jpg") && !file.contains(".png")) {
                            // Mengirim subtitle hasil parsing mandiri
                            subtitleCallback.invoke(SubtitleFile("Auto: $label", file))
                        }
                    }
                }

                // Log Debug Tengah
                subtitleCallback.invoke(SubtitleFile("DBG: 2. JwPlayerHelper Berjalan", "https://localhost/d.vtt"))

                // Ekstraksi Resmi Cloudstream
                JwPlayerHelper.extractStreamLinks(
                    scriptData,
                    "VidMoly",
                    newUrl,
                    callback,
                    subtitleCallback
                )
                return true
            }

            // Fallback iframe
            val iframe = doc.selectFirst("iframe")
            if (iframe != null) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    return loadExtractor(fixUrl(src), subtitleCallback, callback)
                }
            }

            false
        } catch (e: Exception) {
            subtitleCallback.invoke(SubtitleFile("DBG: ERR! ${e.message?.take(20)}", "https://localhost/d.vtt"))
            false
        }
    }

    // ==================== MANUAL EXTRACTOR (BACKUP) ====================
    private suspend fun manualExtractor(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val playerHtml = app.get(url, headers = defaultHeaders).text

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
}
