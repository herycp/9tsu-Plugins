override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (data.isBlank()) return false

    val docRes = app.get(data, headers = mapOf("User-Agent" to userAgent))
    val html = docRes.text
    val doc = docRes.document

    val allUrls = mutableSetOf<String>()
    val embedUrls = mutableSetOf<String>()

    // 1. iframe
    doc.select("iframe").forEach { iframe ->
        val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
        if (src.isNotBlank()) {
            embedUrls.add(src)
        }
    }

    // 2. Cari link langsung di skrip
    doc.select("script").forEach { script ->
        var scriptData = script.data()
        try {
            if (scriptData.contains("eval(") || scriptData.contains("pako") || scriptData.contains("atob")) {
                val unpacked = getAndUnpack(scriptData)
                if (unpacked.isNotBlank()) scriptData = unpacked
            }
        } catch (e: Exception) {}
        extractVideoUrls(scriptData).forEach { url -> allUrls.add(url) }
    }

    // 3. Regex seluruh html
    extractVideoUrls(html).forEach { url -> allUrls.add(url) }

    // 4. Proses setiap embed URL dengan metode khusus
    for (embedUrl in embedUrls) {
        // Coba ekstrak langsung dari URL embed
        val extractedFromEmbed = extractFromEmbed(embedUrl, data) // fungsi baru
        extractedFromEmbed.forEach { url -> allUrls.add(url) }
    }

    // 5. Proses semua URL yang terkumpul
    var linkFound = false
    for (rawUrl in allUrls) {
        var cleanUrl = rawUrl.trim()
        if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
        if (!cleanUrl.startsWith("http")) continue

        if (loadExtractor(cleanUrl, subtitleCallback, callback)) {
            linkFound = true
            continue
        }

        if (cleanUrl.contains(".m3u8") || cleanUrl.endsWith(".mp4")) {
            val isM3 = cleanUrl.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    name = if (isM3) "9tsu - HLS" else "9tsu - MP4",
                    source = this.name,
                    url = cleanUrl,
                    type = if (isM3) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            linkFound = true
        }
    }

    return linkFound
}

// Fungsi baru untuk mengekstrak dari embed dengan pendekatan spesifik
private suspend fun extractFromEmbed(embedUrl: String, parentUrl: String): List<String> {
    val urls = mutableListOf<String>()
    try {
        val embedRes = app.get(embedUrl, referer = parentUrl, headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to parentUrl,
            "Origin" to embedUrl.substringBefore("/", "").replace("https://", "").replace("http://", ""),
            "X-Requested-With" to "XMLHttpRequest"
        ))
        val embedHtml = embedRes.text

        // Ekstrak video URLs dari embed HTML
        urls.addAll(extractVideoUrls(embedHtml))

        // Coba unpack
        try {
            val unpacked = getAndUnpack(embedHtml)
            if (unpacked.isNotBlank()) {
                urls.addAll(extractVideoUrls(unpacked))
            }
        } catch (e: Exception) {}

        // Jika embed dari dremoxa/demoxa/vtbe, coba API khusus
        if (embedUrl.contains("dremoxa") || embedUrl.contains("demoxa") || embedUrl.contains("vtbe")) {
            // Cari ID dari URL atau dari skrip
            val idPattern = Regex("""/(?:embed/|e/|v/|video/)([a-zA-Z0-9]+)""")
            val idMatch = idPattern.find(embedUrl)
            val id = idMatch?.groupValues?.get(1)

            if (id != null) {
                // Coba endpoint API umum
                val apiEndpoints = listOf(
                    "https://dremoxa.space/api/source/$id",
                    "https://demoxa.xyz/api/source/$id",
                    "https://vtbe.xyz/api/source/$id"
                )
                for (apiUrl in apiEndpoints) {
                    try {
                        val apiRes = app.post(
                            apiUrl,
                            data = mapOf("id" to id),
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to embedUrl,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded"
                            )
                        )
                        if (apiRes.code == 200) {
                            val text = apiRes.text
                            urls.addAll(extractVideoUrls(text))
                            // Coba parse JSON
                            try {
                                val json = JSONObject(text)
                                val file = json.optString("file", null) ?: json.optString("url", null) ?: json.optString("src", null)
                                if (file != null) urls.add(file)
                                val sources = json.optJSONArray("sources") ?: json.optJSONArray("files")
                                if (sources != null) {
                                    for (i in 0 until sources.length()) {
                                        val srcObj = sources.getJSONObject(i)
                                        val src = srcObj.optString("file", null) ?: srcObj.optString("url", null) ?: srcObj.optString("src", null)
                                        if (src != null) urls.add(src)
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    } catch (e: Exception) {}
                }

                // Coba juga endpoint dengan parameter berbeda
                val altEndpoints = listOf(
                    "https://dremoxa.space/source/$id",
                    "https://dremoxa.space/get/$id"
                )
                for (altUrl in altEndpoints) {
                    try {
                        val altRes = app.get(altUrl, referer = embedUrl, headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to embedUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        ))
                        if (altRes.code == 200) {
                            urls.addAll(extractVideoUrls(altRes.text))
                        }
                    } catch (e: Exception) {}
                }
            }

            // Coba ekstrak dari skrip di embed
            val embedDoc = org.jsoup.Jsoup.parse(embedHtml)
            embedDoc.select("script").forEach { script ->
                var scriptData = script.data()
                // Cari pola seperti player.setup({file: '...'})
                val setupPattern = Regex("""player\.setup\s*\(\s*\{[^}]*file\s*:\s*['"]([^'"]+)['"]""")
                setupPattern.findAll(scriptData).forEach { match ->
                    urls.add(match.groupValues[1])
                }
                // Cari pola seperti var config = { sources: [...] }
                val configPattern = Regex("""var\s+config\s*=\s*(\{.*?\})""")
                configPattern.findAll(scriptData).forEach { match ->
                    try {
                        val jsonStr = match.groupValues[1]
                        val json = JSONObject(jsonStr)
                        val sources = json.optJSONArray("sources") ?: json.optJSONArray("files")
                        if (sources != null) {
                            for (i in 0 until sources.length()) {
                                val srcObj = sources.getJSONObject(i)
                                val src = srcObj.optString("file", null) ?: srcObj.optString("url", null) ?: srcObj.optString("src", null)
                                if (src != null) urls.add(src)
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

    } catch (e: Exception) {
        e.printStackTrace()
    }
    return urls.distinct()
}
