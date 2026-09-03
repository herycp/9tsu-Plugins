package com.byayzen

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

// Modul baru: Menggunakan Data Class untuk menggantikan org.json.JSONArray
data class JpEpisode(
    @JsonProperty("serverId") val serverId: Int? = null,
    @JsonProperty("postUrl") val postUrl: String? = null,
    @JsonProperty("episodeName") val episodeName: String? = null
)

class JPFilms : MainAPI() {
    override var mainUrl = "https://kodasusaka.com"
    override var name = "JPFilms"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/movies?sortby=newest" to "Movies - Newest",
        "$mainUrl/movies?sortby=latest-update" to "Movies - Latest Update",
        "$mainUrl/movies?sortby=mostview" to "Movies - Most View",
        "$mainUrl/tv-series?sortby=newest" to "TV Series - Newest",
        "$mainUrl/tv-series?sortby=latest-update" to "TV Series - Latest Update",
        "$mainUrl/tv-series?sortby=mostview" to "TV Series - Most View"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            val base = request.data.substringBefore("?")
            val query = request.data.substringAfter("?", "")
            if (query.isNotEmpty()) "$base/page/$page/?$query" else "$base/page/$page/"
        }
        
        val document = app.get(url).document
        
        // Fix: Konversi eksplisit ke List<Element> untuk menghilangkan error JSpecify
        val elements: List<Element> = document.select("article.thumb.grid-item").toList()
        val home = elements.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = if (!poster.isNullOrEmpty()) poster else null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/search/$query" else "$mainUrl/search/$query/page/$page"
        val document = app.get(url).document
        
        // Fix: Konversi eksplisit ke List<Element>
        val elements: List<Element> = document.select("article.thumb.grid-item").toList()
        val searchResults = elements.mapNotNull { it.toSearchResult() }

        val pagination: List<Element> = document.select("ul.page-numbers li a").toList()
        val hasNext = pagination.any {
            it.text().contains((page + 1).toString()) || it.hasClass("next")
        }

        return newSearchResponseList(searchResults, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("\\s*\\(\\d{4}\\)$"), "")
            ?.replace("Full HD", "", ignoreCase = true)
            ?.trim() ?: return null

        val poster = document.selectFirst("img.movie-thumb")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: document.selectFirst(".movie-poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        val country = document.select("p.actors:contains(Country:) a").toList().map { it.text() }
        val tags = document.select(".category a").toList().map { it.text() } + country

        val directors = document.select(".directors a").toList().map { Actor(it.text(), "Director") }
        val cast = document.select(".actors a").toList()
            .filter { it.parent()?.text()?.contains("Country:") == false }
            .map { Actor(it.text()) }
        val actors = directors + cast

        val ratingTxt = document.selectFirst(".imdb-icon")?.attr("data-rating")?.toDoubleOrNull()
            ?: document.selectFirst(".halim_imdbrating .score")?.text()?.toDoubleOrNull()?.times(2.0)

        val duration = Regex("""(\d+)\s*min""").find(document.select("p.released").text())?.groupValues?.get(1)?.toIntOrNull()
        val year = document.selectFirst("p.released a[href*='release']")?.text()?.toIntOrNull()

        val allEpisodes = mutableListOf<Episode>()

        // Rombak: Parsing JSON menggunakan AppUtils (Standar Cloudstream) menghindari org.json exception
        val scriptData = document.select("script").toList().map { it.data() }.find { it.contains("var jsonEpisodes") }
        if (scriptData != null) {
            val jsonStr = scriptData.substringAfter("var jsonEpisodes = ").substringBefore(";</script>").trim().removeSuffix(";")
            try {
                val parsedEpisodes = AppUtils.parseJson<List<List<JpEpisode>>>(jsonStr)
                parsedEpisodes.forEach { innerList ->
                    innerList.filter { it.serverId == 2 }.forEach { epObj ->
                        val epUrl = epObj.postUrl?.replace("\\/", "/") ?: return@forEach
                        val epName = epObj.episodeName ?: ""
                        
                        allEpisodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                        })
                    }
                }
            } catch (e: Exception) {
                // Silently fallback if JSON malformed
            }
        }

        val relatedElements: List<Element> = document.select(".related-film article.thumb").toList()
        val recommendations = relatedElements.mapNotNull { it.toSearchResult() }

        val plot = document.select("article.item-content p").text().trim()

        return if (allEpisodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, allEpisodes.firstOrNull()?.data ?: url) {
                this.posterUrl = if (!poster.isNullOrEmpty()) poster else null
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = ratingTxt?.let { Score.from10(it) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            // Fix: Merubah tipe respon ke TvSeries
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                this.posterUrl = if (!poster.isNullOrEmpty()) poster else null
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = ratingTxt?.let { Score.from10(it) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val nonce = document.selectFirst("body")?.attr("data-nonce").orEmpty()
        
        val scripts: List<String> = document.select("script").toList().map { it.data() }
        val scriptData = scripts.find { it.contains("var halim_cfg") } ?: return false

        val postId = Regex(""""post_id"\s*:\s*(\d+)""").find(scriptData)?.groupValues?.get(1) ?: return false
        val episodeSlug = Regex(""""episode_slug"\s*:\s*"([^"]+)"""").find(scriptData)?.groupValues?.get(1) ?: "server-1"
        val serverId = Regex(""""server"\s*:\s*"([^"]+)"""").find(scriptData)?.groupValues?.get(1) ?: "1"

        val ajaxUrl = "$mainUrl/wp-content/themes/halimmovies/player.php?episode_slug=$episodeSlug&server_id=$serverId&subsv_id=&post_id=$postId&nonce=$nonce&custom_var="

        val playerResponse = app.get(
            ajaxUrl,
            headers = mapOf(
                "x-requested-with" to "XMLHttpRequest",
                "referer" to data
            )
        ).text

        val rawStreamUrl = Regex(""""file"\s*:\s*"([^"]+)"""").find(playerResponse)?.groupValues?.get(1) ?: return false
        val streamUrl = rawStreamUrl.replace("\\/", "/")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            }
        )

        return true
    }
}