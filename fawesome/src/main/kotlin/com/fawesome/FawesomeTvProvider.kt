package com.fawesome

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLDecoder

class FawesomeTvProvider : MainAPI() {
    override var name = "Fawesome TV"
    override var mainUrl = "https://fawesome.tv"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override var lang = "en"
    override var hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"
    private val baseApiUrl = "$mainUrl/home/new/v453/api"

    private var cachedToken: String? = null
    private var tokenTimestamp: Long = 0
    private val TOKEN_EXPIRY_MS = 600_000L

    private suspend fun getToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && (now - tokenTimestamp) < TOKEN_EXPIRY_MS) {
            return cachedToken
        }
        return try {
            val url = "$baseApiUrl/getSecurityToken.php?siteId=236&auth-token=1217575&country=US"
            val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent))
            val json = JSONObject(response.text)
            
            val token = json.optString("token").takeIf { it.isNotBlank() } 
                ?: json.optJSONObject("data")?.optString("token")
                
            if (!token.isNullOrBlank()) {
                cachedToken = token
                tokenTimestamp = now
            }
            token
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun apiRequest(
        endpoint: String,
        params: Map<String, String>,
        addToken: Boolean = true
    ): JSONObject? {
        val allParams = mutableMapOf<String, String>().apply {
            putAll(params)
            put("siteId", "236")
            put("country", "US")
        }

        val fullUrl = "$baseApiUrl/$endpoint?" + allParams.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

        val headers = mutableMapOf(
            "Referer" to mainUrl,
            "User-Agent" to userAgent
        )
        
        if (addToken) {
            val token = getToken()
            if (token != null) {
                headers["token"] = token
            }
        }

        return try {
            val response = app.get(fullUrl, headers = headers)
            JSONObject(response.text)
        } catch (_: Exception) {
            null
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("https://rapi.ifood.tv")) {
            url.replace("https://rapi.ifood.tv", mainUrl)
        } else url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())

        val pref = FawesomePrefs.getMainPageType()
        return when {
            pref == "home" -> loadHomePage()
            pref.startsWith("url:") -> {
                val url = pref.substring(4)
                loadCategoryOrCountryPage(url)
            }
            else -> loadHomePage()
        }
    }

    // Mengambil feed kategori secara Paralel/Konkuren untuk mencegah timeout
    private suspend fun loadHomePage(): HomePageResponse = coroutineScope {
        val json = apiRequest("sub-categories.php", mapOf("parent" to "Home"))
            ?: return@coroutineScope newHomePageResponse(emptyList())

        val subcats = json.optJSONArray("subcategories") 
            ?: return@coroutineScope newHomePageResponse(emptyList())

        val tasks = (0 until subcats.length()).map { i ->
            async {
                val obj = subcats.optJSONObject(i) ?: return@async null
                val categoryTitle = obj.optString("title").takeIf { it.isNotBlank() } ?: return@async null
                val feedUrl = obj.optString("feed").takeIf { it.isNotBlank() } ?: return@async null

                val fixedFeed = fixUrl(feedUrl)
                val (endpoint, params) = extractEndpointAndParams(fixedFeed)
                val categoryJson = apiRequest(endpoint, params)

                val movies = parseFeedItems(categoryJson)
                if (movies.isNotEmpty()) {
                    HomePageList(categoryTitle, movies)
                } else null
            }
        }

        val homePageLists = tasks.awaitAll().filterNotNull()
        newHomePageResponse(homePageLists)
    }

    private suspend fun loadCategoryOrCountryPage(fullUrl: String): HomePageResponse = coroutineScope {
        val (endpoint, params) = extractEndpointAndParams(fullUrl)
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

            val dataJson = JSONObject().apply {
                put("title", videoTitle)
                val videoUrl = obj.optString("video_url")
                if (videoUrl.isNotBlank()) put("video_url", videoUrl)
                val ccPath = obj.optString("cc_path")
                if (ccPath.isNotBlank()) put("cc_path", ccPath)
                val ccMulti = obj.optJSONArray("cc_path_multi_lang")
                if (ccMulti != null && ccMulti.length() > 0) {
                    put("cc_path_multi_lang", ccMulti)
                }
                if (poster.isNotBlank()) put("poster", poster)
                val desc = obj.optString("description")
                if (desc.isNotBlank()) put("plot", desc)
            }

            val dataStr = dataJson.toString()
            val encodedData = URLEncoder.encode(dataStr, "UTF-8")
            val itemDataUrl = "fawesome://video?data=$encodedData"

            val res = newMovieSearchResponse(videoTitle, itemDataUrl, TvType.Movie) {
                this.posterUrl = poster
            }
            results.add(res)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.startsWith("fawesome://video?data=") -> {
                val encoded = url.substringAfter("data=")
                val dataJson = URLDecoder.decode(encoded, "UTF-8")
                val json = try { JSONObject(dataJson) } catch (_: Exception) { null }
                if (json != null) {
                    val title = json.optString("title", "Movie")
                    val poster = json.optString("poster")
                    val plot = json.optString("plot")
                    newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                        this.posterUrl = poster
                        this.plot = plot
                    }
                } else {
                    errorResponse("Invalid video data")
                }
            }
            else -> errorResponse("Unknown URL")
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
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            return false
        }

        var found = false

        val ccPath = json.optString("cc_path")
        if (ccPath.isNotBlank()) {
            subtitleCallback.invoke(SubtitleFile(ccPath, "English"))
            found = true
        }
        val ccMulti = json.optJSONArray("cc_path_multi_lang")
        if (ccMulti != null) {
            for (i in 0 until ccMulti.length()) {
                val subObj = ccMulti.getJSONObject(i)
                val lang = subObj.optString("language", "Unknown")
                val file = subObj.optString("file_path")
                if (file.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile(file, lang))
                    found = true
                }
            }
        }

        val videoUrl = json.optString("video_url")
        if (videoUrl.isBlank()) return found

        val type = when {
            videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
            videoUrl.contains(".mpd") -> ExtractorLinkType.DASH
            videoUrl.endsWith(".mp4") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.VIDEO
        }

        val link = ExtractorLink(
            source = name,
            name = "Fawesome TV",
            url = videoUrl,
            referer = mainUrl,
            quality = Qualities.Unknown.value,
            type = type,
            headers = mapOf("Referer" to mainUrl)
        )
        callback.invoke(link)
        return true
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val json = apiRequest("shows.php", mapOf(
            "searchType" to "search",
            "keys" to query,
            "start-index" to ((page - 1) * 20).toString()
        )) ?: return newSearchResponseList(emptyList(), false)

        val results = parseFeedItems(json)
        val feed = json.optJSONObject("feed")
        val count = feed?.optInt("count", 0) ?: 0
        val maxResult = feed?.optInt("max_result", 20) ?: 20
        val hasNext = results.size >= maxResult && (page * maxResult < count)

        return newSearchResponseList(results, hasNext)
    }

    private suspend fun errorResponse(msg: String): MovieLoadResponse {
        return newMovieLoadResponse("Error: $msg", "", TvType.Movie, "")
    }
}