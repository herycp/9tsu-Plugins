package com.byayzen

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

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

    private val tag = "JPFilmsLog"

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
        
        Log.d(tag, "=== getMainPage Started ===")
        Log.d(tag, "Section: ${request.name} | Page: $page | Request URL: $url")

        val res = try {
            app.get(url)
        } catch (e: Exception) {
            Log.e(tag, "Network request failed for URL: $url", e)
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        Log.d(tag, "Response Status: ${res.code} | Final URL: ${res.url}")

        val document = res.document
        val elements = document.select("article.thumb.grid-item")
        Log.d(tag, "Found ${elements.size} elements using selector 'article.thumb.grid-item'")

        val home = ArrayList<SearchResponse>()
        for ((index, element) in elements.withIndex()) {
            val title = element.selectFirst(".entry-title")?.text()
            val href = element.selectFirst("a")?.attr("href")
            
            if (title.isNullOrEmpty() || href.isNullOrEmpty()) {
                Log.w(tag, "Item #$index SKIPPED: title='$title', href='$href'")
                continue
            }
            
            var poster = element.selectFirst("img")?.attr("data-src")
            if (poster.isNullOrEmpty()) {
                poster = element.selectFirst("img")?.attr("src")
            }

            Log.d(tag, "Item #$index OK: Title='$title' | Poster='$poster'")
            home.add(newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            })
        }

        Log.d(tag, "Total items successfully loaded for '${request.name}': ${home.size}")
        Log.d(tag, "=== getMainPage Finished ===")

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/search/$query" else "$mainUrl/search/$query/page/$page"
        Log.d(tag, "=== search Started ===")
        Log.d(tag, "Query: '$query' | Search URL: $url")

        val res = app.get(url)
        val document = res.document
        
        val searchResults = ArrayList<SearchResponse>()
        val elements = document.select("article.thumb.grid-item")
        Log.d(tag, "Search elements found: ${elements.size}")
        
        for ((index, element) in elements.withIndex()) {
            val title = element.selectFirst(".entry-title")?.text() ?: continue
            val href = element.selectFirst("a")?.attr("href") ?: continue
            
            var poster = element.selectFirst("img")?.attr("data-src")
            if (poster.isNullOrEmpty()) {
                poster = element.selectFirst("img")?.attr("src")
            }

            searchResults.add(newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            })
        }

        var hasNext = false
        val pagination = document.select("ul.page-numbers li a")
        for (item in pagination) {
            if (item.text().contains((page + 1).toString()) || item.hasClass("next")) {
                hasNext = true
                break
            }
        }

        Log.d(tag, "Search results parsed: ${searchResults.size} | hasNext: $hasNext")
        return newSearchResponseList(searchResults, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.d(tag, "=== load Started ===")
        Log.d(tag, "Loading details from URL: $url")

        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("\\s*\\(\\d{4}\\)$"), "")
            ?.replace("Full HD", "", ignoreCase = true)
            ?.trim()

        if (title.isNullOrEmpty()) {
            Log.e(tag, "Failed to extract title from $url")
            return null
        }

        var poster = document.selectFirst("img.movie-thumb")?.attr("data-src")
        if (poster.isNullOrEmpty()) poster = document.selectFirst("img.movie-thumb")?.attr("src")
        if (poster.isNullOrEmpty()) poster = document.selectFirst(".movie-poster img")?.attr("data-src")
        if (poster.isNullOrEmpty()) poster = document.selectFirst(".movie-poster img")?.attr("src")

        val tags = ArrayList<String>()
        for (tagEl in document.select(".category a")) {
            tags.add(tagEl.text())
        }
        for (country in document.select("p.actors:contains(Country:) a")) {
            tags.add(country.text())
        }

        val actors = ArrayList<Actor>()
        for (director in document.select(".directors a")) {
            actors.add(Actor(director.text(), "Director"))
        }
        for (cast in document.select(".actors a")) {
            if (cast.parent()?.text()?.contains("Country:") == false) {
                actors.add(Actor(cast.text()))
            }
        }

        val ratingTxt = document.selectFirst(".imdb-icon")?.attr("data-rating")?.toDoubleOrNull()
            ?: document.selectFirst(".halim_imdbrating .score")?.text()?.toDoubleOrNull()?.times(2.0)

        val duration = Regex("""(\d+)\s*min""").find(document.select("p.released").text())?.groupValues?.get(1)?.toIntOrNull()
        val year = document.selectFirst("p.released a[href*='release']")?.text()?.toIntOrNull()

        val allEpisodes = ArrayList<Episode>()
        val scripts = document.select("script")
        var scriptData: String? = null
        for (script in scripts) {
            if (script.data().contains("var jsonEpisodes")) {
                scriptData = script.data()
                break
            }
        }

        if (scriptData != null) {
            val jsonStr = scriptData.substringAfter("var jsonEpisodes = ").substringBefore(";</script>").trim().removeSuffix(";")
            Log.d(tag, "Found jsonEpisodes raw: ${jsonStr.take(100)}...")
            try {
                val parsedEpisodes = AppUtils.parseJson<List<List<JpEpisode>>>(jsonStr)
                for (innerList in parsedEpisodes) {
                    for (epObj in innerList) {
                        if (epObj.serverId == 2) {
                            val epUrl = epObj.postUrl?.replace("\\/", "/") ?: continue
                            val epName = epObj.episodeName ?: ""
                            
                            allEpisodes.add(newEpisode(epUrl) {
                                this.name = epName
                                this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error parsing jsonEpisodes", e)
            }
        } else {
            Log.w(tag, "No 'var jsonEpisodes' script tag found")
        }

        Log.d(tag, "Total episodes extracted: ${allEpisodes.size}")

        val recommendations = ArrayList<SearchResponse>()
        val relatedElements = document.select(".related-film article.thumb")
        for (element in relatedElements) {
            val recTitle = element.selectFirst(".entry-title")?.text() ?: continue
            val recHref = element.selectFirst("a")?.attr("href") ?: continue
            var recPoster = element.selectFirst("img")?.attr("data-src")
            if (recPoster.isNullOrEmpty()) recPoster = element.selectFirst("img")?.attr("src")

            recommendations.add(newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPoster
            })
        }

        val plot = document.select("article.item-content p").text().trim()

        return if (allEpisodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, allEpisodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = ratingTxt?.let { Score.from10(it) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                this.posterUrl = poster
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
        Log.d(tag, "=== loadLinks Started ===")
        Log.d(tag, "Episode URL / Data: $data")

        val document = app.get(data).document
        val nonce = document.selectFirst("body")?.attr("data-nonce").orEmpty()
        
        var scriptData: String? = null
        for (script in document.select("script")) {
            if (script.data().contains("var halim_cfg")) {
                scriptData = script.data()
                break
            }
        }
        
        if (scriptData == null) {
            Log.e(tag, "Script 'var halim_cfg' not found on page")
            return false
        }

        val postId = Regex(""""post_id"\s*:\s*(\d+)""").find(scriptData)?.groupValues?.get(1) ?: return false
        val episodeSlug = Regex(""""episode_slug"\s*:\s*"([^"]+)"""").find(scriptData)?.groupValues?.get(1) ?: "server-1"
        val serverId = Regex(""""server"\s*:\s*"([^"]+)"""").find(scriptData)?.groupValues?.get(1) ?: "1"

        val ajaxUrl = "$mainUrl/wp-content/themes/halimmovies/player.php?episode_slug=$episodeSlug&server_id=$serverId&subsv_id=&post_id=$postId&nonce=$nonce&custom_var="
        Log.d(tag, "Ajax Request URL: $ajaxUrl")

        val playerResponse = app.get(
            ajaxUrl,
            headers = mapOf(
                "x-requested-with" to "XMLHttpRequest",
                "referer" to data
            )
        ).text

        val rawStreamUrl = Regex(""""file"\s*:\s*"([^"]+)"""").find(playerResponse)?.groupValues?.get(1)
        if (rawStreamUrl == null) {
            Log.e(tag, "Failed to parse stream file URL from response: $playerResponse")
            return false
        }
        
        val streamUrl = rawStreamUrl.replace("\\/", "/")
        Log.d(tag, "Extracted Stream URL: $streamUrl")

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
