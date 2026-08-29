package com.asiaflix

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Asiaflix : MainAPI() {
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)
    override var lang = "en"
    override var mainUrl = "https://api.asiaflix.net/v1"
    override var name = "Asiaflix"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent, "x-access-control" to "web")

    private fun isValidSubtitle(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.startsWith("http") && !lower.endsWith(".jpg") && !lower.endsWith(".png") && !lower.endsWith(".mp4") && !lower.endsWith(".m3u8")
    }

    override val mainPage = mainPageOf(
        "latest" to "Latest Updates",
        "Japan" to "Drama Jepang",
        "China" to "Drama China",
        "Thailand" to "Drama Thailand",
        "South Korea" to "Drama Korea"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val url = if (request.data == "latest") {
            "$mainUrl/drama/dynamic-fetch?page=$page&type=Latest%20Updates"
        } else {
            val country = URLEncoder.encode(request.data, "UTF-8")
            "$mainUrl/drama/list?country=$country&page=$page"
        }

        Log.d("AsiaflixDebug", "Fetching MainPage URL: $url")
        val responseText = app.get(url, headers = headers).text
        val response = tryParseJson<AsiaflixListResponse>(responseText)
        
        val hasNext = response?.hasNext ?: false

        response?.body?.forEach { item ->
            val itemId = item.id ?: return@forEach
            val title = item.name ?: "Unknown"
            val detailUrl = "$mainUrl/drama/detail?id=$itemId"

            val badgeText = if (request.data == "latest") {
                item.recentEp?.toString()?.let { "EP $it" } ?: item.status ?: ""
            } else {
                val status = item.status ?: ""
                val year = item.releaseYear?.toString() ?: ""
                listOf(status, year).filter { it.isNotBlank() }.joinToString(" | ")
            }

            // Menyematkan label langsung ke judul agar pasti terlihat di UI
            val displayTitle = if (badgeText.isNotBlank()) "$title [$badgeText]" else title

            items.add(newAnimeSearchResponse(displayTitle, detailUrl, TvType.AsianDrama) {
                this.posterUrl = item.image
            })
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        Log.d("AsiaflixDebug", "Search Query: $cleanQuery")
        var results = emptyList<AsiaflixItem>()

        // 1. Pencarian menggunakan API List jika hanya berisi format parameter (diawali # tanpa text sebelumnya)
        if (cleanQuery.startsWith("#")) {
            val tagString = cleanQuery.removePrefix("#").trim()
            val queryParams = mutableListOf("limit=50")
            
            tagString.split(",").forEach { tag ->
                val kv = tag.split(":")
                if (kv.size == 2) {
                    val key = kv[0].trim()
                    val value = URLEncoder.encode(kv[1].trim(), "UTF-8")
                    queryParams.add("$key=$value")
                }
            }
            
            val url = "$mainUrl/drama/list?${queryParams.joinToString("&")}"
            Log.d("AsiaflixDebug", "Search Route -> API List: $url")
            val responseText = app.get(url, headers = headers).text
            results = tryParseJson<AsiaflixListResponse>(responseText)?.body ?: emptyList()
        } 
        // 2. Pencarian menggunakan API Search standar dengan filter
        else {
            var searchQuery = cleanQuery
            val tagFilters = mutableMapOf<String, String>()

            if (searchQuery.contains("#")) {
                val parts = searchQuery.split("#", limit = 2)
                searchQuery = parts[0].trim()
                parts.getOrNull(1)?.trim()?.split(",")?.forEach { tag ->
                    val kv = tag.split(":")
                    if (kv.size == 2) tagFilters[kv[0].trim().lowercase()] = kv[1].trim().lowercase()
                }
            }

            val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
            val projections = URLEncoder.encode("""["releaseYear","status","casts","episodes","genres","country","showType","description"]""", "UTF-8")
            val url = "$mainUrl/drama/search?q=$encodedQuery&page=1&projections=$projections"

            Log.d("AsiaflixDebug", "Search Route -> API Search: $url")
            val responseText = app.get(url, headers = headers).text
            val baseResults = tryParseJson<AsiaflixListResponse>(responseText)?.body ?: emptyList()
            
            results = if (tagFilters.isNotEmpty()) {
                baseResults.filter { item ->
                    var matches = true
                    tagFilters.forEach { (key, value) ->
                        when (key) {
                            "country" -> if (item.country?.lowercase()?.contains(value) != true) matches = false
                            "status" -> if (item.status?.lowercase() != value) matches = false
                            "year" -> if (item.releaseYear?.toString()?.lowercase() != value) matches = false
                            "genre", "genres" -> {
                                val genreList = item.genres?.map { g -> if (g is Map<*, *>) g["name"]?.toString()?.lowercase() ?: "" else g.toString().lowercase() } ?: emptyList()
                                if (genreList.none { it.contains(value) }) matches = false
                            }
                        }
                    }
                    matches
                }
            } else baseResults
        }

        return results.mapNotNull { item ->
            val itemId = item.id ?: return@mapNotNull null
            val detailUrl = "$mainUrl/drama/detail?id=$itemId"
            val epCount = item.recentEp?.toString()?.toIntOrNull() ?: item.episodes?.size ?: 0
            val badgeText = "${if (epCount > 0) "$epCount Ep | " else ""}${item.status ?: ""}"
            
            val displayTitle = if (badgeText.isNotBlank()) "${item.name} [$badgeText]" else item.name ?: "Unknown"

            newAnimeSearchResponse(displayTitle, detailUrl, TvType.AsianDrama) {
                this.posterUrl = item.image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("AsiaflixDebug", "Loading detail: $url")
        val responseText = app.get(url, headers = headers).text
        val response = tryParseJson<AsiaflixDetail>(responseText) ?: return null

        val isMovie = response.showType?.contains("Movie", ignoreCase = true) == true || response.episodes?.size == 1

        val episodes = response.episodes?.map { ep ->
            val linkData = EpisodeLinkData(
                tmdbId = response.tmdbId,
                seasonNumber = response.seasonNumber ?: 1,
                epNumber = ep.number ?: 1,
                showType = response.showType ?: if (isMovie) "Movie" else "TVSeries",
                streamUrls = ep.streamUrls
            )

            newEpisode(ep.title ?: "Episode ${ep.number}") {
                this.data = linkData.toJson()
                this.episode = ep.number
            }
        } ?: emptyList()

        return if (isMovie) {
            newMovieLoadResponse(response.name ?: "", url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = response.image
                this.plot = response.description
                this.year = response.releaseYear?.toString()?.toIntOrNull()
                this.tags = response.genres?.mapNotNull { it.name }
            }
        } else {
            newTvSeriesLoadResponse(response.name ?: "", url, TvType.TvSeries, episodes) {
                this.posterUrl = response.image
                this.plot = response.description
                this.year = response.releaseYear?.toString()?.toIntOrNull()
                this.tags = response.genres?.mapNotNull { it.name }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("AsiaflixDebug", "Loading Links from Data...")
        if (data.isBlank()) return false
        val linkData = tryParseJson<EpisodeLinkData>(data) ?: return false
        var anySuccess = false

        linkData.streamUrls?.forEach { stream ->
            var streamUrl = stream.url ?: return@forEach
            if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"
            Log.d("AsiaflixDebug", "Processing Direct Stream URL: $streamUrl")

            when {
                streamUrl.contains("vidbasic.top") || streamUrl.contains("vidb.top") -> {
                    if (processVidBasic(streamUrl, callback)) anySuccess = true
                }
                streamUrl.contains("vidmoly", ignoreCase = true) -> {
                    if (extractVidMoly(streamUrl, callback)) anySuccess = true
                }
                else -> {
                    if (loadExtractor(streamUrl, subtitleCallback, callback)) anySuccess = true
                }
            }
        }

        val tmdbId = linkData.tmdbId
        if (tmdbId != null && tmdbId > 0) {
            Log.d("AsiaflixDebug", "TMDB ID found: $tmdbId. Generating Embed URLs...")
            val isTv = linkData.showType == "TVSeries"
            val season = linkData.seasonNumber ?: 1
            val ep = linkData.epNumber

            val generatedUrls = if (isTv) {
                listOf(
                    "https://peachify.top/embed/tv/$tmdbId/$season/$ep?accent=1DB954&sub=English",
                    "https://vidup.to/tv/$tmdbId/$season/$ep?theme=1DB954&autoPlay=true&sub=en",
                    "https://player.videasy.net/tv/$tmdbId/$season/$ep?nextEpisode=false",
                    "https://player.cinezo.live/embed/tv/$tmdbId/$season/$ep?autoplay=true"
                )
            } else {
                listOf(
                    "https://peachify.top/embed/movie/$tmdbId?accent=1DB954&sub=English",
                    "https://vidup.to/movie/$tmdbId?theme=1DB954&autoPlay=true&sub=en",
                    "https://player.videasy.net/movie/$tmdbId?nextEpisode=false",
                    "https://player.cinezo.live/embed/movie/$tmdbId?autoplay=true"
                )
            }

            generatedUrls.forEach { embedUrl ->
                Log.d("AsiaflixDebug", "Invoking External Extractor: $embedUrl")
                if (loadExtractor(embedUrl, subtitleCallback, callback)) {
                    anySuccess = true
                }
            }
        }

        return anySuccess
    }

    private suspend fun extractVidMoly(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
            var videoUrl: String? = null

            for (script in doc.select("script")) {
                val html = script.html()
                val match = Regex("""(?:file|src|hls|videoUrl|url)"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(html)
                    ?: Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)
                if (match != null) {
                    videoUrl = match.groupValues[1]
                    break
                }
            }
            if (videoUrl != null) {
                Log.d("AsiaflixDebug", "VidMoly link found: $videoUrl")
                // Ganti source ke VidMoly
                callback(newExtractorLink("VidMoly", "VidMoly", videoUrl, ExtractorLinkType.M3U8))
                true
            } else false
        } catch (e: Exception) { 
            Log.e("AsiaflixDebug", "VidMoly Error: ${e.message}")
            false 
        }
    }

    private suspend fun processVidBasic(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val host = java.net.URL(embedUrl).host
            val headersMap = mapOf("User-Agent" to userAgent, "Referer" to "https://$host/", "Origin" to "https://$host")
            val html = app.get(embedUrl, headers = headersMap).text
            val dataVideo = Regex("""data-video="([^"]+)">Standard""").find(html)?.groupValues?.get(1)

            if (!dataVideo.isNullOrEmpty()) {
                val fullUrl = if (dataVideo.startsWith("http")) dataVideo else "https://$host$dataVideo"
                val html2 = app.get(fullUrl, headers = headersMap).text
                val encrypted = Regex("""data-name="crypto"\s*data-value="([^"]+)"""").find(html2)?.groupValues?.get(1)

                if (!encrypted.isNullOrEmpty()) {
                    val decrypted = decryptVidBasic(encrypted)
                    if (decrypted.startsWith("http")) {
                        Log.d("AsiaflixDebug", "VidBasic link decrypted: $decrypted")
                        val isM3u8 = decrypted.contains(".m3u8")
                        // Ganti source ke VidBasic
                        callback(newExtractorLink(
                            if (isM3u8) "VidBasic - HLS" else "VidBasic - Direct",
                            "VidBasic", decrypted,
                            if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) { this.referer = fullUrl; this.headers = headersMap })
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) { 
            Log.e("AsiaflixDebug", "VidBasic Error: ${e.message}")
            false 
        }
    }

    private fun decryptVidBasic(encrypted: String): String {
        val keyBytes = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
        val ivBytes = "5259228356829423".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return String(cipher.doFinal(Base64.getMimeDecoder().decode(encrypted.trim().replace(Regex("\\s+"), ""))), Charsets.UTF_8)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixListResponse(@JsonProperty("hasNext") val hasNext: Boolean? = null, @JsonProperty("body") val body: List<AsiaflixItem>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixItem(@JsonProperty("_id") val id: String? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("image") val image: String? = null, @JsonProperty("status") val status: String? = null, @JsonProperty("country") val country: String? = null, @JsonProperty("recentEp") val recentEp: Any? = null, @JsonProperty("releaseYear") val releaseYear: Any? = null, @JsonProperty("genres") val genres: List<Any>? = null, @JsonProperty("episodes") val episodes: List<Any>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixDetail(@JsonProperty("_id") val id: String? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("description") val description: String? = null, @JsonProperty("image") val image: String? = null, @JsonProperty("showType") val showType: String? = null, @JsonProperty("status") val status: String? = null, @JsonProperty("releaseYear") val releaseYear: Any? = null, @JsonProperty("tmdbId") val tmdbId: Long? = null, @JsonProperty("seasonNumber") val seasonNumber: Int? = null, @JsonProperty("genres") val genres: List<AsiaflixGenre>? = null, @JsonProperty("episodes") val episodes: List<AsiaflixEpisode>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixGenre(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixEpisode(@JsonProperty("title") val title: String? = null, @JsonProperty("number") val number: Int? = null, @JsonProperty("streamUrls") val streamUrls: List<AsiaflixStream>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixStream(@JsonProperty("source") val source: String? = null, @JsonProperty("url") val url: String? = null)
    data class EpisodeLinkData(val tmdbId: Long?, val seasonNumber: Int?, val epNumber: Int, val showType: String?, val streamUrls: List<AsiaflixStream>?)
}