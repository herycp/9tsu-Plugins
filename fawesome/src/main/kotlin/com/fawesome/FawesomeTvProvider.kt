package com.fawesome

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLDecoder

class FawesomeTvProvider : MainAPI() {
    override var name = "Fawesome TV"
    override var mainUrl = "https://fawesome.tv"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override var lang = "en"
    override var hasMainPage = true

    private val TAG = "FawesomeDebug"
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"
    private val baseApiUrl = "$mainUrl/home/new/v453/api"

    @Volatile
    private var cachedToken: String? = null
    private val tokenMutex = Mutex()

    @Volatile
    private var lastPref: String? = null

    private suspend fun ensureToken(): String? {
        cachedToken?.let { return it }

        return tokenMutex.withLock {
            cachedToken?.let { return@withLock it }

            val url = "$baseApiUrl/getSecurityToken.php?siteId=236&auth-token=1217575&country=US"
            val headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)

            try {
                val response = app.get(url, headers = headers)
                if (response.code in 200..299 && response.text.trim().startsWith("{")) {
                    val json = JSONObject(response.text)
                    val token = json.optString("securityToken").takeIf { it.isNotBlank() }
                        ?: json.optString("token").takeIf { it.isNotBlank() }

                    if (!token.isNullOrBlank()) {
                        cachedToken = token
                        Log.i(TAG, "SUCCESS: Security Token didapatkan -> $token")
                    } else {
                        Log.w(TAG, "WARN: Key securityToken tidak ditemukan dalam JSON")
                    }
                    token
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "EXCEPTION ensureToken(): ${e.localizedMessage}")
                null
            }
        }
    }

    private suspend fun apiRequest(
        endpoint: String,
        params: Map<String, String>,
        addToken: Boolean = true
    ): JSONObject? {
        val (cleanEndpoint, queryParams) = if (endpoint.contains("?")) {
            extractEndpointAndParams(endpoint)
        } else {
            endpoint to params.toMutableMap()
        }

        val allParams = mutableMapOf<String, String>().apply {
            putAll(queryParams)
            putAll(params)
            put("siteId", "236")
            put("country", "US")
        }

        val queryString = allParams.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

        val fullUrl = if (cleanEndpoint.startsWith("http")) {
            if (cleanEndpoint.contains("?")) cleanEndpoint else "$cleanEndpoint?$queryString"
        } else {
            "$baseApiUrl/$cleanEndpoint?$queryString"
        }

        val headers = mutableMapOf(
            "Referer" to mainUrl,
            "User-Agent" to userAgent
        )

        if (addToken) {
            ensureToken()?.let { headers["token"] = it }
        }

        Log.d(TAG, "[EXECUTE API] -> $fullUrl")

        return try {
            val response = app.get(fullUrl, headers = headers)
            val body = response.text.trim()

            if (response.code in 200..299 && body.startsWith("{")) {
                JSONObject(body)
            } else {
                Log.e(TAG, "API RESP NOT JSON [${response.code}] -> ${body.take(100)}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "EXCEPTION apiRequest($cleanEndpoint): ${e.localizedMessage}")
            null
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return url.replace("https://rapi.ifood.tv", baseApiUrl)
            .replace("http://rapi.ifood.tv", baseApiUrl)
            .replace("http://fawesome.ifood.tv", baseApiUrl)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureToken()

        val currentPref = FawesomePrefs.getMainPageType()
        val prefChanged = (currentPref != lastPref)
        if (prefChanged) {
            lastPref = currentPref
        }

        Log.i(TAG, "Memuat MainPage [Page $page] dengan preferensi: $currentPref (prefChanged=$prefChanged)")

        return when {
            currentPref == "home" -> loadHomePage(page, prefChanged)
            currentPref.startsWith("url:") -> {
                val url = currentPref.substring(4)
                loadCategoryOrCountryPage(url, page, prefChanged)
            }
            else -> loadHomePage(page, prefChanged)
        }
    }

    private suspend fun loadHomePage(page: Int, prefChanged: Boolean): HomePageResponse = coroutineScope {
        val startIndex = (page - 1).toString()
        val params = mutableMapOf("parent" to "Home")
        if (prefChanged) params["_t"] = System.currentTimeMillis().toString()

        val json = apiRequest("sub-categories.php", params)
            ?: return@coroutineScope newHomePageResponse(emptyList())

        val subcats = json.optJSONArray("subcategories")
            ?: return@coroutineScope newHomePageResponse(emptyList())

        val tasks = (0 until subcats.length()).map { i ->
            async {
                val obj = subcats.optJSONObject(i) ?: return@async null
                val categoryTitle = obj.optString("title").takeIf { it.isNotBlank() } ?: return@async null
                val feedUrl = obj.optString("feed").takeIf { it.isNotBlank() } ?: return@async null

                val fixedFeed = fixUrl(feedUrl)
                val (endpoint, catParams) = extractEndpointAndParams(fixedFeed)
                catParams["start-index"] = startIndex
                if (prefChanged) catParams["_t"] = System.currentTimeMillis().toString()

                val categoryJson = apiRequest(endpoint, catParams)

                val movies = parseFeedItems(categoryJson)
                if (movies.isNotEmpty()) {
                    HomePageList(categoryTitle, movies)
                } else null
            }
        }

        val homePageLists = tasks.awaitAll().filterNotNull()
        newHomePageResponse(homePageLists)
    }

    private suspend fun loadCategoryOrCountryPage(fullUrl: String, page: Int, prefChanged: Boolean): HomePageResponse = coroutineScope {
        val startIndex = (page - 1).toString()
        val fixedUrl = fixUrl(fullUrl)
        val (endpoint, params) = extractEndpointAndParams(fixedUrl)

        // Cek apakah endpoint merupakan kontainer subkategori (seperti shows.php dengan listoflist)
        val isContainer = endpoint.contains("sub-categories") ||
                params["searchType"] == "listoflist" ||
                fullUrl.contains("listoflist")

        // Jika bukan kontainer (misal: feed langsung), tambahkan start-index ke request utama
        if (!isContainer) {
            params["start-index"] = startIndex
        }
        if (prefChanged) params["_t"] = System.currentTimeMillis().toString()

        val json = apiRequest(endpoint, params) ?: return@coroutineScope newHomePageResponse(emptyList())

        val subcats = json.optJSONArray("subcategories")
        val homePageLists = mutableListOf<HomePageList>()

        if (subcats != null) {
            val tasks = (0 until subcats.length()).map { i ->
                async {
                    val obj = subcats.optJSONObject(i) ?: return@async null
                    val categoryTitle = obj.optString("title").takeIf { it.isNotBlank() } ?: return@async null
                    val feedUrl = obj.optString("feed").takeIf { it.isNotBlank() } ?: return@async null

                    val fixedFeed = fixUrl(feedUrl)
                    val (subEndpoint, subParams) = extractEndpointAndParams(fixedFeed)
                    
                    // start-index diterapkan pada feed subkategori (misal: Best Japanese Movies)
                    subParams["start-index"] = startIndex
                    if (prefChanged) subParams["_t"] = System.currentTimeMillis().toString()

                    val categoryJson = apiRequest(subEndpoint, subParams)

                    val movies = parseFeedItems(categoryJson)
                    if (movies.isNotEmpty()) {
                        HomePageList(categoryTitle, movies)
                    } else null
                }
            }
            homePageLists.addAll(tasks.awaitAll().filterNotNull())
        } else if (json.has("feed")) {
            val movies = parseFeedItems(json)
            if (movies.isNotEmpty()) {
                homePageLists.add(HomePageList("Movies", movies))
            }
        }

        newHomePageResponse(homePageLists)
    }

    private fun parseFeedItems(json: JSONObject?): List<SearchResponse> {
        if (json == null) return emptyList()
        val feed = json.optJSONObject("feed") ?: return emptyList()
        val items = feed.optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<SearchResponse>()

        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val videoTitle = obj.optString("title").takeIf { it.isNotBlank() } ?: continue
            val poster = obj.optString("hd_image").ifBlank { obj.optString("sd_image") }

            val videoId = obj.optString("video_id").ifBlank {
                obj.optString("nid")
            }.ifBlank {
                obj.optString("actionkey").substringAfter("nodeid-=").substringAfter("nodeid=")
            }

            val itemUrl = if (videoId.isNotBlank()) {
                "$baseApiUrl/recipes.php?searchType=nodeid&start-index=0&max-results=1&nid=$videoId"
            } else {
                val urlEncodedData = URLEncoder.encode(obj.toString(), "UTF-8")
                "$mainUrl/watch?data=$urlEncodedData"
            }

            val res = newMovieSearchResponse(videoTitle, itemUrl, TvType.Movie) {
                this.posterUrl = poster
            }
            results.add(res)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("searchType=nodeid") || url.contains("nid=") -> {
                val (endpoint, params) = extractEndpointAndParams(url)
                val json = apiRequest(endpoint, params) ?: return errorResponse("Gagal memuat detail API")
                val items = json.optJSONObject("feed")?.optJSONArray("items")
                val item = items?.optJSONObject(0) ?: return errorResponse("Detail film tidak ditemukan")

                val title = item.optString("title", "Movie")
                val poster = item.optString("hd_image").ifBlank { item.optString("sd_image") }
                val plot = item.optString("description")

                val videoUrlsArray = JSONArray()
                val primaryUrl = item.optString("video_url")
                if (primaryUrl.isNotBlank()) {
                    videoUrlsArray.put(primaryUrl)
                }

                val altUrls = item.optJSONArray("video_urls")
                if (altUrls != null) {
                    for (i in 0 until altUrls.length()) {
                        val alt = altUrls.optString(i)
                        if (alt.isNotBlank()) {
                            var exists = false
                            for (j in 0 until videoUrlsArray.length()) {
                                if (videoUrlsArray.optString(j) == alt) {
                                    exists = true
                                    break
                                }
                            }
                            if (!exists) videoUrlsArray.put(alt)
                        }
                    }
                }

                val ccPath = item.optString("cc_path")
                val ccMulti = item.optJSONArray("cc_path_multi_lang")

                val dataJson = JSONObject().apply {
                    put("video_urls", videoUrlsArray)
                    if (ccPath.isNotBlank()) put("cc_path", ccPath)
                    if (ccMulti != null && ccMulti.length() > 0) put("cc_path_multi_lang", ccMulti)
                }

                val year = item.optString("release_date").takeLast(4).toIntOrNull()
                    ?: item.optString("date").takeLast(4).toIntOrNull()

                val tagsList = item.optString("content_genre")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val actorsList = mutableListOf<String>()
                val actorsArray = item.optJSONArray("actors")
                if (actorsArray != null) {
                    for (i in 0 until actorsArray.length()) {
                        actorsList.add(actorsArray.getString(i))
                    }
                }

                val recommendationsList = mutableListOf<SearchResponse>()
                val deeplink = item.optString("deeplink_url").takeIf { it.isNotBlank() }
                if (deeplink != null) {
                    val fixedDeep = fixUrl(deeplink)
                    val (recEnd, recParams) = extractEndpointAndParams(fixedDeep)
                    val recJson = apiRequest(recEnd, recParams)
                    recommendationsList.addAll(parseFeedItems(recJson))
                }

                newMovieLoadResponse(title, url, TvType.Movie, dataJson.toString()) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tagsList
                    this.actors = actorsList.map { ActorData(Actor(it, "")) }
                    if (recommendationsList.isNotEmpty()) {
                        this.recommendations = recommendationsList
                    }
                }
            }
            url.startsWith("$mainUrl/watch?data=") -> {
                val encoded = url.substringAfter("data=")
                val dataJsonStr = URLDecoder.decode(encoded, "UTF-8")
                val json = try { JSONObject(dataJsonStr) } catch (_: Exception) { null }
                if (json != null) {
                    val title = json.optString("title", "Movie")
                    val poster = json.optString("poster")
                    val plot = json.optString("plot")
                    newMovieLoadResponse(title, url, TvType.Movie, dataJsonStr) {
                        this.posterUrl = poster
                        this.plot = plot
                    }
                } else {
                    errorResponse("Data video tidak valid")
                }
            }
            else -> errorResponse("URL tidak dikenal")
        }
    }

    private fun extractEndpointAndParams(fullUrl: String): Pair<String, MutableMap<String, String>> {
        val base = "$baseApiUrl/"
        val afterBase = if (fullUrl.startsWith(base)) {
            fullUrl.substring(base.length)
        } else {
            val idx = fullUrl.indexOf("/api/")
            if (idx != -1) fullUrl.substring(idx + 5) else fullUrl
        }
        val parts = afterBase.split("?")
        val endpoint = parts[0]
        val params = mutableMapOf<String, String>()
        if (parts.size > 1) {
            parts[1].split("&").forEach {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) {
                    params[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
                }
            }
        }
        return endpoint to params
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val json = try { JSONObject(data) } catch (_: Exception) { return false }

        val ccPath = json.optString("cc_path")
        if (ccPath.isNotBlank()) {
            subtitleCallback.invoke(SubtitleFile("English", ccPath))
        }

        val ccMulti = json.optJSONArray("cc_path_multi_lang")
        if (ccMulti != null) {
            for (i in 0 until ccMulti.length()) {
                val subObj = ccMulti.getJSONObject(i)
                val lang = subObj.optString("language", "Unknown")
                val file = subObj.optString("file_path")
                if (file.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile(lang, file))
                }
            }
        }

        val videoUrls = json.optJSONArray("video_urls")
        val urlsList = mutableListOf<String>()

        if (videoUrls != null && videoUrls.length() > 0) {
            for (i in 0 until videoUrls.length()) {
                val u = videoUrls.optString(i)
                if (u.isNotBlank()) urlsList.add(u)
            }
        } else {
            val singleUrl = json.optString("video_url")
            if (singleUrl.isNotBlank()) urlsList.add(singleUrl)
        }

        if (urlsList.isEmpty()) return false

        urlsList.distinct().forEachIndexed { index, streamUrl ->
            val serverName = if (urlsList.size > 1) "$name - Server ${index + 1}" else name
            val type = when {
                streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                streamUrl.contains(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = serverName,
                    url = streamUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = type,
                    headers = mapOf("Referer" to mainUrl)
                )
            )
        }

        return true
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        ensureToken()

        val json = apiRequest("recipes.php", mapOf(
            "searchType" to "search",
            "keys" to query,
            "start-index" to (page - 1).toString()
        )) ?: return newSearchResponseList(emptyList(), false)

        val results = parseFeedItems(json)
        val feed = json.optJSONObject("feed")
        val count = feed?.optInt("count", 0) ?: 0
        val maxResult = feed?.optInt("max_result", 20) ?: 20
        val hasNext = results.isNotEmpty() && (results.size >= maxResult || (count > 0 && page * maxResult < count))

        return newSearchResponseList(results, hasNext)
    }

    private suspend fun errorResponse(msg: String): MovieLoadResponse {
        return newMovieLoadResponse("Error: $msg", "", TvType.Movie, "")
    }
}