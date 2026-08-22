package com.drmclmy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Dramacool : MainAPI() {
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "en"
    override var mainUrl = "https://dramacool.my"
    override var name = "Dramacool"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "popular-drama" to "Popular Drama",
        "popular-ongoing-series" to "Ongoing Series",
        "recently-added-drama" to "Recently Added Drama",
        "recently-added-movie" to "Recently Added Movie",
        "popular-completed-series" to "Popular Completed Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val items = document.select("#drama div.card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("a")?.attr("title") ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchTitle = query.replace(" ", "-")
        val url = "$mainUrl/search/$searchTitle"
        val document = app.get(url, referer = "$mainUrl/").document
        return document.select("#drama div.card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val posterUrl = document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }

        // Perbaikan: ActorData menggunakan parameter 'actor' dan 'image'
        val actors = document.select("div.slider div.img-container").map {
            ActorData(
                actor = it.select("div.bottom-right").text(),
                image = it.select("img").attr("src")
            )
        }

        // Perbaikan: gunakan newEpisode
        val episodes = document.select("div.epdiv").mapNotNull { el ->
            val name = el.selectFirst("a")?.text()?.substringAfter("Episode")?.trim() ?: return@mapNotNull null
            val rawHref = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val href = fixUrl(rawHref)
            newEpisode(href, "Episode $name")
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val server = document.selectFirst("#load-iframe")?.attr("onclick")
            ?.substringAfter("playThis(\"")?.substringBefore("\")")

        val iframe = app.get(fixUrl(server ?: return false))
        val iframeDoc = iframe.document

        return loadExtractor(iframeDoc.html(), mainUrl, subtitleCallback, callback)
    }
}
