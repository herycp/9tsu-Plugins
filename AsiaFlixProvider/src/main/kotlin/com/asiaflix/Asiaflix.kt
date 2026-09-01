package com.asiaflix

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
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
    
    private val tmdbToken = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3MmJhMTBjNDI5OTE0MTU3MzgwOGQyNzEwNGVkMThmYSIsInN1YiI6IjY0ZjVhNTUwMTIxOTdlMDBmZWE5MzdmMSIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.84b7vWpVEilAbly4RpS01E9tyirHdhSXjcpfmTczI3Q"

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()

            if (url.contains(".vtt") || url.contains("sub") || url.contains("track") || url.contains("kk.vidbasic.top")) {
                val rawBody = response.body?.string() ?: ""
                val decryptedBody = decryptVttText(rawBody)
                
                response.newBuilder()
                    .body(decryptedBody.toResponseBody(response.body?.contentType()))
                    .build()
            } else {
                response
            }
        }
    }

    private fun decryptVttText(vttText: String): String {
        val keyBytes = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
        val ivBytes = "5259228356829423".toByteArray(Charsets.UTF_8)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        return vttText.lines().joinToString("\n") { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("WEBVTT") || t.startsWith("NOTE") || Regex("""^\d+$""").matches(t) || Regex("""^\d{2}:\d{2}""").containsMatchIn(t)) {
                line
            } else {
                try {
                    var b64 = t.replace("-", "+").replace("_", "/").replace(Regex("""\s+"""), "")
                    while (b64.length % 4 != 0) {
                        b64 += "="
                    }

                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                    val decodedBytes = Base64.getDecoder().decode(b64)
                    val decryptedBytes = cipher.doFinal(decodedBytes)
                    
                    String(decryptedBytes, Charsets.UTF_8).trim()
                } catch (e: Exception) {
                    Log.e("AsiaflixDebug", "VTT Line decrypt failed for [$t]: ${e.message}")
                    line
                }
            }
        }
    }

    private fun extractEpNum(recentEp: Any?, episodes: Any?, status: String? = null): Int? {
        recentEp?.toString()?.let { str ->
            Regex("""\d+""").find(str)?.value?.toIntOrNull()?.let { return it }
        }
        when (episodes) {
            is Number -> if (episodes.toInt() > 0) return episodes.toInt()
            is List<*> -> if (episodes.isNotEmpty()) return episodes.size
            is String -> Regex("""\d+""").find(episodes)?.value?.toIntOrNull()?.let { return it }
        }
        status?.let { str ->
            Regex("""\d+""").find(str)?.value?.toIntOrNull()?.let { return it }
        }
        return null
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
        val isLatest = request.data == "latest"

        val url = if (isLatest) {
            "$mainUrl/drama/dynamic-fetch?page=$page&type=Latest%20Updates"
        } else {
            val country = URLEncoder.encode(request.data, "UTF-8")
            "$mainUrl/drama/list?country=$country&page=$page"
        }

        val responseText = app.get(url, headers = headers).text
        val response = tryParseJson<AsiaflixListResponse>(responseText)
        val hasNext = true

        response?.body?.forEach { item ->
            val itemId = item.id ?: return@forEach
            val title = item.name ?: "Unknown"
            val detailUrl = "$mainUrl/drama/detail?id=$itemId"
            val epNum = extractEpNum(item.recentEp, item.episodes, item.status)

            items.add(newAnimeSearchResponse(title, detailUrl, TvType.AsianDrama) {
                this.posterUrl = item.image
                addSub(epNum)
                if (!item.status.isNullOrBlank()) {
                    addQuality(item.status)
                }
            })
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        var results = emptyList<AsiaflixItem>()

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
            val responseText = app.get(url, headers = headers).text
            results = tryParseJson<AsiaflixListResponse>(responseText)?.body ?: emptyList()
        } else {
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
            val title = item.name ?: "Unknown"
            val epNum = extractEpNum(item.recentEp, item.episodes, item.status)

            newAnimeSearchResponse(title, detailUrl, TvType.AsianDrama) {
                this.posterUrl = item.image
                addSub(epNum)
                if (!item.status.isNullOrBlank()) {
                    addQuality(item.status)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val responseText = app.get(url, headers = headers).text
        val response = tryParseJson<AsiaflixDetail>(responseText) ?: return null

        val isMovie = response.showType?.contains("Movie", ignoreCase = true) == true || response.episodes?.size == 1
        
        var tmdbActors: List<ActorData>? = null
        var tmdbTrailer: String? = null
        var tmdbEpisodesMap: Map<Int, TmdbEpisode>? = null

        val tmdbId = response.tmdbId
        if (tmdbId != null && tmdbId > 0) {
            val tmdbHeaders = mapOf("Authorization" to "Bearer $tmdbToken")
            val tmdbType = if (isMovie) "movie" else "tv"
            
            try {
                val tmdbUrl = "https://api.themoviedb.org/3/$tmdbType/$tmdbId?append_to_response=credits,videos"
                val tmdbDetail = app.get(tmdbUrl, headers = tmdbHeaders).parsedSafe<TmdbDetail>()
                
                tmdbActors = tmdbDetail?.credits?.cast?.take(10)?.mapNotNull { cast ->
                    val name = cast.name ?: return@mapNotNull null
                    val image = cast.profile_path?.let { "https://image.tmdb.org/t/p/w185$it" }
                    ActorData(actor = Actor(name, image), roleString = cast.character)
                }
                
                tmdbTrailer = tmdbDetail?.videos?.results?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }?.key?.let { "https://www.youtube.com/watch?v=$it" }
                
                if (!isMovie) {
                    val seasonNum = response.seasonNumber ?: 1
                    val seasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$seasonNum"
                    val seasonData = app.get(seasonUrl, headers = tmdbHeaders).parsedSafe<TmdbSeason>()
                    tmdbEpisodesMap = seasonData?.episodes?.associateBy { it.episode_number ?: -1 }
                }
            } catch (e: Exception) { }
        }

        val episodes = response.episodes?.map { ep ->
            val linkData = EpisodeLinkData(
                tmdbId = response.tmdbId,
                seasonNumber = response.seasonNumber ?: 1,
                epNumber = ep.number ?: 1,
                showType = response.showType ?: if (isMovie) "Movie" else "TVSeries",
                streamUrls = ep.streamUrls
            )
            
            val epNum = ep.number ?: 1
            val tmdbEpMeta = tmdbEpisodesMap?.get(epNum)
            
            val epTitle = tmdbEpMeta?.name ?: ep.title ?: "Episode $epNum"
            val epPoster = tmdbEpMeta?.still_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            val epDesc = tmdbEpMeta?.overview

            newEpisode(epTitle) {
                this.data = linkData.toJson()
                this.episode = epNum
                this.posterUrl = epPoster
                this.description = epDesc
            }
        } ?: emptyList()

        val genreName = response.genres?.mapNotNull { it.name }?.firstOrNull { !it.equals("drama", ignoreCase = true) }
        val countryName = response.country

        val recommendations = try {
            var recText = ""
            if (!genreName.isNullOrBlank()) {
                val recUrl = "$mainUrl/drama/list?genre=${URLEncoder.encode(genreName, "UTF-8")}&limit=30"
                recText = app.get(recUrl, headers = headers).text
            }
            
            var recResponse = tryParseJson<AsiaflixListResponse>(recText)
            
            if (recResponse?.body.isNullOrEmpty() && !countryName.isNullOrBlank()) {
                val recUrl = "$mainUrl/drama/list?country=${URLEncoder.encode(countryName, "UTF-8")}&limit=30"
                recText = app.get(recUrl, headers = headers).text
                recResponse = tryParseJson<AsiaflixListResponse>(recText)
            }

            recResponse?.body?.filter { item ->
                item.id != null && item.id != response.id && !item.name.equals(response.name, ignoreCase = true)
            }?.distinctBy { it.id }?.take(15)?.mapNotNull { item ->
                val itemId = item.id ?: return@mapNotNull null
                val itemTitle = item.name ?: "Unknown"
                val itemDetailUrl = "$mainUrl/drama/detail?id=$itemId"
                val epNum = extractEpNum(item.recentEp, item.episodes, item.status)

                newAnimeSearchResponse(itemTitle, itemDetailUrl, TvType.AsianDrama) {
                    this.posterUrl = item.image
                    addSub(epNum)
                    if (!item.status.isNullOrBlank()) {
                        addQuality(item.status)
                    }
                }
            }
        } catch (e: Exception) { null }

        return if (isMovie) {
            newMovieLoadResponse(response.name ?: "", url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = response.image
                this.plot = response.description
                this.year = response.releaseYear?.toString()?.toIntOrNull()
                this.tags = response.genres?.mapNotNull { it.name }
                this.recommendations = recommendations
                this.actors = tmdbActors
                addTrailer(tmdbTrailer)
            }
        } else {
            newTvSeriesLoadResponse(response.name ?: "", url, TvType.TvSeries, episodes) {
                this.posterUrl = response.image
                this.plot = response.description
                this.year = response.releaseYear?.toString()?.toIntOrNull()
                this.tags = response.genres?.mapNotNull { it.name }
                this.recommendations = recommendations
                this.actors = tmdbActors
                addTrailer(tmdbTrailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val linkData = tryParseJson<EpisodeLinkData>(data) ?: return false
        val anySuccess = AtomicBoolean(false)
        val providerName = this.name

        coroutineScope {
            val jobs = mutableListOf<Job>()

            // ------------------------------------------------------------------
            // URUTAN 1: Ekstraktor Konvensional (VidBasic, VidMoly, loadExtractor)
            // ------------------------------------------------------------------
            linkData.streamUrls?.forEach { stream ->
                var streamUrl = stream.url ?: return@forEach
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"

                jobs.add(launch {
                    if (streamUrl.contains("vidbasic.top") || streamUrl.contains("vidb.top")) {
                        if (processVidBasic(streamUrl, subtitleCallback, callback)) anySuccess.set(true)
                    }
                    if (streamUrl.contains("vidmoly", ignoreCase = true)) {
                        if (extractVidMoly(streamUrl, callback)) anySuccess.set(true)
                    }
                    if (loadExtractor(streamUrl, subtitleCallback, callback)) anySuccess.set(true)
                })
            }

            // ------------------------------------------------------------------
            // URUTAN 2: API HLS Proxy Asiaflix (Timeout 10s & Non-blocking Async)
            // ------------------------------------------------------------------
            linkData.streamUrls?.forEach { stream ->
                var streamUrl = stream.url ?: return@forEach
                if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"
                val serverName = stream.source ?: "unknown"

                jobs.add(launch {
                    try {
                        val base64Url = Base64.getEncoder().encodeToString(streamUrl.toByteArray(Charsets.UTF_8))
                        val encodedUrl = URLEncoder.encode(base64Url, "UTF-8")
                        val encodedServer = URLEncoder.encode(serverName, "UTF-8")
                        
                        val proxyApiUrl = "$mainUrl/drama/get-stream-url?value=$encodedUrl&server=$encodedServer"
                        
                        val reqHeaders = mapOf(
                            "accept" to "application/json, text/plain, */*",
                            "origin" to "https://asiaflix.net",
                            "referer" to "https://asiaflix.net/drama/",
                            "user-agent" to userAgent,
                            "x-access-control" to "web"
                        )

                        Log.d("AsiaflixDebug", "Hit HLS Proxy API | Server: $serverName | URL: $proxyApiUrl")
                        
                        val apiResponseText = app.get(proxyApiUrl, headers = reqHeaders, timeout = 10).text
                        Log.d("AsiaflixDebug", "Respon HLS Proxy API: $apiResponseText")
                        
                        val proxyUrlMatch = Regex(""""(?:url|link|data)"\s*:\s*"([^"]+hlsproxy[^"]+)"""").find(apiResponseText) 
                            ?: Regex(""""(https://[^"]*hlsproxy[^"]*)"""").find(apiResponseText)
                        
                        val proxyUrl = tryParseJson<AsiaflixStreamResponse>(apiResponseText)?.url ?: proxyUrlMatch?.groupValues?.get(1)

                        if (!proxyUrl.isNullOrEmpty()) {
                            Log.d("AsiaflixDebug", "BERHASIL: Direct HLS Proxy terdeteksi -> $proxyUrl")
                            
                            callback(newExtractorLink(
                                name = "Asiaflix Proxy - $serverName",
                                source = providerName,
                                url = proxyUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = reqHeaders
                                this.referer = "https://asiaflix.net/"
                            })
                            anySuccess.set(true)
                        } else {
                            Log.d("AsiaflixDebug", "GAGAL: URL Proxy HLS tidak ditemukan dalam respon JSON.")
                        }
                    } catch (e: Exception) {
                        Log.e("AsiaflixDebug", "Error HLS Proxy API: ${e.message}")
                    }
                })
            }

            // ------------------------------------------------------------------
            // URUTAN 3: Fallback TMDB Embed Links (Eksekusi Non-blocking Async)
            // ------------------------------------------------------------------
            val tmdbId = linkData.tmdbId
            if (tmdbId != null && tmdbId > 0) {
                val isTv = linkData.showType == "TVSeries"
                val season = linkData.seasonNumber ?: 1
                val ep = linkData.epNumber

                val generatedUrls = if (isTv) {
                    listOf(
                        "https://peachify.top/embed/tv/$tmdbId/$season/$ep?accent=1DB954&sub=English",
                        "https://vidup.to/tv/$tmdbId/$season/$ep?theme=1DB954&autoPlay=true&sub=en",
                        "https://player.videasy.to/tv/$tmdbId/$season/$ep?nextEpisode=false",
                        "https://player.cinezo.live/embed/tv/$tmdbId/$season/$ep?autoplay=true"
                    )
                } else {
                    listOf(
                        "https://peachify.top/embed/movie/$tmdbId?accent=1DB954&sub=English",
                        "https://vidup.to/movie/$tmdbId?theme=1DB954&autoPlay=true&sub=en",
                        "https://player.videasy.to/movie/$tmdbId?nextEpisode=false",
                        "https://player.cinezo.live/embed/movie/$tmdbId?autoplay=true"
                    )
                }

                generatedUrls.forEach { embedUrl ->
                    jobs.add(launch {
                        if (loadExtractor(embedUrl, subtitleCallback, callback)) anySuccess.set(true)
                    })
                }
            }

            jobs.joinAll()
        }

        return anySuccess.get()
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
                callback(newExtractorLink("VidMoly", this.name, videoUrl, ExtractorLinkType.M3U8))
                true
            } else false
        } catch (e: Exception) { false }
    }

    private suspend fun processVidBasic(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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
                        val isM3u8 = decrypted.contains(".m3u8")
                        
                        callback(newExtractorLink(
                            name = if (isM3u8) "VidBasic - HLS" else "VidBasic - Direct",
                            source = this.name,
                            url = decrypted,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) { this.referer = fullUrl; this.headers = headersMap })

                        val subParam = Regex("""[?&]sub=([^&]+)""").find(fullUrl)?.groupValues?.get(1)
                        if (!subParam.isNullOrEmpty()) {
                            try {
                                val decodedSubParam = URLDecoder.decode(subParam, "UTF-8")
                                val subUrl = decryptVidBasic(decodedSubParam)
                                
                                if (subUrl.startsWith("http")) {
                                    subtitleCallback.invoke(
                                        newSubtitleFile(
                                            lang = "English",
                                            url = subUrl
                                        )
                                    )
                                    Log.d("AsiaflixDebug", "VidBasic subtitle URL registered for interceptor: $subUrl")
                                }
                            } catch (e: Exception) {
                                Log.e("AsiaflixDebug", "VidBasic Subtitle Error: ${e.message}")
                            }
                        }
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) { 
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
    data class AsiaflixItem(@JsonProperty("_id") val id: String? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("image") val image: String? = null, @JsonProperty("status") val status: String? = null, @JsonProperty("country") val country: String? = null, @JsonProperty("recentEp") val recentEp: Any? = null, @JsonProperty("releaseYear") val releaseYear: Any? = null, @JsonProperty("genres") val genres: List<Any>? = null, @JsonProperty("episodes") val episodes: Any? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixDetail(@JsonProperty("_id") val id: String? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("description") val description: String? = null, @JsonProperty("image") val image: String? = null, @JsonProperty("showType") val showType: String? = null, @JsonProperty("status") val status: String? = null, @JsonProperty("country") val country: String? = null, @JsonProperty("releaseYear") val releaseYear: Any? = null, @JsonProperty("tmdbId") val tmdbId: Long? = null, @JsonProperty("seasonNumber") val seasonNumber: Int? = null, @JsonProperty("genres") val genres: List<AsiaflixGenre>? = null, @JsonProperty("episodes") val episodes: List<AsiaflixEpisode>? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixGenre(@JsonProperty("name") val name: String? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixEpisode(@JsonProperty("title") val title: String? = null, @JsonProperty("number") val number: Int? = null, @JsonProperty("streamUrls") val streamUrls: List<AsiaflixStream>? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixStream(@JsonProperty("source") val source: String? = null, @JsonProperty("url") val url: String? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AsiaflixStreamResponse(@JsonProperty("url") val url: String? = null)

    data class EpisodeLinkData(val tmdbId: Long?, val seasonNumber: Int?, val epNumber: Int, val showType: String?, val streamUrls: List<AsiaflixStream>?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbDetail(@JsonProperty("credits") val credits: TmdbCredits? = null, @JsonProperty("videos") val videos: TmdbVideos? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profile_path: String? = null
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbVideos(@JsonProperty("results") val results: List<TmdbVideoResult>? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbVideoResult(@JsonProperty("key") val key: String? = null, @JsonProperty("site") val site: String? = null, @JsonProperty("type") val type: String? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSeason(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbEpisode(@JsonProperty("episode_number") val episode_number: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("still_path") val still_path: String? = null)
}