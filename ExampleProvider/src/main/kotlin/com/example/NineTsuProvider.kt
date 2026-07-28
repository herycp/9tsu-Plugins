package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.json.JSONArray

class NineTsuProvider : MainAPI() {
    override var mainUrl = "https://9tsu.vip"
    override var name = "9tsu"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun getAttrOrNull(element: org.jsoup.nodes.Element?, attr: String): String? {
        val value = element?.attr(attr)?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    private fun unescapeJs(str: String): String {
        return str.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    // 1. Kategori Halaman Utama
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/daily" to "Harian (Daily)",
        "$mainUrl/drama-monday1" to "Drama Senin",
        "$mainUrl/drama-tuesday1" to "Drama Selasa",
        "$mainUrl/drama-wednesdaydouga" to "Drama Rabu",
        "$mainUrl/drama-thursdaydouga" to "Drama Kamis",
        "$mainUrl/drama-fridaydouga" to "Drama Jumat",
        "$mainUrl/drama-saturdaydouga" to "Drama Sabtu",
        "$mainUrl/drama-sundaydouga" to "Drama Minggu",
        "$mainUrl/dramaend" to "Drama Tamat (End)",
        "$mainUrl/premium" to "Kategori Premium"
    )

    // 2. Scraping Beranda
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            val cleanData = request.data.removeSuffix("/")
            "$cleanData/page/$page/"
        } else {
            request.data
        }

        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val homeItems = doc.select("article, .post, .entry, .type-post").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, .entry-title a, a[rel='bookmark']") 
                ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")

            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            val imgElement = element.selectFirst("img")
            val posterUrl = getAttrOrNull(imgElement, "data-src") 
                ?: getAttrOrNull(imgElement, "data-lazy-src") 
                ?: getAttrOrNull(imgElement, "src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // 3. Pencarian (FIXED: Blokir Hasil Navigasi)
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim().replace(" ", "+")

        // Filter ketat untuk membuang elemen web/menu
        val invalidTitles = listOf("back to homepage", "home", "beranda", "menu", "skip to content")

        // Metode A: WP REST API (Target Utama)
        try {
            val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$cleanQuery&_embed&per_page=20"
            val apiRes = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent))
            
            if (apiRes.code == 200 && apiRes.text.trim().startsWith("[")) {
                val jsonArray = JSONArray(apiRes.text)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val titleRaw = item.getJSONObject("title").optString("rendered", "")
                    val title = titleRaw.replace(Regex("<[^>]*>"), "").replace("&#8211;", "-").trim()
                    val link = item.optString("link", "")

                    if (invalidTitles.any { title.equals(it, ignoreCase = true) }) continue

                    var posterUrl: String? = null
                    if (item.has("_embedded")) {
                        val embedded = item.getJSONObject("_embedded")
                        if (embedded.has("wp:featuredmedia")) {
                            val mediaArray = embedded.getJSONArray("wp:featuredmedia")
                            if (mediaArray.length() > 0) {
                                posterUrl = mediaArray.getJSONObject(0).optString("source_url", null)
                            }
                        }
                    }

                    if (title.isNotBlank() && link.isNotBlank()) {
                        results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        } catch (e: Exception) {
            // Abaikan error API
        }

        // Jangan eksekusi fallback HTML jika API sudah menemukan hasil yang bersih
        if (results.isNotEmpty()) return results.distinctBy { it.url }

        // Metode B: HTML Fallback dengan Seleksi Ketat
        try {
            val res = app.get("$mainUrl/?s=$cleanQuery", headers = mapOf("User-Agent" to userAgent))
            val doc = res.document

            // HANYA ekstrak dari div artikel resmi, bukan tag 'a' bebas
            doc.select("article.post, article.type-post, div.post, div.result-item, li.is-ajax-search-result").forEach { element ->
                val titleElement = element.selectFirst("h2 a, h3 a, .entry-title a, a[rel='bookmark']") ?: return@forEach
                val title = titleElement.text().trim()
                val href = titleElement.attr("href")

                if (invalidTitles.any { title.equals(it, ignoreCase = true) }) return@forEach

                if (title.isNotBlank() && href.isNotBlank() && href.contains("9tsu.vip")) {
                    val imgElement = element.selectFirst("img")
                    val posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")

                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        } catch (e: Exception) {
            // Abaikan error HTML
        }

        return results.distinctBy { it.url }
    }

    // 4. Memuat Detail Halaman
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val doc = response.document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: doc.title()
        val imgElement = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
        val posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src")

        // Kunci Perbaikan: Lempar URL postingan aslinya langsung ke loadLinks
        return newMovieLoadResponse(title, url, TvType.TvSeries, url) {
            this.posterUrl = posterUrl
        }
    }

    // 5. Ekstraksi Pemutar Video (FIXED: Penelusuran Mendalam)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        // Data adalah tautan asli (https://9tsu.vip/...)
        val docRes = app.get(data, headers = mapOf("User-Agent" to userAgent))
        val html = docRes.text
        val doc = docRes.document

        val embedUrls = mutableSetOf<String>()

        // A. Cek iFrame yang tertanam
        doc.select("iframe").forEach { iframe ->
            getAttrOrNull(iframe, "src")?.let { embedUrls.add(it) }
            getAttrOrNull(iframe, "data-src")?.let { embedUrls.add(it) }
        }

        // B. Cek Tombol/Tautan server video
        doc.select("a.video-link, a.play-button, .entry-content a").forEach { a ->
            val href = a.attr("href")
            if (href.contains("dremoxa") || href.contains("demoxa") || href.contains("vtbe") || href.endsWith(".m3u8")) {
                embedUrls.add(href)
            }
        }

        // C. Ekstrak brutal peladen mentah dari JS dan teks HTML
        val regex = Regex("""https?://[^\s"'<>]+?(?:dremoxa|demoxa|vtbe|vidmoly|streamtape|dood|mixdrop|playlist|\.m3u8|\.mp4)[^\s"'<>]*""")
        regex.findAll(html).forEach { match ->
            embedUrls.add(unescapeJs(match.value))
        }

        var linkFound = false

        for (rawUrl in embedUrls) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) continue

            // 1. Ekstrak Langsung jika file .m3u8
            if (cleanUrl.contains(".m3u8") || cleanUrl.endsWith(".mp4")) {
                val isM3 = cleanUrl.contains(".m3u8")
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = if (isM3) "9tsu - Direct Stream" else "9tsu - Direct MP4",
                        url = cleanUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = isM3 // Paksa ExoPlayer memutar sebagai HLS
                    )
                )
                linkFound = true
                continue
            }

            // 2. Ekstrak Dremoxa / Demoxa (Unpacker)
            if (cleanUrl.contains("dremoxa") || cleanUrl.contains("demoxa") || cleanUrl.contains("vtbe")) {
                try {
                    // Paksa Origin header agar server tidak menolak HTTP Request
                    val embedHtml = app.get(cleanUrl, referer = data, headers = mapOf("User-Agent" to userAgent, "Origin" to mainUrl)).text
                    val unpacked = try { getAndUnpack(embedHtml) } catch (e: Exception) { "" }
                    val combinedText = embedHtml + unpacked

                    // Temukan .m3u8 yang berhasil di-unpack
                    val m3u8Regex = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""")
                    val streams = m3u8Regex.findAll(combinedText).map { unescapeJs(it.value) }.toList()

                    streams.distinct().forEach { streamUrl ->
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "9tsu - Demoxa Server",
                                url = streamUrl,
                                referer = cleanUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true, // Wajib bernilai True agar streaming stabil
                                headers = mapOf("Origin" to "https://dremoxa.space")
                            )
                        )
                        linkFound = true
                    }
                } catch (e: Exception) {
                    // Jika server target down, lanjutkan ke peladen berikutnya
                }
                continue
            }

            // 3. Fallback Universal (Streamtape, dll)
            if (loadExtractor(cleanUrl, subtitleCallback, callback)) {
                linkFound = true
            }
        }

        return linkFound
    }
}
