package com.fawesome

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray

class FawesomeProvider : MainAPI() {
    override var name = "Fawesome TV"
    override var mainUrl = "https://fawesome.tv"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "en"

    private val baseApiUrl = "https://fawesome.tv/home/new/v453/api"
    private var securityToken: String? = null

    // Fungsi untuk mendapatkan token keamanan[cite: 3]
    private suspend fun getToken(): String {
        securityToken?.let { return it }
        val tokenUrl = "$baseApiUrl/getSecurityToken.php?siteId=236&auth-token=1217575&country=US"
        
        // Memanggil API token dengan header BaseUrl[cite: 3]
        val response = app.get(tokenUrl, headers = mapOf("BaseUrl" to "fawesome.tv")).text
        
        // Asumsi respons berupa token text atau JSON. Parsing yang aman:
        securityToken = try {
            if (response.trim().startsWith("{")) {
                JSONObject(response).optString("token", response.trim())
            } else {
                response.trim()
            }
        } catch (e: Exception) {
            response.trim()
        }
        return securityToken ?: ""
    }

    // Fungsi untuk memuat header wajib untuk setiap panggilan API[cite: 3]
    private suspend fun getApiHeaders(): Map<String, String> {
        return mapOf(
            "BaseUrl" to "fawesome.tv",
            "referer" to "fawesome.tv",
            "token" to getToken()
        )
    }

    // Mengambil dan mendefinisikan layout halaman depan secara dinamis 
    // berdasarkan preferensi yang dipilih di FawesomePrefs[cite: 3]
    override val mainPage: List<MainPageData>
        get() = runBlocking {
            val list = mutableListOf<MainPageData>()
            try {
                val pref = FawesomePrefs.getLayout()
                val isHome = pref.startsWith("parent=")
                val url = if (isHome) {
                    "$baseApiUrl/sub-categories.php?$pref&siteId=236&country=US"
                } else {
                    "$baseApiUrl/shows.php?searchType=listoflist&$pref&siteId=236&auth-token=1217575&country=US"
                }

                val jsonResponse = app.get(url, headers = getApiHeaders()).text
                val jsonObj = JSONObject(jsonResponse)
                val subcategories = jsonObj.optJSONArray("subcategories")

                if (subcategories != null) {
                    for (i in 0 until subcategories.length()) {
                        val sub = subcategories.getJSONObject(i)
                        val title = sub.optString("title")
                        val rawFeed = sub.optString("feed")
                        
                        // Mengganti domain lama rapi.ifood.tv dengan base API[cite: 3]
                        val feed = rawFeed.replace("https://rapi.ifood.tv", baseApiUrl)
                        
                        if (title.isNotBlank() && feed.isNotBlank()) {
                            list.add(MainPageData(title, feed))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Fallback jika gagal memuat kategori
            if (list.isEmpty()) {
                listOf(MainPageData("Home", "$baseApiUrl/sub-categories.php?parent=Home&siteId=236&country=US"))
            } else {
                list
            }
        }

    // Mengeksekusi setiap kategori di halaman depan menggunakan URL feed masing-masing[cite: 3]
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        // Penyesuaian pagination berdasarkan start-index (kelipatan 20)[cite: 3]
        val pagedUrl = if (url.contains("start-index=")) {
            url.replace(Regex("start-index=\\d+"), "start-index=${(page - 1) * 20}")
        } else {
            "$url&start-index=${(page - 1) * 20}"
        }

        val jsonResponse = app.get(pagedUrl, headers = getApiHeaders()).text
        val items = parseFeedItems(jsonResponse)

        return newHomePageResponse(request.name, items)
    }

    // Melakukan pencarian menggunakan API Search Fawesome[cite: 3]
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = "$baseApiUrl/recipes.php?searchType=search&keys=$query&siteId=236&country=US&start-index=${(page - 1) * 20}"
        val jsonResponse = app.get(searchUrl, headers = getApiHeaders()).text
        val items = parseFeedItems(jsonResponse)
        
        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    // Helper untuk memparsing objek JSON "feed" -> "items" yang sering digunakan oleh Fawesome[cite: 3]
    private fun parseFeedItems(jsonString: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val jsonObj = JSONObject(jsonString)
            val feedObj = jsonObj.optJSONObject("feed") ?: return emptyList()
            val items = feedObj.optJSONArray("items") ?: return emptyList()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.optString("title")
                val poster = item.optString("hd_image").ifBlank { item.optString("sd_image") }
                val videoId = item.optString("video_id")

                if (title.isNotBlank() && videoId.isNotBlank()) {
                    // Menyimpan URL kustom berisi videoId untuk digunakan di fungsi load()
                    val loadUrl = "fawesome://$videoId"
                    results.add(
                        newMovieSearchResponse(title, loadUrl, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    // Membuka detail movie menggunakan API nodeid[cite: 3]
    override suspend fun load(url: String): LoadResponse {
        val videoId = url.replace("fawesome://", "")
        val apiUrl = "$baseApiUrl/recipes.php?searchType=nodeid&max-results=1&siteId=236&auth-token=1217575&nid=$videoId"
        
        val jsonResponse = app.get(apiUrl, headers = getApiHeaders()).text
        val jsonObj = JSONObject(jsonResponse)
        val item = jsonObj.optJSONObject("feed")?.optJSONArray("items")?.getJSONObject(0) 
            ?: throw ErrorLoadingException("Failed to load video details")

        val title = item.optString("title")
        val description = item.optString("description")
        val poster = item.optString("hd_image").ifBlank { item.optString("sd_image") }
        val videoUrl = item.optString("video_url")
        val streamFormat = item.optString("streamFormat")
        val ccPath = item.optString("cc_path")
        
        // Memasukkan array subtitle multi-bahasa jika ada[cite: 3]
        val ccPathMulti = item.optJSONArray("cc_path_multi_lang") ?: JSONArray()

        // Menggabungkan semua informasi media ke dalam payload JSON untuk dikirim ke loadLinks
        val linkData = JSONObject().apply {
            put("video_url", videoUrl)
            put("streamFormat", streamFormat)
            put("cc_path", ccPath)
            put("cc_multi", ccPathMulti)
        }.toString()

        return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // Mengekstrak stream video dan subtitle langsung dari respon JSON API sebelumnya[cite: 3]
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val json = JSONObject(data)
            val videoUrl = json.optString("video_url")
            val streamFormat = json.optString("streamFormat")
            val ccPath = json.optString("cc_path")
            val ccMulti = json.optJSONArray("cc_multi")

            if (videoUrl.isNotBlank()) {
                val isM3u8 = streamFormat.equals("hls", ignoreCase = true) || videoUrl.contains(".m3u8")
                callback.invoke(
                    ExtractorLink(
                        name = "Fawesome",
                        source = this.name,
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )

                // Subtitle default (English)[cite: 3]
                if (ccPath.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile("English", ccPath))
                }

                // Subtitle tambahan jika ada multi-bahasa (contoh: Spanish, dll)[cite: 3]
                if (ccMulti != null) {
                    for (i in 0 until ccMulti.length()) {
                        val subObj = ccMulti.getJSONObject(i)
                        val lang = subObj.optString("language", "Unknown")
                        val path = subObj.optString("file_path", "")
                        if (path.isNotBlank() && path.startsWith("http")) {
                            subtitleCallback.invoke(SubtitleFile(lang, path))
                        }
                    }
                }
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}