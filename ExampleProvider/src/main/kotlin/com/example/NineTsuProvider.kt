override suspend fun search(query: String): List<SearchResponse> {
    val results = mutableListOf<SearchResponse>()
    val cleanQuery = query.trim().replace(" ", "+")

    val invalidTitles = listOf("back to homepage", "home", "beranda", "menu", "skip to content", "not found", "404")

    // 1. WP REST API (standar)
    try {
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$cleanQuery&_embed&per_page=50"
        val apiRes = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest"))
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
                    results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = posterUrl })
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }

    // 2. Admin AJAX (umum digunakan untuk search)
    if (results.isEmpty()) {
        try {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            // Coba beberapa action yang mungkin
            val actions = listOf("search", "search_posts", "loadmore", "search_results")
            for (action in actions) {
                try {
                    val params = mapOf("action" to action, "s" to cleanQuery, "keyword" to cleanQuery)
                    val ajaxRes = app.post(
                        ajaxUrl,
                        data = params,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    )
                    if (ajaxRes.code == 200) {
                        val text = ajaxRes.text.trim()
                        // Coba parse sebagai JSON
                        if (text.startsWith("{")) {
                            val json = org.json.JSONObject(text)
                            val html = json.optString("html", null) ?: json.optString("data", null) ?: json.optString("content", null)
                            if (html != null) {
                                val fragment = org.jsoup.Jsoup.parse(html)
                                extractItemsFromDocument(fragment, results, invalidTitles)
                            }
                        } else if (text.startsWith("[")) {
                            // Mungkin JSON array langsung
                            val jsonArray = JSONArray(text)
                            for (i in 0 until jsonArray.length()) {
                                val item = jsonArray.getJSONObject(i)
                                val title = item.optString("title", "").trim()
                                val link = item.optString("link", "").trim()
                                val poster = item.optString("image", null) ?: item.optString("thumbnail", null)
                                if (title.isNotBlank() && link.isNotBlank() && !invalidTitles.any { title.equals(it, ignoreCase = true) }) {
                                    results.add(newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = poster })
                                }
                            }
                        } else {
                            // Mungkin HTML
                            val doc = org.jsoup.Jsoup.parse(text)
                            extractItemsFromDocument(doc, results, invalidTitles)
                        }
                        if (results.isNotEmpty()) break
                    }
                } catch (e: Exception) { /* ignore */ }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 3. HTML fallback dengan header AJAX dan beberapa format URL
    if (results.isEmpty()) {
        try {
            val searchUrls = listOf(
                "$mainUrl/?s=$cleanQuery",
                "$mainUrl/search/$cleanQuery/",
                "$mainUrl/search/$cleanQuery"
            )
            for (url in searchUrls) {
                val res = app.get(url, headers = mapOf(
                    "User-Agent" to userAgent,
                    "X-Requested-With" to "XMLHttpRequest"
                ))
                val doc = res.document
                extractItemsFromDocument(doc, results, invalidTitles)
                if (results.isNotEmpty()) break
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    return results.distinctBy { it.url }
}

// Fungsi pembantu untuk mengekstrak item dari document/fragment
private fun extractItemsFromDocument(doc: org.jsoup.nodes.Document, results: MutableList<SearchResponse>, invalidTitles: List<String>) {
    doc.select("article, .post, .entry, .type-post, .item, .result-item, .video-block, .search-item, .blog-item, .hentry, .list-item").forEach { element ->
        val titleElement = element.selectFirst("h2 a, h3 a, h4 a, .entry-title a, a[rel='bookmark']")
            ?: element.select("a").firstOrNull { it.text().trim().isNotBlank() }
            ?: return@forEach

        val title = titleElement.text().trim()
        val href = titleElement.attr("href")

        if (title.isBlank() || href.isBlank() || !href.contains(mainUrl)) return@forEach
        if (invalidTitles.any { title.contains(it, ignoreCase = true) }) return@forEach

        val imgElement = element.selectFirst("img")
        var posterUrl = getAttrOrNull(imgElement, "data-src") ?: getAttrOrNull(imgElement, "src") ?: getAttrOrNull(imgElement, "data-lazy-src")
        if (posterUrl?.startsWith("data:image") == true) posterUrl = null

        results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl })
    }
}
